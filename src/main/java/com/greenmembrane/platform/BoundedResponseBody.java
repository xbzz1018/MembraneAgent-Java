package com.greenmembrane.platform;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/** Limit bytes while receiving the body; checking a fully materialized String is too late. */
final class BoundedResponseBody implements HttpResponse.BodySubscriber<String> {
  private final int maximumBytes;
  private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
  private final CompletableFuture<String> result = new CompletableFuture<>();
  private Flow.Subscription subscription;

  BoundedResponseBody(int maximumBytes) {
    if (maximumBytes < 1) throw new IllegalArgumentException("Body size limit must be positive");
    this.maximumBytes = maximumBytes;
  }

  @Override
  public CompletionStage<String> getBody() {
    return result;
  }

  @Override
  public void onSubscribe(Flow.Subscription incoming) {
    if (subscription != null) {
      incoming.cancel();
      return;
    }
    subscription = incoming;
    incoming.request(1);
  }

  @Override
  public void onNext(List<ByteBuffer> chunks) {
    if (result.isDone()) return;
    for (ByteBuffer chunk : chunks) {
      if (chunk.remaining() > maximumBytes - buffer.size()) {
        subscription.cancel();
        result.completeExceptionally(new IOException("Downstream response exceeds byte limit"));
        return;
      }
      byte[] bytes = new byte[chunk.remaining()];
      chunk.get(bytes);
      buffer.writeBytes(bytes);
    }
    subscription.request(1);
  }

  @Override
  public void onError(Throwable error) {
    result.completeExceptionally(error);
  }

  @Override
  public void onComplete() {
    result.complete(buffer.toString(StandardCharsets.UTF_8));
  }
}
