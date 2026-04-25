package org.example.kbsystemproject.ailearning.application.profile;

import org.example.kbsystemproject.ailearning.domain.profile.LearningProfileContext;
import org.example.kbsystemproject.ailearning.domain.profile.LearningProfileRecord;
import org.example.kbsystemproject.ailearning.domain.profile.LearningSessionPersonalizationRecord;
import org.example.kbsystemproject.ailearning.domain.session.LearningSessionRecord;
import org.example.kbsystemproject.ailearning.infrastructure.persistence.profile.LearningProfileStore;
import org.example.kbsystemproject.ailearning.infrastructure.persistence.profile.LearningSessionPersonalizationStore;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.List;

@Service
public class ProfileContextService {

    private final LearningProfileStore learningProfileStore;
    private final LearningSessionPersonalizationStore learningSessionPersonalizationStore;

    public ProfileContextService(LearningProfileStore learningProfileStore,
                                 LearningSessionPersonalizationStore learningSessionPersonalizationStore) {
        this.learningProfileStore = learningProfileStore;
        this.learningSessionPersonalizationStore = learningSessionPersonalizationStore;
    }

    public Mono<LearningProfileContext> loadContext(LearningSessionRecord session) {
        if (session == null || session.userId() == null || session.userId().isBlank()) {
            return Mono.just(LearningProfileContext.EMPTY);
        }
        String subject = resolveSubject(session.subject());
        Mono<LearningSessionPersonalizationRecord> personalizationMono = learningSessionPersonalizationStore.findByConversationId(session.conversationId())
                .defaultIfEmpty(new LearningSessionPersonalizationRecord(session.conversationId(), session.userId(), null, List.of(), null));
        Mono<LearningProfileRecord> profileMono = subject == null
                ? Mono.just(new LearningProfileRecord(null, session.userId(), null, null, null, null, List.of(), null))
                : learningProfileStore.findByUserIdAndSubject(session.userId(), subject)
                .defaultIfEmpty(new LearningProfileRecord(null, session.userId(), subject, null, null, null, List.of(), null));

        return Mono.zip(profileMono, personalizationMono)
                .map(tuple -> new LearningProfileContext(
                        tuple.getT1().learningGoal(),
                        tuple.getT1().preferredStyle(),
                        tuple.getT1().preferredLanguage(),
                        tuple.getT1().weakPoints(),
                        tuple.getT2().currentTopic(),
                        tuple.getT2().recentTopics()
                ));
    }

    public String resolveSubject(String subject) {
        if (subject == null || subject.isBlank()) {
            return null;
        }
        return subject.trim();
    }
}

