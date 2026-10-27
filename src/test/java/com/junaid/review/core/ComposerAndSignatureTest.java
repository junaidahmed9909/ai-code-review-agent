package com.junaid.review.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.junaid.review.core.Finding.Severity;
import com.junaid.review.core.Finding.Source;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ComposerAndSignatureTest {

    private static FileDiff file(String path, int... added) {
        List<FileDiff.AddedLine> lines = new ArrayList<>();
        for (int n : added) {
            lines.add(new FileDiff.AddedLine(n, "code();"));
        }
        return new FileDiff(path, false, false, lines, 0, "");
    }

    private static Finding finding(String rule, Severity s, String path, int line, Source source) {
        return new Finding(rule, s, path, line, "msg", "fix", source);
    }

    @Test
    void dropsAiCommentsOnLinesOutsideTheDiff() {
        List<FileDiff> files = List.of(file("A.java", 10, 11));
        ReviewComposer.Review review = new ReviewComposer(10).compose(files, List.of(),
                List.of(finding("AI_REVIEW", Severity.HIGH, "A.java", 11, Source.AI),
                        finding("AI_REVIEW", Severity.HIGH, "A.java", 99, Source.AI),
                        finding("AI_REVIEW", Severity.HIGH, "Missing.java", 1, Source.AI)));

        assertEquals(1, review.comments().size());
        assertEquals(2, review.droppedInvalidAi());
    }

    @Test
    void prefersRuleCommentWhenAiFlagsTheSameLine() {
        List<FileDiff> files = List.of(file("A.java", 5));
        ReviewComposer.Review review = new ReviewComposer(10).compose(files,
                List.of(finding("EMPTY_CATCH", Severity.HIGH, "A.java", 5, Source.RULE)),
                List.of(finding("AI_REVIEW", Severity.MEDIUM, "A.java", 5, Source.AI)));

        assertEquals(1, review.comments().size());
        assertEquals(Source.RULE, review.comments().get(0).source());
    }

    @Test
    void sortsBySeverityAndCapsTheCount() {
        List<FileDiff> files = List.of(file("A.java", 1, 2, 3));
        ReviewComposer.Review review = new ReviewComposer(2).compose(files, List.of(
                finding("LOW_ONE", Severity.LOW, "A.java", 1, Source.RULE),
                finding("HIGH_ONE", Severity.HIGH, "A.java", 3, Source.RULE),
                finding("MED_ONE", Severity.MEDIUM, "A.java", 2, Source.RULE),
                finding("HIGH_ONE", Severity.HIGH, "A.java", 3, Source.RULE)), List.of());

        assertEquals(List.of("HIGH_ONE", "MED_ONE"), review.comments().stream().map(Finding::ruleId).toList());
        assertEquals(1, review.droppedForLimit());
        assertTrue(review.summaryMarkdown().contains("1 lower-priority comments were left out"));
        assertTrue(review.summaryMarkdown().contains("1 high, 1 medium, 0 low"));
    }

    @Test
    void cleanChangeGetsAFriendlySummary() {
        ReviewComposer.Review review = new ReviewComposer(5).compose(List.of(file("A.java", 1)), List.of(), List.of());
        assertTrue(review.summaryMarkdown().contains("found nothing to flag"), review.summaryMarkdown());
    }

    @Test
    void commentBodyLabelsAiFindings() {
        assertTrue(finding("AI_REVIEW", Severity.LOW, "A.java", 1, Source.AI).toCommentBody().contains("AI-generated"));
        assertFalse(finding("X", Severity.LOW, "A.java", 1, Source.RULE).toCommentBody().contains("AI-generated"));
    }

    @Test
    void promptShowsLineNumbersAndTruncates() {
        FileDiff f = file("A.java", 7, 8);
        String prompt = AiReviewPrompts.userPrompt(f, 10_000);
        assertTrue(prompt.contains("    7 +code();"), prompt);
        assertTrue(AiReviewPrompts.userPrompt(file("A.java", 1, 2, 3, 4, 5, 6, 7, 8, 9), 60).contains("(truncated)"));
        assertEquals(Severity.HIGH, AiReviewPrompts.severityOf("critical"));
        assertEquals(Severity.MEDIUM, AiReviewPrompts.severityOf("whatever"));
    }

    @Test
    void picksBiggestCodeFilesForAi() {
        List<FileDiff> picked = AiReviewPrompts.filesForAi(List.of(
                file("small.java", 1), file("big.java", 1, 2, 3), file("notes.md", 1, 2, 3, 4)), 1);
        assertEquals("big.java", picked.get(0).path());
        assertEquals(1, picked.size());
    }

    @Test
    void verifiesGithubSignatures() {
        byte[] body = "{\"action\":\"opened\"}".getBytes(StandardCharsets.UTF_8);
        String sig = WebhookSignature.sign("s3cret", body);

        assertTrue(sig.startsWith("sha256="));
        assertEquals(71, sig.length());
        assertTrue(WebhookSignature.isValid("s3cret", body, sig));
        assertFalse(WebhookSignature.isValid("other", body, sig));
        assertFalse(WebhookSignature.isValid("s3cret", "tampered".getBytes(StandardCharsets.UTF_8), sig));
        assertFalse(WebhookSignature.isValid("s3cret", body, null));
        assertFalse(WebhookSignature.isValid("", body, sig));
    }

    @Test
    void knownHmacVector() {
        // Matches GitHub's documented example: secret "It's a Secret to Everybody", payload "Hello, World!".
        assertEquals("sha256=757107ea0eb2509fc211221cce984b8a37570b6d7586c22c46f4379c8b043e17",
                WebhookSignature.sign("It's a Secret to Everybody", "Hello, World!".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void rejectsInvalidFindings() {
        assertThrows(IllegalArgumentException.class, () -> finding("X", Severity.LOW, "A.java", 0, Source.RULE));
        assertThrows(IllegalArgumentException.class, () -> new ReviewComposer(0));
    }
}
