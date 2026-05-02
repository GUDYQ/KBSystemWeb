package org.example.kbsystemproject.ailearning.application.session;

import org.example.kbsystemproject.ailearning.domain.session.MemoryCandidate;
import org.example.kbsystemproject.ailearning.domain.session.SessionWhiteboard;

import java.util.List;

record MemoryCollectionState(TurnMemoryContext context,
                             SessionWhiteboard whiteboard,
                             List<MemoryCandidate> candidates,
                             MemoryProcessingPlan plan) {
}
