package com.greenmembrane.platform;

import static com.greenmembrane.platform.BusinessSupport.*;

import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

@Service
public class DocumentService {
  private final BusinessRepository repo;
  private final TransactionTemplate tx;
  private final ObjectStorage objects;

  public DocumentService(BusinessRepository repo, TransactionTemplate tx, ObjectStorage objects) {
    this.repo = repo;
    this.tx = tx;
    this.objects = objects;
  }

  public Map<String, Object> upload(
      Identity who,
      String project,
      String version,
      String type,
      String model,
      String classification,
      MultipartFile file) {
    repo.project(who, project);
    repo.role(who, "BUSINESS", "DESIGNER");
    if (!Set.of("history", "design", "spec", "general").contains(type)
        || !Set.of("public", "internal", "restricted").contains(classification))
      throw error(HttpStatus.UNPROCESSABLE_ENTITY, "资料类型或分级无效");
    if (version.isBlank() || version.length() > 64 || model.length() > 128)
      throw error(HttpStatus.UNPROCESSABLE_ENTITY, "版本或型号字段无效");
    String name = Objects.toString(file.getOriginalFilename(), "").replace('\\', '/');
    name = name.substring(name.lastIndexOf('/') + 1);
    if (name.isBlank()
        || name.length() > 256
        || !name.toLowerCase(Locale.ROOT).matches(".*\\.(txt|md|pdf|docx)$"))
      throw error(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "文件类型不受支持");
    try {
      byte[] bytes = file.getBytes();
      if (bytes.length == 0 || bytes.length > 20 * 1024 * 1024)
        throw error(HttpStatus.PAYLOAD_TOO_LARGE, "文件大小无效");
      String hash = sha(bytes);
      objects.put(hash, bytes);
      String finalName = name;
      try {
        return tx.execute(
            status -> {
              var existing =
                  repo.one(
                      "SELECT * FROM mb_documents WHERE project_id=? AND sha256=? AND"
                          + " business_version=?",
                      project,
                      hash,
                      version);
              if (existing != null) {
                validateMetadata(existing, type, model, classification);
                return existing;
              }
              String id = id();
              repo.sql()
                  .update(
                      "INSERT INTO mb_documents VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                      id,
                      project,
                      finalName,
                      hash,
                      version,
                      type,
                      model,
                      classification,
                      hash,
                      "uploaded",
                      now());
              repo.event(project, who.userId(), "document.uploaded", id, hash);
              return repo.required("SELECT * FROM mb_documents WHERE id=?", id);
            });
      } catch (DuplicateKeyException ex) {
        var existing =
            repo.required(
                "SELECT * FROM mb_documents WHERE project_id=? AND sha256=? AND business_version=?",
                project,
                hash,
                version);
        validateMetadata(existing, type, model, classification);
        return existing;
      }
    } catch (org.springframework.web.server.ResponseStatusException ex) {
      throw ex;
    } catch (java.io.IOException ex) {
      throw error(HttpStatus.SERVICE_UNAVAILABLE, "文件存储失败");
    }
  }

  private void validateMetadata(
      Map<String, Object> row, String type, String model, String classification) {
    if (!type.equals(text(row, "doc_type"))
        || !model.equals(text(row, "material_model"))
        || !classification.equals(text(row, "classification")))
      throw error(HttpStatus.CONFLICT, "相同内容和版本的业务声明不同，请创建新业务版本");
  }

  public byte[] bytes(Map<String, Object> document) {
    byte[] data = objects.get(text(document, "object_path"));
    if (!sha(data).equals(text(document, "sha256"))) throw error(HttpStatus.CONFLICT, "原始资料哈希不符");
    return data;
  }
}
