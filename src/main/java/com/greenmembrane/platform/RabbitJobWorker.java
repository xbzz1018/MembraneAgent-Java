package com.greenmembrane.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.concurrent.*;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
@ConditionalOnProperty(name="app.execution",havingValue="rabbit")
public class RabbitJobWorker {
  private final OutboxService outbox;
  private final JobProcessor processor;
  private final JobSettings cfg;
  private final ObjectMapper json;
  private final ScheduledExecutorService pulses=Executors.newScheduledThreadPool(2);
  public RabbitJobWorker(OutboxService outbox,JobProcessor processor,JobSettings cfg,ObjectMapper json) {
    this.outbox=outbox;this.processor=processor;this.cfg=cfg;this.json=json;
  }
  @PreDestroy public void close() { pulses.shutdownNow(); }

  @RabbitListener(queues="$"+"{app.jobs.queue:membrane.java.tasks.v1}",containerFactory="jobListenerFactory")
  public void consume(Message message,Channel channel) throws IOException {
    try {
      handle(message,channel);
    } catch (RuntimeException ex) {
      // MANUAL mode: explicitly requeue an unsettled delivery when the DB cannot save
      // the result/retry intent. Bound a database-outage redelivery loop per consumer.
      org.slf4j.LoggerFactory.getLogger(RabbitJobWorker.class).warn("Task persistence unavailable; requeueing delivery");
      try { Thread.sleep(250); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
      channel.basicNack(message.getMessageProperties().getDeliveryTag(),false,true);
    }
  }

  private void handle(Message message,Channel channel) throws IOException {
    long tag=message.getMessageProperties().getDeliveryTag();
    String id;
    int generation;
    try {
      if (message.getBody().length>4096) throw new IllegalArgumentException("Oversized job envelope");
      var payload=json.readTree(message.getBody());
      id=payload.path("task_id").asText("");
      if (id.isBlank() || id.length()>64 || (!payload.path("generation").isIntegralNumber() || !payload.path("generation").canConvertToInt()))
        throw new IllegalArgumentException("Invalid job envelope");
      generation=payload.path("generation").intValue();
      if (generation<0) throw new IllegalArgumentException("Invalid generation");
    } catch (Exception ex) { channel.basicReject(tag,false); return; }
    var claim=outbox.claim(id,generation,cfg.leaseSeconds()*1000L,cfg.maxAttempts(),cfg.maxRunSeconds()*1000L);
    if (claim==null) { channel.basicAck(tag,false); return; }
    var pulse=pulses.scheduleWithFixedDelay(() -> {
      try {
        if (!outbox.renew(claim,cfg.leaseSeconds()*1000L))
          outbox.failed(claim,true,cfg.maxAttempts(),cfg.retrySeconds()*1000L,"execution_lease_lost");
      } catch (RuntimeException ex) {
        // Keep the scheduler alive; DB deadline/lease checks still fence this execution.
        org.slf4j.LoggerFactory.getLogger(RabbitJobWorker.class)
            .warn("Task heartbeat failed task_id={} generation={} attempt={}",id,generation,claim.attempt());
      }
    },cfg.leaseSeconds()/3,cfg.leaseSeconds()/3,TimeUnit.SECONDS);
    try {
      processor.processClaimed(id,claim.token());
    } catch (ModelHttpFailure ex) {
      fail(claim,ex.retryable(),ex.taskError());
    } catch (RuntimeException ex) {
      int status=ex instanceof ResponseStatusException b ? b.getStatusCode().value() : 503;
      boolean retryable=status>=500 || status==408 || status==429;
      Throwable cause=ex;
      for(int i=0;i<8 && cause.getCause()!=null && cause!=cause.getCause();i++) cause=cause.getCause();
      org.slf4j.LoggerFactory.getLogger(RabbitJobWorker.class).warn(
          "Task processing exception task_id={} generation={} attempt={} status={} exception={} cause={}",
          id,generation,claim.attempt(),status,ex.getClass().getSimpleName(),cause.getClass().getSimpleName());
      fail(claim,retryable,retryable ? "temporary_processing_failure" : "invalid_document_or_state");
    } finally { pulse.cancel(false); }
    // Ack only after the result or durable retry intent commits.
    channel.basicAck(tag,false);
  }

  private void fail(OutboxService.Claim claim, boolean retryable, String reason) {
    long delay=cfg.retrySeconds()*1000L*(1L<<Math.min(6,claim.attempt()-1));
    outbox.failed(claim,retryable,cfg.maxAttempts(),delay,reason);
  }
}
