package com.greenmembrane.platform;

import java.util.*;
import org.springframework.stereotype.Repository;

/** Custom MyBatis statements for locks, PostgreSQL vectors and conditional state transitions. */
@Repository
public class SqlAccess {
  private final SqlMapper mapper;
  public SqlAccess(SqlMapper mapper) { this.mapper=mapper; }
  public List<Map<String,Object>> queryForList(String sql,Object... args) { return mapper.select(sql,args); }
  public int update(String sql,Object... args) { return mapper.update(sql,args); }
  public <T> T queryForObject(String sql,Class<T> type,Object... args) {
    var rows=queryForList(sql,args);
    if (rows.size()!=1 || rows.get(0).size()!=1) throw new IllegalStateException("Expected single scalar");
    Object value=rows.get(0).values().iterator().next();
    if (value instanceof Number n) {
      if (type==Integer.class) return type.cast(n.intValue());
      if (type==Long.class) return type.cast(n.longValue());
    }
    return type.cast(value);
  }
}
