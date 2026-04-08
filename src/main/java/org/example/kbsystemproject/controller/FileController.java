package org.example.kbsystemproject.controller;

import lombok.Getter;
import org.example.kbsystemproject.base.response.ResponseBuilder;
import org.example.kbsystemproject.base.response.ResponseVO;
import org.example.kbsystemproject.entity.UserToken;
import org.example.kbsystemproject.service.FileService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/upload/")
public class FileController {

    @Autowired
    private FileService fileService;

    @GetMapping("test")
    public Mono<ResponseVO<String>> testFile() {

        return fileService.loadAllTestDocuments()
                .then(Mono.just("hello").map(ResponseBuilder::success));
    }

//    @PostMapping("file/test")
//    public Mono<ResponseVO<Boolean>> kafkaTest() {
//        UserToken userToken = UserToken.builder()
//                .accessToken("11").refreshToken("234").build();
//        return fileService.product("test-key", "file-uploaded", "file-service", userToken)
//                .thenReturn(ResponseBuilder.success(Boolean.TRUE));
//    }
}