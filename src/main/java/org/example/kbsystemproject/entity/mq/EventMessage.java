package org.example.kbsystemproject.entity.mq;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EventMessage<T> implements Serializable {
    private String eventId;
    private String eventType;
    private String source;
    private Instant timestamp;
    private T payload;
}

