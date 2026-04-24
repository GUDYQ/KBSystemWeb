package org.example.kbsystemproject.ailearning.application.service;

import org.example.kbsystemproject.ailearning.domain.profile.LearningProfileRecord;
import org.example.kbsystemproject.ailearning.domain.profile.LearningSessionPersonalizationRecord;
import org.example.kbsystemproject.ailearning.interfaces.http.request.LearningProfileUpdateRequest;
import org.example.kbsystemproject.ailearning.interfaces.http.response.LearningProfileResponse;
import org.example.kbsystemproject.ailearning.infrastructure.persistence.profile.LearningProfileStore;
import org.example.kbsystemproject.ailearning.infrastructure.persistence.profile.LearningSessionPersonalizationStore;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.List;

@Service
public class LearningProfileApplicationService {

    private final LearningProfileStore learningProfileStore;
    private final LearningSessionPersonalizationStore learningSessionPersonalizationStore;
    private final ProfileContextService profileContextService;

    public LearningProfileApplicationService(LearningProfileStore learningProfileStore,
                                             LearningSessionPersonalizationStore learningSessionPersonalizationStore,
                                             ProfileContextService profileContextService) {
        this.learningProfileStore = learningProfileStore;
        this.learningSessionPersonalizationStore = learningSessionPersonalizationStore;
        this.profileContextService = profileContextService;
    }

    public Mono<LearningProfileResponse> getProfile(String userId, String subject, String conversationId) {
        String resolvedSubject = profileContextService.resolveSubject(subject);
        Mono<LearningProfileRecord> profileMono = learningProfileStore.findByUserIdAndSubject(userId, resolvedSubject)
                .defaultIfEmpty(new LearningProfileRecord(null, userId, resolvedSubject, null, null, null, List.of(), null));
        Mono<LearningSessionPersonalizationRecord> personalizationMono = conversationId == null || conversationId.isBlank()
                ? Mono.just(new LearningSessionPersonalizationRecord(null, userId, null, List.of(), null))
                : learningSessionPersonalizationStore.findByConversationId(conversationId)
                .defaultIfEmpty(new LearningSessionPersonalizationRecord(conversationId, userId, null, List.of(), null));

        return Mono.zip(profileMono, personalizationMono)
                .map(tuple -> new LearningProfileResponse(
                        tuple.getT1().userId(),
                        tuple.getT1().subject(),
                        tuple.getT1().learningGoal(),
                        tuple.getT1().preferredStyle(),
                        tuple.getT1().preferredLanguage(),
                        tuple.getT1().weakPoints(),
                        tuple.getT2().currentTopic(),
                        tuple.getT2().recentTopics()
                ));
    }

    public Mono<LearningProfileResponse> updateProfile(LearningProfileUpdateRequest request) {
        String resolvedSubject = profileContextService.resolveSubject(request.subject());
        return learningProfileStore.upsert(
                        request.userId(),
                        resolvedSubject,
                        request.learningGoal(),
                        request.preferredStyle(),
                        request.preferredLanguage(),
                        List.of()
                )
                .then(getProfile(request.userId(), resolvedSubject, null));
    }
}
