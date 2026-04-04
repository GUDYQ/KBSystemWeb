package org.example.kbsystemproject.utils;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.kbsystemproject.entity.mq.EventMessage;

public final class EventMessageJsonUtils {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().findAndRegisterModules();

    public static <T> EventMessage<T> fromJson(String rawJson, Class<T> payloadClass) {
        try {
            JavaType type = OBJECT_MAPPER.getTypeFactory().constructParametricType(EventMessage.class, payloadClass);
            return OBJECT_MAPPER.readValue(rawJson, type);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid EventMessage json with payloadClass=" + payloadClass, e);
        }
    }
}

