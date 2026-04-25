package org.example.kbsystemproject.ailearning.application.chat;

import org.example.kbsystemproject.ailearning.domain.intent.ExecutionMode;
import org.example.kbsystemproject.ailearning.domain.intent.IntentDecision;
import org.example.kbsystemproject.ailearning.domain.intent.IntentSource;
import org.example.kbsystemproject.ailearning.domain.intent.IntentType;
import org.example.kbsystemproject.ailearning.domain.session.LearningSessionType;
import org.example.kbsystemproject.ailearning.domain.session.SessionMemorySnapshot;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
public class LearningChatRoutingService {

    public Mono<IntentDecision> route(LearningChatCommand command,
                                      SessionMemorySnapshot snapshot,
                                      boolean forceDirectExecution) {
        LearningSessionType sessionType = command.sessionType();
        if (sessionType == null && snapshot != null && snapshot.session() != null) {
            sessionType = snapshot.session().sessionType();
        }
        if (sessionType == null) {
            sessionType = LearningSessionType.QA;
        }

        IntentType intentType = switch (sessionType) {
            case QA -> IntentType.GENERAL_QA;
            case EXERCISE -> IntentType.GENERATE_EXERCISE;
            case REVIEW -> IntentType.REVIEW_SUMMARY;
        };
        ExecutionMode executionMode = forceDirectExecution
                ? ExecutionMode.DIRECT
                : switch (sessionType) {
                    case QA -> ExecutionMode.DIRECT;
                    case EXERCISE, REVIEW -> ExecutionMode.AGENT;
                };

        return Mono.just(new IntentDecision(
                intentType,
                sessionType,
                executionMode,
                1.0D,
                command.sessionType() == null ? IntentSource.FALLBACK : IntentSource.USER_HINT,
                true,
                executionMode == ExecutionMode.AGENT,
                command.sessionType() == null ? "session-default-routing" : "session-type-routing"
        ));
    }
}
