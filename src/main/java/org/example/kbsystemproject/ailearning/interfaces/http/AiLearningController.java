package org.example.kbsystemproject.ailearning.interfaces.http;

import jakarta.validation.Valid;
import org.example.kbsystemproject.ailearning.application.service.AiLearningApplicationService;
import org.example.kbsystemproject.ailearning.application.service.LearningChatCommand;
import org.example.kbsystemproject.ailearning.interfaces.http.request.ChatRequest;
import org.example.kbsystemproject.ailearning.interfaces.http.response.ChatChunkResponse;
import org.example.kbsystemproject.ailearning.interfaces.http.response.ChatTaskResponse;
import org.example.kbsystemproject.base.response.ResponseBuilder;
import org.example.kbsystemproject.base.response.ResponseVO;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

@RestController
@Validated
@RequestMapping("/api/ai-learning")
public class AiLearningController {

    private final AiLearningApplicationService aiLearningApplicationService;

    public AiLearningController(AiLearningApplicationService aiLearningApplicationService) {
        this.aiLearningApplicationService = aiLearningApplicationService;
    }

    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ResponseVO<ChatChunkResponse>> chat(@Valid @RequestBody ChatRequest request) {
        LearningChatCommand command = toCommand(request);
        return aiLearningApplicationService.chat(command)
                .map(content -> ChatChunkResponse.token(command.conversationId(), command.requestId(), content))
                .concatWith(Mono.just(ChatChunkResponse.finish(command.conversationId(), command.requestId(), "回答完成")))
                .map(ResponseBuilder::success);
    }

    @GetMapping(value = "/agent/simple-react", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ResponseVO<String>> simpleReact(@RequestParam String prompt) {
        return aiLearningApplicationService.chatWithAgent(prompt)
                .map(ResponseBuilder::success);
    }

    @PostMapping("/chat/start")
    public Mono<ResponseVO<ChatTaskResponse>> startChatBackground(@Valid @RequestBody ChatRequest request) {
        LearningChatCommand command = toCommand(request);
        return aiLearningApplicationService.startChat(command)
                .thenReturn(ResponseBuilder.success(new ChatTaskResponse(command.conversationId(), command.requestId(), "PROCESSING")));
    }

    @GetMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ResponseVO<ChatChunkResponse>> getChatStream(@RequestParam String conversationId) {
        return aiLearningApplicationService.getSessionLiveStream(conversationId)
                .map(content -> ChatChunkResponse.token(conversationId, null, content))
                .concatWith(Mono.just(ChatChunkResponse.finish(conversationId, null, "回答完成")))
                .map(ResponseBuilder::success);
    }

    @PostMapping("/chat/stop")
    public Mono<ResponseVO<ChatTaskResponse>> stopGeneration(@RequestParam String conversationId) {
        return aiLearningApplicationService.stopChat(conversationId)
                .thenReturn(ResponseBuilder.success(new ChatTaskResponse(conversationId, null, "STOPPED")));
    }

    private LearningChatCommand toCommand(ChatRequest request) {
        String conversationId = resolveConversationId(request.conversationId());
        String requestId = resolveRequestId(request.requestId());
        return new LearningChatCommand(
                conversationId,
                requestId,
                request.userId(),
                request.subject(),
                request.sessionType(),
                request.learningGoal(),
                request.currentTopic(),
                request.prompt()
        );
    }

    private String resolveConversationId(String conversationId) {
        if (conversationId != null && !conversationId.isBlank()) {
            return conversationId;
        }
        return "conv_" + UUID.randomUUID().toString().replace("-", "");
    }

    private String resolveRequestId(String requestId) {
        if (requestId != null && !requestId.isBlank()) {
            return requestId;
        }
        return "req_" + UUID.randomUUID().toString().replace("-", "");
    }
}
