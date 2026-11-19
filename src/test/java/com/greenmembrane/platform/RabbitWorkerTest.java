package com.greenmembrane.platform;

import static com.greenmembrane.platform.BusinessSupport.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.*;
import org.springframework.amqp.core.*;
import org.springframework.http.HttpStatus;

class RabbitWorkerTest {
  OutboxService outbox;
  JobProcessor processor;
  RabbitJobWorker worker;
  Channel channel;
  final OutboxService.Claim claim=new OutboxService.Claim("job",0,"claim-token",1);
  @BeforeEach void setup() {
    outbox=mock(OutboxService.class);processor=mock(JobProcessor.class);channel=mock(Channel.class);
    worker=new RabbitJobWorker(outbox,processor,new JobSettings("q","x",1,1,3,60,1,600,0),new ObjectMapper());
    when(outbox.claim("job",0,60000,3,600000)).thenReturn(claim);
  }
  @AfterEach void close() {worker.close();}
  Message message() {
    var props=new MessageProperties();props.setDeliveryTag(7);
    return new Message(json(Map.of("task_id","job","generation",0)).getBytes(StandardCharsets.UTF_8),props);
  }
  @Test void ackFollowsCommittedResult() throws Exception {
    worker.consume(message(),channel);
    var order=inOrder(processor,channel);
    order.verify(processor).processClaimed("job","claim-token");
    order.verify(channel).basicAck(7,false);
  }
  @Test void processingTimeoutStoresRetryBeforeAck() throws Exception {
    doThrow(error(HttpStatus.BAD_GATEWAY,"synthetic downstream timeout")).when(processor).processClaimed(anyString(),anyString());
    worker.consume(message(),channel);
    var order=inOrder(outbox,channel);
    order.verify(outbox).claim("job",0,60000,3,600000);
    order.verify(outbox).failed(claim,true,3,1000,"temporary_processing_failure");
    order.verify(channel).basicAck(7,false);
  }
  @Test void databaseFailureCannotAcknowledgeAndLoseMessage() throws Exception {
    doThrow(new IllegalStateException("synthetic processor error")).when(processor).processClaimed(anyString(),anyString());
    doThrow(new IllegalStateException("synthetic database offline")).when(outbox).failed(any(),anyBoolean(),anyInt(),anyLong(),anyString());
    worker.consume(message(),channel);
    verify(channel,never()).basicAck(anyLong(),anyBoolean());
    verify(channel).basicNack(7,false,true);
  }
  @Test void duplicateIsAcknowledgedWithoutInvokingProcessor() throws Exception {
    when(outbox.claim("job",0,60000,3,600000)).thenReturn(null);
    worker.consume(message(),channel);
    verifyNoInteractions(processor);
    verify(channel).basicAck(7,false);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({
      "401,false,model_credentials_rejected", "403,false,model_credentials_rejected",
      "422,false,model_request_rejected", "429,true,model_temporarily_unavailable",
      "503,true,model_temporarily_unavailable"})
  void upstreamPolicyIsSavedBeforeAcknowledgement(int code, boolean retryable, String reason) throws Exception {
    doThrow(new ModelHttpFailure(code)).when(processor).processClaimed(anyString(),anyString());
    worker.consume(message(),channel);
    var order=inOrder(outbox,channel);
    order.verify(outbox).failed(claim,retryable,3,1000,reason);
    order.verify(channel).basicAck(7,false);
  }
}
