package com.greenmembrane.platform;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.*;

@Configuration
public class PersistenceConfiguration {
  @Bean MybatisPlusInterceptor paginationInterceptor() {
    var interceptor=new MybatisPlusInterceptor();
    var page=new PaginationInnerInterceptor(DbType.POSTGRE_SQL);
    page.setMaxLimit(100L);
    interceptor.addInnerInterceptor(page);
    return interceptor;
  }
}
