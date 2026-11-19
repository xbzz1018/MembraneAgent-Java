package com.greenmembrane.platform;

import static com.greenmembrane.platform.BusinessSupport.*;

import io.minio.*;
import io.minio.errors.ErrorResponseException;
import java.io.*;
import java.nio.file.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ObjectStorage {
  private final String mode, bucket;
  private final Path directory;
  private final MinioClient minio;

  public ObjectStorage(
      PlatformSettings cfg,
      @Value("${app.object-store:local}") String mode,
      @Value("${app.minio-endpoint:http://127.0.0.1:9000}") String endpoint,
      @Value("${app.minio-access-key:}") String access,
      @Value("${app.minio-secret-key:}") String secret,
      @Value("${app.minio-bucket:membrane-java}") String bucket) {
    this.mode = mode;
    this.bucket = bucket;
    this.directory = Path.of(cfg.storage()).toAbsolutePath().normalize();
    if (!java.util.Set.of("local", "minio").contains(mode))
      throw new IllegalArgumentException("Unknown object store");
    if ("minio".equals(mode)) {
      if (access.isBlank() || secret.isBlank())
        throw new IllegalStateException("MinIO credentials required");
      minio = MinioClient.builder().endpoint(endpoint).credentials(access, secret).build();
    } else {
      minio = null;
      if (!cfg.demo()) throw new IllegalStateException("Non-demo object storage requires MinIO");
    }
  }

  public void put(String hash, byte[] bytes) {
    validate(hash);
    try {
      if (minio == null) {
        Files.createDirectories(directory);
        try {
          Files.write(directory.resolve(hash), bytes, StandardOpenOption.CREATE_NEW);
        } catch (FileAlreadyExistsException ex) {
          if (!sha(get(hash)).equals(hash)) throw new IOException("Object hash mismatch");
        }
      } else {
        try {
          minio.statObject(StatObjectArgs.builder().bucket(bucket).object(hash).build());
          if (!sha(get(hash)).equals(hash)) throw new IOException("Object hash mismatch");
        } catch (ErrorResponseException ex) {
          if (!"NoSuchKey".equals(ex.errorResponse().code())) throw ex;
          minio.putObject(
              PutObjectArgs.builder().bucket(bucket).object(hash).stream(
                      new ByteArrayInputStream(bytes), bytes.length, -1L)
                  .contentType("application/octet-stream")
                  .build());
        }
      }
    } catch (Exception ex) {
      throw error(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "原件存储失败；检查存储配置和文件哈希");
    }
  }

  public byte[] get(String hash) {
    validate(hash);
    try {
      if (minio == null) return Files.readAllBytes(directory.resolve(hash));
      try (var in = minio.getObject(GetObjectArgs.builder().bucket(bucket).object(hash).build())) {
        byte[] data = in.readNBytes(20 * 1024 * 1024 + 1);
        if (data.length > 20 * 1024 * 1024) throw new IOException("Object exceeds size limit");
        return data;
      }
    } catch (Exception ex) {
      throw error(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "原件读取失败");
    }
  }

  private void validate(String hash) {
    if (!hash.matches("[a-f0-9]{64}"))
      throw error(org.springframework.http.HttpStatus.CONFLICT, "原件标识无效");
  }
}
