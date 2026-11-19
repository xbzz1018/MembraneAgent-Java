package com.greenmembrane.platform;

import static com.greenmembrane.platform.BusinessSupport.*;
import java.time.Duration;
import java.util.*;
import java.util.function.Supplier;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.*;

@Service
public class SpecificationCache {
  private static final Logger log=LoggerFactory.getLogger(SpecificationCache.class);
  private final StringRedisTemplate redis;
  private final boolean enabled;
  private final Duration ttl;
  public SpecificationCache(StringRedisTemplate redis,
      @Value("$"+"{app.spec-cache.enabled:false}") boolean enabled,
      @Value("$"+"{app.spec-cache.ttl-seconds:120}") int seconds) {
    if (seconds<1 || seconds>3600) throw new IllegalArgumentException("Invalid specification cache TTL");
    this.redis=redis;this.enabled=enabled;this.ttl=Duration.ofSeconds(seconds);
  }
  String key(String project,String model,String version) {
    return "membrane:java:spec:v1:"+sha(json(List.of(project,model,version)));
  }
  public Map<String,Object> read(String project,String model,String version,Supplier<List<Map<String,Object>>> load) {
    String key=key(project,model,version), mode=enabled ? "miss" : "disabled";
    if (enabled) try {
      String value=redis.opsForValue().get(key);
      if (value!=null) return Map.of("results",list(value),"cache","hit");
    } catch (RuntimeException ex) { mode="unavailable"; }
    var result=load.get();
    if (enabled && !"unavailable".equals(mode)) try { redis.opsForValue().set(key,json(result),ttl); }
    catch (RuntimeException ex) { mode="unavailable"; }
    return Map.of("results",result,"cache",mode);
  }
  public void invalidateAfterCommit(String project,String model,String version) {
    if (!enabled) return;
    Runnable evict=() -> {
      try { redis.delete(key(project,model,version)); }
      catch (RuntimeException ex) { log.warn("Specification cache eviction unavailable; TTL bounds stale entries"); }
    };
    if (TransactionSynchronizationManager.isActualTransactionActive())
      TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override public void afterCommit() { evict.run(); }
      });
    else evict.run();
  }
}
