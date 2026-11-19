package com.greenmembrane.platform;

import java.util.Map;

public final class SqlProvider {
  private SqlProvider() {}
  public static String statement(Map<String,Object> parameters) {
    String sql=(String)parameters.get("sql");
    Object[] args=(Object[])parameters.get("args");
    StringBuilder bound=new StringBuilder();
    boolean quoted=false;
    int index=0;
    for (int i=0;i<sql.length();i++) {
      char c=sql.charAt(i);
      if (c=='\'') {
        bound.append(c);
        if (quoted && i+1<sql.length() && sql.charAt(i+1)=='\'') { bound.append(sql.charAt(++i)); continue; }
        quoted=!quoted;
      } else if (c=='?' && !quoted) {
        bound.append("#{args[").append(index++).append("]}");
      } else bound.append(c);
    }
    if (quoted || index!=args.length) throw new IllegalArgumentException("Invalid SQL parameter count");
    return bound.toString();
  }
}
