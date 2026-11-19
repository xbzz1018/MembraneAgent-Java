package com.greenmembrane.platform;

import static com.greenmembrane.platform.BusinessSupport.*;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class CatalogService {
  private final BusinessRepository repo;
  private final DocumentMapper mapper;
  private final SpecificationCache cache;
  public CatalogService(BusinessRepository repo,DocumentMapper mapper,SpecificationCache cache) {
    this.repo=repo;this.mapper=mapper;this.cache=cache;
  }
  public Map<String,Object> page(Identity who,String project,String model,String version,String type,long page,long size) {
    repo.project(who,project);
    if (page<1 || page>10000 || size<1 || size>100) throw error(HttpStatus.UNPROCESSABLE_ENTITY,"分页参数超出范围");
    if ((model!=null && model.length()>128) || (version!=null && version.length()>64)
        || (type!=null && !Set.of("history","design","spec","general").contains(type)))
      throw error(HttpStatus.UNPROCESSABLE_ENTITY,"过滤条件无效");
    var query=new QueryWrapper<DocumentRecord>()
        .select("id","project_id","name","business_version","doc_type","material_model","classification","status","created_at")
        .eq("project_id",project)
        .eq(model!=null && !model.isBlank(),"material_model",model)
        .eq(version!=null && !version.isBlank(),"business_version",version)
        .eq(type!=null && !type.isBlank(),"doc_type",type)
        .orderByDesc("created_at","id");
    var result=mapper.selectMapsPage(new Page<>(page,size),query);
    var rows=result.getRecords();
    if (!rows.isEmpty()) {
      var ids=rows.stream().map(r -> text(r,"id")).toList();
      Map<String,Map<String,Object>> states=new HashMap<>();
      for (var task:mapper.taskStates(project,ids)) states.put(text(task,"target_id"),task);
      Map<String,Object> counts=new HashMap<>();
      for (var row:mapper.chunkCounts(project,ids)) counts.put(text(row,"document_id"),row.get("chunk_count"));
      for (var row:rows) {
        row.put("processing",states.getOrDefault(text(row,"id"),Map.of()));
        row.put("chunk_count",counts.getOrDefault(text(row,"id"),0L));
      }
    }
    return Map.of("items",rows,"page",page,"size",size,"total",result.getTotal());
  }
  public Map<String,Object> specifications(Identity who,String project,String model,String version) {
    // Authorization always precedes cache access, including cache hits.
    repo.project(who,project);
    if (model==null || model.isBlank() || model.length()>128 || version==null || version.isBlank() || version.length()>64)
      throw error(HttpStatus.UNPROCESSABLE_ENTITY,"请提供明确型号与版次");
    return cache.read(project,model,version,() -> {
      var rows=mapper.specifications(project,model,version);
      if (rows.size()>500) throw error(HttpStatus.UNPROCESSABLE_ENTITY,"规格资料过多，请缩小资料范围");
      return rows;
    });
  }
}
