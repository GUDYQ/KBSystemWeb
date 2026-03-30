package org.example.kbsystemproject.base.response;

import org.example.kbsystemproject.base.exception.UseException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import reactor.core.publisher.Mono;

@RestControllerAdvice
public class GlobExceptionHandler {

    @ExceptionHandler(UseException.class)
    public Mono<ResponseVO<String>> handlerLoginException() {
        return Mono.just(ResponseBuilder.error(402, "登陆失败"));
    }
}
