package com.greenmembrane.platform;

import static com.greenmembrane.platform.BusinessSupport.*;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name="app.execution",havingValue="rabbit")
public class OutboxPublisher {
  private final OutboxService outbox;
  private final RabbitTemplate rabbit;
  private final JobSettings cfg;
  public OutboxPublisher(OutboxService outbox,RabbitTemplate rabbit,JobSettings cfg) {
    this.outbox=outbox;this.rabbit=rabbit;this.cfg=cfg;
  }
  public boolean publishOne() {
    var delivery=outbox.next(15000,cfg.projectDispatchWindow());
    if (delivery==null) return false;
    var correlation=new CorrelationData(delivery.sendToken());
    try {
      rabbit.convertAndSend(cfg.exchange(),"process",
          json(Map.of("task_id",delivery.taskId(),"generation",delivery.generation())),
          message -> {
            message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
            message.getMessageProperties().setContentType("application/json");
            message.getMessageProperties().setMessageId(delivery.taskId()+":"+delivery.generation());
            return message;
          },correlation);
      var confirm=correlation.getFuture().get(5,TimeUnit.SECONDS);
      if (!confirm.isAck() || correlation.getReturned()!=null)
        throw new IllegalStateException("Message not routed and confirmed");
      outbox.published(delivery);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      outbox.publishFailed(delivery, cfg.retrySeconds()*1000L);
    } catch (Exception ex) {
      outbox.publishFailed(delivery, cfg.retrySeconds()*1000L);
    }
    return true;
  }
  @Scheduled(fixedDelayString="${app.jobs.poll-ms:1000}", initialDelayString="${app.jobs.initial-delay-ms:0}")
  public void tick() {
    outbox.recover(cfg.maxAttempts());
    for (int i=0;i<20;i++) if (!publishOne()) break;
  }
}
