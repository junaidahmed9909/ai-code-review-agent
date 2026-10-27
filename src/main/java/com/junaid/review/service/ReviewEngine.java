package com.junaid.review.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.junaid.review.config.ReviewProperties;
import com.junaid.review.core.AiReviewPrompts;
import com.junaid.review.core.DiffParser;
import com.junaid.review.core.FileDiff;
import com.junaid.review.core.Finding;
import com.junaid.review.core.ReviewComposer;
import com.junaid.review.core.RuleChecker;
import com.junaid.review.llm.LlmClient;
import com.junaid.review.llm.LlmException;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Turns a unified diff into a finished review: rule checks, an optional AI pass, then composition. */
@Service
public class ReviewEngine {

    private static final Logger log = LoggerFactory.getLogger(ReviewEngine.class);

    public record ReviewResult(List<Finding> comments, String summaryMarkdown, int filesReviewed, boolean aiUsed,
                               int droppedInvalidAi, int droppedForLimit, long durationMs, List<String> warnings) {}

    private final DiffParser parser = new DiffParser();
    private final RuleChecker rules = new RuleChecker();
    private final ReviewComposer composer;
    private final LlmClient llm;
    private final ReviewProperties props;
    private final ObjectMapper json;
    private final ReviewStats stats;

    public ReviewEngine(LlmClient llm, ReviewProperties props, ObjectMapper json, ReviewStats stats) {
        this.llm = llm;
        this.props = props;
        this.json = json;
        this.stats = stats;
        this.composer = new ReviewComposer(props.maxComments());
    }

    public ReviewResult review(String diff) {
        long start = System.nanoTime();
        List<FileDiff> files = parser.parse(diff);
        if (files.isEmpty()) {
            throw new IllegalArgumentException("That doesn't look like a unified diff (no 'diff --git' file headers found)");
        }

        List<Finding> ruleFindings = new ArrayList<>();
        for (FileDiff f : files) {
            ruleFindings.addAll(rules.check(f));
        }

        List<String> warnings = new ArrayList<>();
        List<Finding> aiFindings = new ArrayList<>();
        boolean aiUsed = llm.isAvailable();
        if (aiUsed) {
            for (FileDiff f : AiReviewPrompts.filesForAi(files, props.maxAiFiles())) {
                try {
                    String reply = llm.complete(AiReviewPrompts.SYSTEM, AiReviewPrompts.userPrompt(f, props.maxAiCharsPerFile()));
                    aiFindings.addAll(parseAiFindings(reply, f.path()));
                } catch (LlmException e) {
                    log.warn("AI review skipped for {}: {}", f.path(), e.getMessage());
                    warnings.add("AI review skipped for " + f.path() + ": " + e.getMessage());
                }
            }
        }

        ReviewComposer.Review review = composer.compose(files, ruleFindings, aiFindings);
        long durationMs = (System.nanoTime() - start) / 1_000_000;
        int filesReviewed = (int) files.stream().filter(FileDiff::isReviewable).count();
        stats.record(review.comments(), filesReviewed, durationMs, review.droppedInvalidAi());
        return new ReviewResult(review.comments(), review.summaryMarkdown(), filesReviewed, aiUsed,
                review.droppedInvalidAi(), review.droppedForLimit(), durationMs, warnings);
    }

    /** Reads the model's JSON array. Malformed items are skipped; the path defaults to the file under review. */
    List<Finding> parseAiFindings(String reply, String defaultPath) {
        List<Finding> out = new ArrayList<>();
        JsonNode root;
        try {
            root = json.readTree(AiReviewPrompts.stripCodeFence(reply));
        } catch (Exception e) {
            log.warn("AI reply for {} was not valid JSON; ignoring it", defaultPath);
            return out;
        }
        if (root == null || !root.isArray()) {
            return out;
        }
        for (JsonNode item : root) {
            String message = item.path("message").asText("").trim();
            int line = item.path("line").asInt(0);
            if (message.isEmpty() || line < 1) {
                continue;
            }
            String path = item.path("path").asText(defaultPath);
            out.add(new Finding("AI_REVIEW", AiReviewPrompts.severityOf(item.path("severity").asText(null)),
                    path.isBlank() ? defaultPath : path, line, message, item.path("suggestion").asText(""), Finding.Source.AI));
        }
        return out;
    }
}
