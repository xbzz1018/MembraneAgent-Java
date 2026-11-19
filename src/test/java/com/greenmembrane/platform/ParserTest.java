package com.greenmembrane.platform;

import static org.assertj.core.api.Assertions.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.*;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

class ParserTest {
  DocumentParser parser = new DocumentParser();

  @Test
  void officeTablesKeepTheirLocation() throws Exception {
    try (var doc = new XWPFDocument();
        var output = new ByteArrayOutputStream()) {
      doc.createParagraph().createRun().setText("SYNTHETIC material specification");
      var table = doc.createTable(1, 2);
      table.getRow(0).getCell(0).setText("MODEL-T1");
      table.getRow(0).getCell(1).setText("4000 N/5cm");
      doc.write(output);
      var chunks = parser.parse("synthetic.docx", output.toByteArray());
      assertThat(chunks)
          .anySatisfy(
              c -> {
                assertThat(c.location()).contains("表格", "行");
                assertThat(c.text()).contains("MODEL-T1", "4000");
              });
    }
  }

  @Test
  void textPdfRetainsPageNumber() throws Exception {
    try (var doc = new PDDocument();
        var output = new ByteArrayOutputStream()) {
      var page = new PDPage();
      doc.addPage(page);
      try (var content = new PDPageContentStream(doc, page)) {
        content.beginText();
        content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
        content.newLineAtOffset(20, 700);
        content.showText("SYNTHETIC MODEL-T1 4000 N/5cm");
        content.endText();
      }
      doc.save(output);
      assertThat(parser.parse("synthetic.pdf", output.toByteArray()).get(0).location())
          .startsWith("第1页");
    }
  }

  @Test
  void scannedPdfFailsExplicitly() throws Exception {
    try (var doc = new PDDocument();
        var output = new ByteArrayOutputStream()) {
      doc.addPage(new PDPage());
      doc.save(output);
      assertThatThrownBy(() -> parser.parse("scan.pdf", output.toByteArray()))
          .hasMessageContaining("OCR");
    }
  }

  @Test
  void longTextHasBoundedChunksWithOverlap() {
    var chunks = parser.parse("long.md", "A".repeat(2000).getBytes(StandardCharsets.UTF_8));
    assertThat(chunks).hasSize(3);
    assertThat(chunks.get(1).location()).contains("700-1500");
  }
}
