package com.greenmembrane.platform;

import com.baomidou.mybatisplus.annotation.*;

@TableName("mb_documents")
public class DocumentRecord {
  @TableId(value="id",type=IdType.INPUT)
  private String id;
  public String getId() { return id; }
  public void setId(String value) { this.id=value; }
  @TableField("project_id")
  private String projectId;
  public String getProjectId() { return projectId; }
  public void setProjectId(String value) { this.projectId=value; }
  @TableField("name")
  private String name;
  public String getName() { return name; }
  public void setName(String value) { this.name=value; }
  @TableField("sha256")
  private String sha256;
  public String getSha256() { return sha256; }
  public void setSha256(String value) { this.sha256=value; }
  @TableField("business_version")
  private String businessVersion;
  public String getBusinessVersion() { return businessVersion; }
  public void setBusinessVersion(String value) { this.businessVersion=value; }
  @TableField("doc_type")
  private String docType;
  public String getDocType() { return docType; }
  public void setDocType(String value) { this.docType=value; }
  @TableField("material_model")
  private String materialModel;
  public String getMaterialModel() { return materialModel; }
  public void setMaterialModel(String value) { this.materialModel=value; }
  @TableField("classification")
  private String classification;
  public String getClassification() { return classification; }
  public void setClassification(String value) { this.classification=value; }
  @TableField("object_path")
  private String objectPath;
  public String getObjectPath() { return objectPath; }
  public void setObjectPath(String value) { this.objectPath=value; }
  @TableField("status")
  private String status;
  public String getStatus() { return status; }
  public void setStatus(String value) { this.status=value; }
  @TableField("created_at")
  private String createdAt;
  public String getCreatedAt() { return createdAt; }
  public void setCreatedAt(String value) { this.createdAt=value; }
}
