package com.greenmembrane.platform;

import static com.greenmembrane.platform.BusinessSupport.*;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class TaskDispatcher {
  private final PlatformSettings cfg;
  private final JobProcessor processor;
  public TaskDispatcher(PlatformSettings cfg,JobProcessor processor) {
    this.cfg=cfg; this.processor=processor;
    if (!"rabbit".equals(cfg.execution()) && !(cfg.demo() && "inline".equals(cfg.execution())))
      throw new IllegalStateException("Production execution requires RabbitMQ; inline is test/demo only");
  }
  public void start(Map<String,Object> task) {
    if ("inline".equals(cfg.execution()) && "queued".equals(text(task,"status")))
      processor.process(text(task,"id"),"local-inline/"+text(task,"id"),1);
    // RabbitMQ tasks are published only from the transactional outbox after commit.
  }
}
