package org.example.kbsystemproject.base.response;

import org.example.kbsystemproject.ailearning.application.service.SessionRequestConflictException;
import org.example.kbsystemproject.base.exception.UseException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import reactor.core.publisher.Mono;

@RestControllerAdvice
public class GlobExceptionHandler {

    @ExceptionHandler(UseException.class)
    public Mono<ResponseVO<String>> handlerLoginException() {
        return Mono.just(ResponseBuilder.error(402, "登录失败"));
    }

    @ExceptionHandler(SessionRequestConflictException.class)
    public Mono<ResponseVO<String>> handleSessionRequestConflict(SessionRequestConflictException error) {
        return Mono.just(ResponseBuilder.error(409, error.getMessage()));
    }
}
