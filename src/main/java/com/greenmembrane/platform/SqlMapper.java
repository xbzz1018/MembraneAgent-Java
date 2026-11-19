package com.greenmembrane.platform;

import java.util.*;
import org.apache.ibatis.annotations.*;

/** Internal, server-owned statements only. No API or Agent may supply a SQL statement. */
@Mapper
interface SqlMapper {
  @SelectProvider(type=SqlProvider.class, method="statement")
  @Options(useCache=false, flushCache=Options.FlushCachePolicy.TRUE)
  List<Map<String,Object>> select(@Param("sql") String sql, @Param("args") Object[] args);
  @UpdateProvider(type=SqlProvider.class, method="statement")
  int update(@Param("sql") String sql, @Param("args") Object[] args);
}
