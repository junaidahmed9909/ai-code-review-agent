package com.junaid.review.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.junaid.review.config.ReviewProperties;
import com.junaid.review.core.Finding;
import com.junaid.review.llm.LlmClient;
import com.junaid.review.llm.LlmException;
import com.junaid.review.service.ReviewEngine;
import com.junaid.review.service.ReviewStats;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

/** Exercises the AI pass with a fake model, so we can check validation without calling a real API. */
class ReviewEngineAiTest {

    private static final String DIFF = """
            diff --git a/src/Svc.java b/src/Svc.java
            --- a/src/Svc.java
            +++ b/src/Svc.java
            @@ -1,2 +1,4 @@
             class Svc {
            +  Map<String, Integer> cache = new HashMap<>();
            +  int get(String k) { return cache.get(k); }
             }
            """;

    private static ReviewEngine engineReplying(String reply) {
        LlmClient fake = new LlmClient() {
            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public String complete(String system, String user) {
                if (reply == null) {
                    throw new LlmException("boom");
                }
                return reply;
            }
        };
        return new ReviewEngine(fake, new ReviewProperties(20, 5, 10_000), new ObjectMapper(), new ReviewStats(new SimpleMeterRegistry()));
    }

    @Test
    void keepsValidAiFindingsAndDropsOffDiffOnes() {
        String reply = """
                ```json
                [
                  {"path":"src/Svc.java","line":3,"severity":"high","message":"Unboxing a missing key throws NullPointerException.","suggestion":"Use getOrDefault."},
                  {"path":"src/Svc.java","line":40,"severity":"low","message":"Made-up line.","suggestion":""},
                  {"path":"src/Svc.java","line":2,"severity":"medium","message":""}
                ]
                ```""";
        ReviewEngine.ReviewResult result = engineReplying(reply).review(DIFF);

        assertTrue(result.aiUsed());
        assertEquals(1, result.comments().size());
        Finding f = result.comments().get(0);
        assertEquals(Finding.Source.AI, f.source());
        assertEquals(Finding.Severity.HIGH, f.severity());
        assertEquals(3, f.line());
        assertEquals(1, result.droppedInvalidAi());
    }

    @Test
    void garbageReplyIsIgnored() {
        assertTrue(engineReplying("I think this looks fine!").review(DIFF).comments().isEmpty());
    }

    @Test
    void modelOutageBecomesAWarningNotAFailure() {
        ReviewEngine.ReviewResult result = engineReplying(null).review(DIFF);
        assertEquals(1, result.warnings().size());
        assertTrue(result.comments().isEmpty());
    }
}
