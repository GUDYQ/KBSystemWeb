package org.example.kbsystemproject.service.component.advisor;

import cn.hutool.db.sql.Order;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Component
public class ConversationAdvisor extends Order implements CallAdvisor, StreamAdvisor {

    private final MemoryInboundContextBuilder contextBuilder;

    public ConversationAdvisor(MemoryInboundContextBuilder contextBuilder) {
        this.contextBuilder = contextBuilder;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest chatClientRequest, CallAdvisorChain callAdvisorChain) {
        // 委托给独立的逻辑类处理入站
        ChatClientRequest inboundRequest = contextBuilder.build(chatClientRequest);
        // 放行
        return callAdvisorChain.nextCall(inboundRequest);
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest chatClientRequest, StreamAdvisorChain streamAdvisorChain) {
        // 委托给独立的逻辑类处理入站
        Mono<ChatClientRequest> inboundRequest = Mono.fromCallable(() -> contextBuilder.build(chatClientRequest))
                .subscribeOn(Schedulers.boundedElastic())
                .publishOn(Schedulers.parallel());
        // 放行
        return inboundRequest.flatMapMany(request -> streamAdvisorChain.nextStream(request));
    }

    @Override
    public String getName() {
        return "ConversationAdvisor";
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
