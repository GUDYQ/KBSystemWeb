package org.example.kbsystemproject.base.mq;

import org.example.kbsystemproject.entity.mq.EventMessage;
import reactor.core.publisher.Mono;

public interface EventPublisher {
    <T> Mono<Void> publish(String key, EventMessage<T> message);
    <T> Mono<Void> publish(String topic, String key, EventMessage<T> message);
}