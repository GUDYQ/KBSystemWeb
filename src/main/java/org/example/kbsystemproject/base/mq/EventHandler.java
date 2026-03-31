package org.example.kbsystemproject.base.mq;

import org.example.kbsystemproject.entity.mq.EventMessage;
import reactor.core.publisher.Mono;

public interface EventHandler<T> {
    String eventType();
    Class<T> payloadType();
    Mono<Void> handle(EventMessage<T> message);
}
