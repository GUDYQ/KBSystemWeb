package org.example.kbsystemproject.controller;

import org.example.kbsystemproject.base.response.ResponseBuilder;
import org.example.kbsystemproject.base.response.ResponseVO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/test")
public class TestController {
    @GetMapping("/hello")
    public Mono<ResponseVO<String>> hello() {
        return Mono.just(ResponseBuilder.success("hello"));
    }
}
