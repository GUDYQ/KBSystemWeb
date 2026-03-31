package org.example.kbsystemproject.service;

import org.example.kbsystemproject.entity.mq.EventMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

@Service
public class FileService {

    private static final Logger log = LoggerFactory.getLogger(FileService.class);

    @Autowired
    private KafkaTemplate<String, EventMessage<?>> kafkaTemplate;
    @Value("${app.kafka.topics.events}")
    private String eventTopic;

    @KafkaListener(topics = "${app.kafka.topics.events}", groupId = "${spring.kafka.consumer.group-id}")
    public void onEvent(EventMessage<?> message) {
        log.info("Kafka consumed event: eventId={}, eventType={}, source={}, payload={}",
                message != null ? message.getEventId() : null,
                message != null ? message.getEventType() : null,
                message != null ? message.getSource() : null,
                message != null ? message.getPayload() : null);
    }

    public <T> void product(String key, String eventType, String source, T payload) {
        EventMessage<T> event = EventMessage.<T>builder()
                .eventId(UUID.randomUUID().toString())
                .eventType(eventType)
                .source(source)
                .timestamp(Instant.now())
                .payload(payload)
                .build();

        kafkaTemplate.send(eventTopic, key, event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Kafka produce failed: topic={}, key={}", eventTopic, key, ex);
                    } else {
                        log.info("Kafka produced: topic={}, key={}, partition={}, offset={}",
                                eventTopic,
                                key,
                                result.getRecordMetadata().partition(),
                                result.getRecordMetadata().offset());
                    }
                });
    }
}