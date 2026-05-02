package org.example.kbsystemproject.ailearning.interfaces.http;

import jakarta.validation.Valid;
import org.example.kbsystemproject.ailearning.application.chat.AiLearningApplicationService;
import org.example.kbsystemproject.ailearning.application.chat.LearningChatCommand;
import org.example.kbsystemproject.ailearning.application.profile.LearningProfileApplicationService;
import org.example.kbsystemproject.ailearning.interfaces.http.request.ChatRequest;
import org.example.kbsystemproject.ailearning.interfaces.http.request.LearningProfileUpdateRequest;
import org.example.kbsystemproject.ailearning.interfaces.http.response.ChatChunkResponse;
import org.example.kbsystemproject.ailearning.interfaces.http.response.ChatTaskResponse;
import org.example.kbsystemproject.ailearning.interfaces.http.response.LearningProfileResponse;
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
    private final LearningProfileApplicationService learningProfileApplicationService;

    public AiLearningController(AiLearningApplicationService aiLearningApplicationService,
                                LearningProfileApplicationService learningProfileApplicationService) {
        this.aiLearningApplicationService = aiLearningApplicationService;
        this.learningProfileApplicationService = learningProfileApplicationService;
    }

    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ResponseVO<ChatChunkResponse>> chat(@Valid @RequestBody ChatRequest request) {
        LearningChatCommand command = toCommand(request);
        return streamChat(command.conversationId(), command.requestId(), aiLearningApplicationService.chat(command));
    }

    @PostMapping(value = "/chat/start", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseVO<ChatTaskResponse>> startChatBackground(@Valid @RequestBody ChatRequest request) {
        LearningChatCommand command = toCommand(request);
        return aiLearningApplicationService.startChat(command)
                .thenReturn(ResponseBuilder.success(new ChatTaskResponse(command.conversationId(), command.requestId(), "PROCESSING")));
    }

    @PostMapping(value = "/chat/start", params = "stream=true", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ResponseVO<ChatChunkResponse>> startChatStream(@Valid @RequestBody ChatRequest request) {
        LearningChatCommand command = toCommand(request);
        return streamChat(command.conversationId(), command.requestId(), aiLearningApplicationService.startChatAndStream(command));
    }

    @GetMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ResponseVO<ChatChunkResponse>> getChatStream(@RequestParam String conversationId) {
        return streamChat(conversationId, null, aiLearningApplicationService.getSessionLiveStream(conversationId));
    }

    @PostMapping("/chat/stop")
    public Mono<ResponseVO<ChatTaskResponse>> stopGeneration(@RequestParam String conversationId) {
        return aiLearningApplicationService.stopChat(conversationId)
                .thenReturn(ResponseBuilder.success(new ChatTaskResponse(conversationId, null, "STOPPED")));
    }

    @GetMapping("/profile")
    public Mono<ResponseVO<LearningProfileResponse>> getProfile(@RequestParam String userId,
                                                                @RequestParam(required = false) String subject,
                                                                @RequestParam(required = false) String conversationId) {
        return learningProfileApplicationService.getProfile(userId, subject, conversationId)
                .map(ResponseBuilder::success);
    }

    @PostMapping("/profile")
    public Mono<ResponseVO<LearningProfileResponse>> updateProfile(@Valid @RequestBody LearningProfileUpdateRequest request) {
        return learningProfileApplicationService.updateProfile(request)
                .map(ResponseBuilder::success);
    }

    private Flux<ResponseVO<ChatChunkResponse>> streamChat(String conversationId,
                                                           String requestId,
                                                           Flux<String> contentStream) {
        Flux<ResponseVO<ChatChunkResponse>> statusStream = Mono.just(
                ResponseBuilder.success(ChatChunkResponse.status(conversationId, requestId, "PROCESSING"))
        ).flux();

        Flux<ResponseVO<ChatChunkResponse>> tokenStream = contentStream
                .filter(content -> content != null && !content.isBlank())
                .map(content -> ResponseBuilder.success(ChatChunkResponse.token(conversationId, requestId, content)));

        Flux<ResponseVO<ChatChunkResponse>> finishStream = Mono.just(
                ResponseBuilder.success(ChatChunkResponse.finish(conversationId, requestId, "回答完成"))
        ).flux();

        return Flux.concat(statusStream, tokenStream, finishStream)
                .onErrorResume(error -> Flux.just(
                        ResponseBuilder.success(ChatChunkResponse.error(
                                conversationId,
                                requestId,
                                resolveErrorMessage(error)
                        )),
                        ResponseBuilder.success(ChatChunkResponse.finish(conversationId, requestId, "回答终止"))
                ));
    }

    private String resolveErrorMessage(Throwable error) {
        if (error == null || error.getMessage() == null || error.getMessage().isBlank()) {
            return "学习问答处理失败";
        }
        return error.getMessage();
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
                request.conversationMode(),
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

