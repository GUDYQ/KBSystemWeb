package org.example.kbsystemproject;

import org.example.kbsystemproject.ailearning.application.service.AiLearningApplicationService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Arrays;
import java.util.List;

@SpringBootTest
public class RecallRateTest {

    @Autowired
    private AiLearningApplicationService aiLearningApplicationService;

    @Test
    public void testRecallRate() {
        // Example test cases
        List<TestCase> testCases = Arrays.asList(
                new TestCase("如果单身太久感到焦虑怎么办？", "恋爱常见问题和回答 - 单身篇.md"),
                new TestCase("结婚后财政大权应该交给谁？", "恋爱常见问题和回答 - 已婚篇.md"),
                new TestCase("异地恋怎么维持感情？", "恋爱常见问题和回答 - 恋爱篇.md")
        );

        int hits = 0;
        int topK = 10;
        int total = testCases.size();

        for (int i = 0; i < total; i++) {
            TestCase testCase = testCases.get(i);
            String question = testCase.question;
            String expectedFile = testCase.expectedFile;

            // Using blockLast to gather all results from Flux correctly for a test
            List<Document> results = aiLearningApplicationService.searchSimilarity(question).collectList().block();

            boolean hit = false;
            if (results != null) {
                // Ensure we only check up to topK results
                int limit = Math.min(topK, results.size());
                for (int j = 0; j < limit; j++) {
                    Document doc = results.get(j);
                    // Checking document metadata depending on your loader configurations
                    // commonly named "file_name" or "source"
                    String fileName = (String) doc.getMetadata().getOrDefault("file_name", doc.getMetadata().get("source"));
                    if (fileName != null && fileName.contains(expectedFile)) {
                        hit = true;
                        break;
                    }
                }
            }

            if (hit) {
                hits++;
                System.out.printf("[%d/%d] HIT! Question: %s%n", i + 1, total, question);
            } else {
                System.out.printf("[%d/%d] MISS. Question: %s | Expected: %s%n", i + 1, total, question, expectedFile);
            }
        }

        double recallRate = (double) hits / total;
        System.out.println("--- Evaluation Finished ---");
        System.out.println("Total Test Cases: " + total);
        System.out.println("Hits @ " + topK + " : " + hits);
        System.out.printf("Recall Rate @ %d : %.2f%%%n", topK, recallRate * 100);
    }

    private static class TestCase {
        String question;
        String expectedFile;

        TestCase(String question, String expectedFile) {
            this.question = question;
            this.expectedFile = expectedFile;
        }
    }
}
