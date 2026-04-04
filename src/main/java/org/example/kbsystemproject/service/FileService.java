package org.example.kbsystemproject.service;

import org.example.kbsystemproject.entity.UserToken;
import org.example.kbsystemproject.entity.mq.EventMessage;
import org.example.kbsystemproject.utils.EventMessageJsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.reactive.ReactiveKafkaProducerTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

@Service
public class FileService {

    private static final Logger log = LoggerFactory.getLogger(FileService.class);

//    @Autowired
//    private ReactiveKafkaProducerTemplate<String, EventMessage<?>> producerTemplate;
//    @Value("${app.kafka.topics.events}")
//    private String eventTopic;
//
//    @KafkaListener(topics = "${app.kafka.topics.events}", groupId = "${spring.kafka.consumer.group-id}")
//    public void onEvent(String message, Acknowledgment ack) {
//        try {
//            EventMessage eventMessage = EventMessageJsonUtils.fromJson(message, UserToken.class);
//            log.info("Kafka consumed event: eventId={}, eventType={}, source={}, payload={}",
//                    eventMessage.getEventId(),
//                    eventMessage.getEventType(),
//                    eventMessage.getSource(),
//                    eventMessage.getPayload());
//            ack.acknowledge();
//        } catch (Exception ex) {
//            log.error("Kafka consumed invalid message: {}", message, ex);
//        }
//    }
//
//    public <T> Mono<Void> product(String key, String eventType, String source, T payload) {
//        EventMessage<T> event = EventMessage.<T>builder()
//                .eventId(UUID.randomUUID().toString())
//                .eventType(eventType)
//                .source(source)
//                .timestamp(Instant.now())
//                .payload(payload)
//                .build();
//
//        return producerTemplate.send(eventTopic, key, event)
//                .doOnSuccess(result -> log.info("Kafka produced: topic={}, key={}, partition={}, offset={}",
//                        eventTopic,
//                        key,
//                        result.recordMetadata().partition(),
//                        result.recordMetadata().offset()))
//                .doOnError(ex -> log.error("Kafka produce failed: topic={}, key={}", eventTopic, key, ex))
//                .then();
//    }
}