package org.example.kbsystemproject.ailearning.application.session;

record MemoryProcessingPlan(boolean fullCompression,
                            boolean longTermCollection,
                            int currentTurnTokenEstimate,
                            int currentTurnToolCount) {
}
