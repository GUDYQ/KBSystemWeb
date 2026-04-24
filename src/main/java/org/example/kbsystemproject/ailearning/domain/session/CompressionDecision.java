package org.example.kbsystemproject.ailearning.domain.session;

public record CompressionDecision(
        CompressionAction action,
        boolean finalizeCurrentBlock,
        boolean archiveLongTerm,
        String reason
) {
}
