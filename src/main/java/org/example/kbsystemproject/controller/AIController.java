package org.example.kbsystemproject.controller;

import org.example.kbsystemproject.base.response.ResponseBuilder;
import org.example.kbsystemproject.base.response.ResponseVO;
import org.example.kbsystemproject.service.AgentService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/api/test/ai")
public class AIController {

    @Autowired
    private AgentService agentService;

    @GetMapping(value = "/simple-react", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ResponseVO<String>> simpleReact(@RequestParam String prompt) {
        return agentService.chatWithVectorStore(prompt)
                .map(ResponseBuilder::success);
    }
}
