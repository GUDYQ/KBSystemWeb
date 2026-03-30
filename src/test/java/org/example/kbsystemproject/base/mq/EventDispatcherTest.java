package org.example.kbsystemproject.base.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.kbsystemproject.entity.EventMessage;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

class EventDispatcherTest {

    @Test
    void shouldDispatchByEventType() {
        ObjectMapper mapper = new ObjectMapper();
        AtomicReference<UserCreatedPayload> userRef = new AtomicReference<>();
        AtomicReference<OrderPaidPayload> orderRef = new AtomicReference<>();

        EventHandler<UserCreatedPayload> userHandler = new EventHandler<>() {
            @Override
            public String eventType() {
                return "user.created";
            }

            @Override
            public Class<UserCreatedPayload> payloadType() {
                return UserCreatedPayload.class;
            }

            @Override
            public Mono<Void> handle(EventMessage<UserCreatedPayload> message) {
                userRef.set(message.getPayload());
                return Mono.empty();
            }
        };

        EventHandler<OrderPaidPayload> orderHandler = new EventHandler<>() {
            @Override
            public String eventType() {
                return "order.paid";
            }

            @Override
            public Class<OrderPaidPayload> payloadType() {
                return OrderPaidPayload.class;
            }

            @Override
            public Mono<Void> handle(EventMessage<OrderPaidPayload> message) {
                orderRef.set(message.getPayload());
                return Mono.empty();
            }
        };

        EventDispatcher dispatcher = new EventDispatcher(mapper, List.of(userHandler, orderHandler));

        EventMessage<Map<String, Object>> userEvent = new EventMessage<>();
        userEvent.setEventId("e-1");
        userEvent.setEventType("user.created");
        userEvent.setSource("test");
        userEvent.setTimestamp(Instant.now());
        userEvent.setPayload(Map.of("userId", "u100", "nickname", "tom"));

        EventMessage<Map<String, Object>> orderEvent = new EventMessage<>();
        orderEvent.setEventId("e-2");
        orderEvent.setEventType("order.paid");
        orderEvent.setSource("test");
        orderEvent.setTimestamp(Instant.now());
        orderEvent.setPayload(Map.of("orderId", "o200", "amount", 99.5));

        dispatcher.dispatch(userEvent).block();
        dispatcher.dispatch(orderEvent).block();

        Assertions.assertNotNull(userRef.get());
        Assertions.assertEquals("u100", userRef.get().getUserId());
        Assertions.assertEquals("tom", userRef.get().getNickname());

        Assertions.assertNotNull(orderRef.get());
        Assertions.assertEquals("o200", orderRef.get().getOrderId());
        Assertions.assertEquals(99.5, orderRef.get().getAmount());
    }

    @Test
    void shouldFailWhenHandlerMissing() {
        EventDispatcher dispatcher = new EventDispatcher(new ObjectMapper(), List.of());
        EventMessage<String> event = new EventMessage<>();
        event.setEventType("missing.type");
        event.setPayload("x");

        IllegalArgumentException ex = Assertions.assertThrows(IllegalArgumentException.class,
                () -> dispatcher.dispatch(event).block());
        Assertions.assertTrue(ex.getMessage().contains("No handler found"));
    }

    static class UserCreatedPayload {
        private String userId;
        private String nickname;

        public String getUserId() {
            return userId;
        }

        public void setUserId(String userId) {
            this.userId = userId;
        }

        public String getNickname() {
            return nickname;
        }

        public void setNickname(String nickname) {
            this.nickname = nickname;
        }
    }

    static class OrderPaidPayload {
        private String orderId;
        private double amount;

        public String getOrderId() {
            return orderId;
        }

        public void setOrderId(String orderId) {
            this.orderId = orderId;
        }

        public double getAmount() {
            return amount;
        }

        public void setAmount(double amount) {
            this.amount = amount;
        }
    }
}

