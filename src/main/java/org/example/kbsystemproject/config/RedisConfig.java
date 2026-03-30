package org.example.kbsystemproject.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Configuration
public class RedisConfig {
    @Bean
    public ReactiveRedisTemplate<String, String> reactiveRedisTemplate(ReactiveRedisConnectionFactory connectionFactory) {
        StringRedisSerializer stringRedisSerializer = new StringRedisSerializer();
        RedisSerializationContext<String, String>  context =
                RedisSerializationContext.<String, String>newSerializationContext()
                .key(stringRedisSerializer)
                .value(stringRedisSerializer)
                .hashKey(stringRedisSerializer)
                .hashValue(stringRedisSerializer)
                .build();
        return new ReactiveRedisTemplate<>(connectionFactory, context);

    }
}
