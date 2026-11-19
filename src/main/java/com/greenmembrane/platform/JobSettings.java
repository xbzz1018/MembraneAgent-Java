package com.greenmembrane.platform;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("app.jobs")
public record JobSettings(
    @DefaultValue("membrane.java.tasks.v1") String queue,
    @DefaultValue("membrane.java.tasks.v1") String exchange,
    @DefaultValue("2") int concurrency,
    @DefaultValue("2") int prefetch,
    @DefaultValue("3") int maxAttempts,
    @DefaultValue("60") int leaseSeconds,
    @DefaultValue("5") int retrySeconds,
    @DefaultValue("600") int maxRunSeconds,
    @DefaultValue("0") int projectDispatchWindow) {
  public JobSettings {
    if (concurrency<1 || concurrency>32 || prefetch<1 || prefetch>100 || maxAttempts<1
        || maxAttempts>10 || leaseSeconds<10 || retrySeconds<1 || maxRunSeconds<10
        || projectDispatchWindow<0 || projectDispatchWindow>100)
      throw new IllegalArgumentException("Invalid Rabbit worker limits");
  }
}
