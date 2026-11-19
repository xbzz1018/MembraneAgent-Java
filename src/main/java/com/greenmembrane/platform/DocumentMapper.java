package com.greenmembrane.platform;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.*;
import org.apache.ibatis.annotations.*;

@Mapper
public interface DocumentMapper extends BaseMapper<DocumentRecord> {
  @Select("""
      <script>SELECT target_id,status,generation,attempt,last_error FROM mb_tasks
      WHERE project_id=#{project} AND task_type='ingest' AND target_id IN
      <foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>
      </script>
      """)
  List<Map<String,Object>> taskStates(@Param("project") String project,@Param("ids") List<String> ids);

  @Select("""
      <script>SELECT document_id,COUNT(*) AS chunk_count FROM mb_evidence
      WHERE project_id=#{project} AND document_id IN
      <foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>
      GROUP BY document_id</script>
      """)
  List<Map<String,Object>> chunkCounts(@Param("project") String project,@Param("ids") List<String> ids);

  @Select("""
      SELECT d.id AS document_id,d.name,d.business_version,d.material_model,d.classification,
      e.id AS evidence_id,e.body,e.location
      FROM mb_documents d JOIN mb_evidence e ON e.document_id=d.id
      WHERE d.project_id=#{project} AND d.doc_type='spec' AND d.status='indexed'
      AND d.material_model=#{model} AND d.business_version=#{version}
      ORDER BY d.id,e.chunk_index LIMIT 501
      """)
  List<Map<String,Object>> specifications(@Param("project") String project,@Param("model") String model,@Param("version") String version);
}
