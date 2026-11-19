package com.greenmembrane.platform;

import static com.greenmembrane.platform.BusinessSupport.*;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class DocumentParser {
  public record Chunk(String text, String location) {}

  public List<Chunk> parse(String filename, byte[] data) {
    List<Chunk> chunks = new ArrayList<>();
    String lower = filename.toLowerCase(Locale.ROOT);
    try {
      if (lower.endsWith(".txt") || lower.endsWith(".md")) {
        String text =
            StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(data))
                .toString();
        split(chunks, text, "正文");
      } else if (lower.endsWith(".docx")) {
        try (var doc = new XWPFDocument(new ByteArrayInputStream(data))) {
          int i = 0;
          for (var element : doc.getBodyElements()) {
            i++;
            if (element instanceof org.apache.poi.xwpf.usermodel.XWPFParagraph p)
              split(chunks, p.getText(), "段落" + i);
            if (element instanceof org.apache.poi.xwpf.usermodel.XWPFTable table) {
              int r = 0;
              for (var row : table.getRows()) {
                String text =
                    String.join(" | ", row.getTableCells().stream().map(c -> c.getText()).toList());
                split(chunks, text, "表格" + i + "行" + (++r));
              }
            }
          }
        }
      } else if (lower.endsWith(".pdf")) {
        try (var doc = Loader.loadPDF(data)) {
          if (doc.getNumberOfPages() > 200)
            throw error(HttpStatus.UNPROCESSABLE_ENTITY, "PDF 超过200页");
          var stripper = new PDFTextStripper();
          for (int page = 1; page <= doc.getNumberOfPages(); page++) {
            stripper.setStartPage(page);
            stripper.setEndPage(page);
            String text = stripper.getText(doc);
            if (text.isBlank())
              throw error(HttpStatus.UNPROCESSABLE_ENTITY, "PDF 存在无文本页，需要 OCR；本 Java 解析器未配置 OCR");
            split(chunks, text, "第" + page + "页");
          }
        }
      } else
        throw error(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "首版支持 UTF-8 TXT/Markdown、DOCX 和带文本层 PDF");
      if (chunks.isEmpty() || chunks.size() > 500)
        throw error(HttpStatus.UNPROCESSABLE_ENTITY, "资料为空或片段数超过500");
      return chunks;
    } catch (org.springframework.web.server.ResponseStatusException ex) {
      throw ex;
    } catch (Exception ex) {
      throw error(HttpStatus.UNPROCESSABLE_ENTITY, "文件解析失败；检查格式或编码");
    }
  }

  private void split(List<Chunk> out, String text, String location) {
    text = text.trim();
    if (text.isBlank()) return;
    int from = 0;
    while (from < text.length()) {
      int to = Math.min(text.length(), from + 800);
      if (to < text.length()) {
        int boundary = text.lastIndexOf('\n', to);
        if (boundary > from + 400) to = boundary;
      }
      if (out.size() >= 500) throw error(HttpStatus.UNPROCESSABLE_ENTITY, "片段数超过500");
      out.add(new Chunk(text.substring(from, to), location + "字符" + from + "-" + to));
      if (to == text.length()) break;
      from = to - 100;
    }
  }
}
