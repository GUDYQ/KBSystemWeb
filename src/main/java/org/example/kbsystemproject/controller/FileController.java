package org.example.kbsystemproject.controller;

import lombok.Getter;
import org.example.kbsystemproject.base.response.ResponseBuilder;
import org.example.kbsystemproject.base.response.ResponseVO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/upload/")
public class FileController {
    @GetMapping("test")
    public Mono<ResponseVO<String>> testFile() {
        return Mono.just("hello")
                .map(ResponseBuilder::success);
    }
}