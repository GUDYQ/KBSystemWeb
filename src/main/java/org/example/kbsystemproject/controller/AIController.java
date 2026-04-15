package org.example.kbsystemproject.controller;

import org.example.kbsystemproject.base.ai.agent.ReActAgent;
import org.example.kbsystemproject.base.response.ResponseBuilder;
import org.example.kbsystemproject.base.response.ResponseVO;
import org.example.kbsystemproject.service.AgentService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/test/ai")
public class AIController {

    @Autowired
    private AgentService agentService;

    @GetMapping(value = "/simple-react", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ResponseVO<String>> simpleReact(@RequestParam String prompt) {
        return agentService.chatWithAgent(prompt)
                .map(ResponseBuilder::success);
    }

    @PostMapping("/chat/start")
    public Mono<ResponseVO<String>> startChatBackground(@RequestParam String sessionId, @RequestParam String prompt) {
        agentService.startAgentChatBackground(sessionId, prompt);
        return Mono.just(ResponseBuilder.success("Agent task started in background."));
    }

    @GetMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ResponseVO<String>> getChatStream(@RequestParam String sessionId) {
        return agentService.getSessionLiveStream(sessionId)
                .map(ResponseBuilder::success);
    }

    @GetMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ResponseVO<String>> chat(@RequestParam String prompt) {
        return agentService.chat(prompt)
                .map(ResponseBuilder::success);
    }

    @Qualifier("reactiveStringRedisTemplate")
    @Autowired
    private ReactiveRedisTemplate<String, String> redisTemplate;

    @PostMapping("/stop")
    public Mono<Void> stopGeneration(@RequestParam String sessionId) {
        String stopChannel = "channel:stop:" + sessionId;

        // 向特定的频道发布停止指令
        return redisTemplate.convertAndSend(stopChannel, "STOP").then();
    }

}
