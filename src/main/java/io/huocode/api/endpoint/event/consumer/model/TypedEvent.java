package io.huocode.api.endpoint.event.consumer.model;

import io.huocode.api.PojaGenerated;
import io.huocode.api.endpoint.event.model.PojaEvent;

@PojaGenerated
public record TypedEvent(String typeName, PojaEvent payload) {}
