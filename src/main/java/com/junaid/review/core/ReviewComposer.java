package com.junaid.review.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Merges rule and AI findings into one review: removes duplicates, drops AI comments on lines the PR didn't add,
 * orders by severity and caps the count so a big PR doesn't get buried in noise.
 */
public final class ReviewComposer {

    public record Review(List<Finding> comments, int droppedForLimit, int droppedInvalidAi, String summaryMarkdown) {
        public Review {
            comments = List.copyOf(comments);
        }
    }

    private final int maxComments;

    public ReviewComposer(int maxComments) {
        if (maxComments < 1) {
            throw new IllegalArgumentException("maxComments must be at least 1");
        }
        this.maxComments = maxComments;
    }

    public Review compose(List<FileDiff> files, List<Finding> ruleFindings, List<Finding> aiFindings) {
        Map<String, Set<Integer>> validLines = new LinkedHashMap<>();
        for (FileDiff f : files) {
            validLines.put(f.path(), f.addedLineNumbers());
        }

        List<Finding> all = new ArrayList<>(ruleFindings);
        int invalidAi = 0;
        Set<String> ruleLines = new HashSet<>();
        for (Finding f : ruleFindings) {
            ruleLines.add(f.path() + ":" + f.line());
        }
        for (Finding f : aiFindings) {
            Set<Integer> lines = validLines.get(f.path());
            if (lines == null || !lines.contains(f.line())) {
                invalidAi++; // model pointed at a file or line that isn't part of this change
            } else if (!ruleLines.contains(f.path() + ":" + f.line())) {
                all.add(f); // a rule already covers this line; prefer the deterministic comment
            }
        }

        // Exact duplicates (same rule, same place) collapse to one.
        Map<String, Finding> unique = new LinkedHashMap<>();
        for (Finding f : all) {
            unique.putIfAbsent(f.ruleId() + "|" + f.path() + "|" + f.line(), f);
        }
        List<Finding> sorted = new ArrayList<>(unique.values());
        sorted.sort(Comparator.comparing(Finding::severity)
                .thenComparing(Finding::path)
                .thenComparingInt(Finding::line));

        int dropped = Math.max(0, sorted.size() - maxComments);
        List<Finding> kept = sorted.size() > maxComments ? sorted.subList(0, maxComments) : sorted;
        long reviewable = files.stream().filter(FileDiff::isReviewable).count();
        return new Review(kept, dropped, invalidAi, summary(kept, dropped, reviewable));
    }

    private static String summary(List<Finding> kept, int dropped, long filesReviewed) {
        Map<Finding.Severity, Integer> counts = new EnumMap<>(Finding.Severity.class);
        for (Finding f : kept) {
            counts.merge(f.severity(), 1, Integer::sum);
        }
        StringBuilder sb = new StringBuilder("## 🤖 Automated review\n\n");
        if (kept.isEmpty()) {
            sb.append("Looked at ").append(filesReviewed).append(filesReviewed == 1 ? " file" : " files")
                    .append(" and found nothing to flag. Nice work.\n");
        } else {
            sb.append("Looked at ").append(filesReviewed).append(filesReviewed == 1 ? " file" : " files").append(" and left ")
                    .append(kept.size()).append(kept.size() == 1 ? " comment" : " comments").append(": ")
                    .append(counts.getOrDefault(Finding.Severity.HIGH, 0)).append(" high, ")
                    .append(counts.getOrDefault(Finding.Severity.MEDIUM, 0)).append(" medium, ")
                    .append(counts.getOrDefault(Finding.Severity.LOW, 0)).append(" low.\n");
            if (counts.getOrDefault(Finding.Severity.HIGH, 0) > 0) {
                sb.append("\nPlease look at the high-severity items before merging.\n");
            }
        }
        if (dropped > 0) {
            sb.append("\n").append(dropped).append(" lower-priority comments were left out to keep this readable.\n");
        }
        sb.append("\n<sub>React with 👍 or 👎 on individual comments so the rules can be tuned.</sub>\n");
        return sb.toString();
    }
}
