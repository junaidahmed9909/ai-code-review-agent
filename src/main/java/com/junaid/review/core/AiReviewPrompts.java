package com.junaid.review.core;

import java.util.List;
import java.util.Locale;

/**
 * Prompts for the AI pass. Each added line is shown with its real line number so the model can point at it
 * precisely; the composer then rejects anything that doesn't land on an added line.
 */
public final class AiReviewPrompts {

    public static final String SYSTEM = """
            You are a senior Java reviewer. Review ONLY the added lines (marked with +) in the diff you are given.
            Focus on real problems: bugs, null handling, concurrency, resource leaks, security, transaction boundaries,
            money/precision handling, and missing error handling. Skip style nitpicks and anything a linter would catch.
            Reply with a JSON array and nothing else. Each item must have:
              "path" (string, exactly as given), "line" (the number shown before the +),
              "severity" ("HIGH", "MEDIUM" or "LOW"), "message" (one or two sentences), "suggestion" (short fix).
            If there is nothing worth flagging, reply with [].
            Return at most 8 items.""";

    private AiReviewPrompts() {}

    /** Renders one file's added lines with line numbers, trimmed to {@code maxChars}. */
    public static String userPrompt(FileDiff file, int maxChars) {
        StringBuilder sb = new StringBuilder("File: ").append(file.path()).append("\n\nAdded lines:\n");
        for (FileDiff.AddedLine line : file.addedLines()) {
            String row = String.format(Locale.ROOT, "%5d +%s%n", line.number(), line.content());
            if (sb.length() + row.length() > maxChars) {
                sb.append("... (truncated)\n");
                break;
            }
            sb.append(row);
        }
        return sb.toString();
    }

    /** Parses a severity leniently, defaulting to MEDIUM for anything unexpected. */
    public static Finding.Severity severityOf(String raw) {
        if (raw == null) {
            return Finding.Severity.MEDIUM;
        }
        return switch (raw.trim().toUpperCase(Locale.ROOT)) {
            case "HIGH", "CRITICAL", "BLOCKER" -> Finding.Severity.HIGH;
            case "LOW", "INFO", "MINOR" -> Finding.Severity.LOW;
            default -> Finding.Severity.MEDIUM;
        };
    }

    /** Strips a ```json fence if the model added one. */
    public static String stripCodeFence(String raw) {
        String s = raw.trim();
        if (s.startsWith("```")) {
            int firstNewline = s.indexOf('\n');
            int lastFence = s.lastIndexOf("```");
            if (firstNewline > 0 && lastFence > firstNewline) {
                s = s.substring(firstNewline + 1, lastFence).trim();
            }
        }
        return s;
    }

    /** Files worth sending to the model: reviewable code, biggest changes first, capped. */
    public static List<FileDiff> filesForAi(List<FileDiff> files, int maxFiles) {
        return files.stream()
                .filter(FileDiff::isReviewable)
                .filter(f -> f.isJava() || f.path().endsWith(".kt") || f.path().endsWith(".sql")
                        || f.path().endsWith(".yml") || f.path().endsWith(".yaml") || f.path().endsWith(".properties"))
                .sorted((a, b) -> Integer.compare(b.addedLines().size(), a.addedLines().size()))
                .limit(maxFiles)
                .toList();
    }
}
