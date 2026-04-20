package org.example.kbsystemproject.ailearning.domain.session;

public record SessionRequestDecision(
        SessionRequestDecisionType type,
        SessionRequestRecord record
) {
    public static SessionRequestDecision acquired(SessionRequestRecord record) {
        return new SessionRequestDecision(SessionRequestDecisionType.ACQUIRED, record);
    }

    public static SessionRequestDecision completed(SessionRequestRecord record) {
        return new SessionRequestDecision(SessionRequestDecisionType.COMPLETED, record);
    }

    public static SessionRequestDecision processing(SessionRequestRecord record) {
        return new SessionRequestDecision(SessionRequestDecisionType.PROCESSING, record);
    }

    public static SessionRequestDecision conversationBusy() {
        return new SessionRequestDecision(SessionRequestDecisionType.CONVERSATION_BUSY, null);
    }
}
