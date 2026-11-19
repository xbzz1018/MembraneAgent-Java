package com.greenmembrane.platform;

import org.springframework.amqp.core.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name="app.execution",havingValue="rabbit")
public class RabbitConfiguration {
  @Bean Declarables jobTopology(JobSettings cfg) {
    var exchange=new DirectExchange(cfg.exchange(),true,false);
    var dead=new DirectExchange(cfg.exchange()+".dead",true,false);
    var queue=QueueBuilder.durable(cfg.queue()).deadLetterExchange(dead.getName()).deadLetterRoutingKey("invalid").build();
    var failed=QueueBuilder.durable(cfg.queue()+".dead").build();
    return new Declarables(exchange,dead,queue,failed,
        BindingBuilder.bind(queue).to(exchange).with("process"),
        BindingBuilder.bind(failed).to(dead).with("invalid"));
  }
  @Bean RabbitTemplate rabbitTemplate(ConnectionFactory connection) {
    var template=new RabbitTemplate(connection);
    template.setMandatory(true);
    return template;
  }
  @Bean SimpleRabbitListenerContainerFactory jobListenerFactory(ConnectionFactory connection,JobSettings cfg,
      @Value("${app.jobs.worker-enabled:true}") boolean workerEnabled) {
    var factory=new SimpleRabbitListenerContainerFactory();
    factory.setConnectionFactory(connection);
    factory.setAutoStartup(workerEnabled);
    factory.setConcurrentConsumers(cfg.concurrency());
    factory.setMaxConcurrentConsumers(cfg.concurrency());
    factory.setPrefetchCount(cfg.prefetch());
    factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
    factory.setDefaultRequeueRejected(true);
    factory.setMissingQueuesFatal(false);
    factory.setContainerCustomizer(container -> container.setShutdownTimeout(20_000L));
    return factory;
  }
}
