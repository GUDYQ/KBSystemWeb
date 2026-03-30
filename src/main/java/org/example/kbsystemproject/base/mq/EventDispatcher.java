package org.example.kbsystemproject.base.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.kbsystemproject.entity.EventMessage;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class EventDispatcher {

    private final ObjectMapper objectMapper;
    private final Map<String, EventHandler<?>> handlers;

    public EventDispatcher(ObjectMapper objectMapper, List<EventHandler<?>> handlerList) {
        this.objectMapper = objectMapper;
        this.handlers = handlerList.stream().collect(Collectors.toMap(EventHandler::eventType, Function.identity()));
    }

    public Mono<Void> dispatch(EventMessage<?> message) {
        EventHandler<?> handler = handlers.get(message.getEventType());
        if (handler == null) {
            return Mono.error(new IllegalArgumentException("No handler found for eventType: " + message.getEventType()));
        }
        return doHandle(handler, message);
    }

    private <T> Mono<Void> doHandle(EventHandler<T> handler, EventMessage<?> message) {
        T payload = objectMapper.convertValue(message.getPayload(), handler.payloadType());

        EventMessage<T> typed = new EventMessage<>();
        typed.setEventId(message.getEventId());
        typed.setEventType(message.getEventType());
        typed.setSource(message.getSource());
        typed.setTimestamp(message.getTimestamp());
        typed.setPayload(payload);

        return handler.handle(typed);
    }
}
