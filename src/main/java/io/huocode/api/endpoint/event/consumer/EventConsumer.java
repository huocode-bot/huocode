package io.huocode.api.endpoint.event.consumer;

import static java.util.stream.Collectors.toList;

import io.huocode.api.PojaGenerated;
import io.huocode.api.concurrency.Workers;
import io.huocode.api.endpoint.event.consumer.model.ConsumableEvent;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@PojaGenerated
@Component
@Slf4j
public class EventConsumer implements Consumer<List<ConsumableEvent>> {
  private final Workers<Void> workers;
  private final EventServiceInvoker eventServiceInvoker;

  public EventConsumer(Workers<Void> workers, EventServiceInvoker eventServiceInvoker) {
    this.workers = workers;
    this.eventServiceInvoker = eventServiceInvoker;
  }

  @Override
  public void accept(List<ConsumableEvent> consumableEvents) {
    workers.apply(consumableEvents.stream().map(this::toCallable).collect(toList()));
  }

  private Callable<Void> toCallable(ConsumableEvent consumableEvent) {
    return () -> {
      eventServiceInvoker.accept(consumableEvent.getEvent());
      consumableEvent.ack();
      return null;
    };
  }
}
