package com.greenmembrane.platform;

import static com.greenmembrane.platform.BusinessSupport.*;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class ReportService {
  private final BusinessRepository repo;
  private final ReviewService reviews;

  public ReportService(BusinessRepository repo, ReviewService reviews) {
    this.repo = repo;
    this.reviews = reviews;
  }

  public byte[] export(Identity who, String id, String format) {
    var review = repo.review(who, id);
    if (!Set.of("md", "docx").contains(format))
      throw error(HttpStatus.UNPROCESSABLE_ENTITY, "导出格式无效");
    if (!"approved".equals(text(review, "status"))) throw error(HttpStatus.CONFLICT, "正式导出需要人工批准");
    reviews.verifySnapshot(review);
    var commands =
        repo.sql()
            .queryForList(
                "SELECT actor,reason FROM mb_commands WHERE review_id=? AND decision='approved'",
                id);
    if (commands.size() != 1) throw error(HttpStatus.CONFLICT, "缺少唯一审批记录");
    StringBuilder text = new StringBuilder(text(review, "report"));
    for (var issue :
        repo.sql()
            .queryForList("SELECT id,status FROM mb_issues WHERE review_id=? ORDER BY id", id)) {
      text.append("\n问题 ").append(text(issue, "id")).append(" 复核状态：").append(text(issue, "status"));
      for (var event :
          repo.sql()
              .queryForList(
                  "SELECT detail FROM mb_events WHERE project_id=? AND target_id=? AND"
                      + " event_type='issue.resolved' ORDER BY created_at",
                  text(review, "project_id"),
                  text(issue, "id"))) text.append("；处置说明：").append(text(event, "detail"));
    }
    text.append("\n\n审查ID：")
        .append(id)
        .append("\n审查内容SHA：")
        .append(text(review, "content_hash"))
        .append("\n审核人：")
        .append(text(commands.get(0), "actor"))
        .append("\n批准说明：")
        .append(text(commands.get(0), "reason"))
        .append("\n人工批准表示资料复核流程完成，不等于工程安全认证。");
    if ("md".equals(format)) return text.toString().getBytes(StandardCharsets.UTF_8);
    try (var doc = new XWPFDocument();
        var output = new ByteArrayOutputStream()) {
      for (String line : text.toString().split("\n")) {
        var p = doc.createParagraph();
        var run = p.createRun();
        run.setFontFamily("Microsoft YaHei");
        run.setFontSize(line.startsWith("#") ? 16 : 11);
        run.setBold(line.startsWith("#"));
        run.setText(line.replaceFirst("^#+\\s*", ""));
      }
      doc.write(output);
      return output.toByteArray();
    } catch (java.io.IOException ex) {
      throw error(HttpStatus.SERVICE_UNAVAILABLE, "Word导出失败");
    }
  }
}
