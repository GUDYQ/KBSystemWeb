package org.example.kbsystemproject.base.security.vertify;

import com.alibaba.fastjson2.JSON;
import io.lettuce.core.json.JsonObject;
import org.example.kbsystemproject.base.response.ResponseBuilder;
import org.example.kbsystemproject.base.response.ResponseCode;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public class UserAuthenticationEntryPoint implements ServerAuthenticationEntryPoint {
    @Override
    public Mono<Void> commence(ServerWebExchange exchange, AuthenticationException ex) {
        ServerHttpResponse response = exchange.getResponse();
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        response.getHeaders().set("Content-Type", "application/json;charset=UTF-8");
        return response.writeWith(
                Mono.just(
                        response.bufferFactory().wrap(JSON.toJSONBytes(
                                ResponseBuilder.error(ResponseCode.UNAUTHORIZED)
                        ))
                )
        );
    }
}
