package com.junaid.review.core;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Changes to one file in a pull request.
 *
 * @param path       path in the new version of the repo
 * @param addedLines lines added by the PR, with their line numbers in the new file. Only these can get review comments.
 * @param patch      this file's slice of the raw diff, used when asking the LLM for a review
 */
public record FileDiff(String path, boolean deleted, boolean binary, List<AddedLine> addedLines, int removedCount, String patch) {

    public record AddedLine(int number, String content) {}

    public FileDiff {
        addedLines = List.copyOf(addedLines);
    }

    public Set<Integer> addedLineNumbers() {
        return addedLines.stream().map(AddedLine::number).collect(Collectors.toSet());
    }

    public boolean isJava() {
        return path.endsWith(".java");
    }

    /** Lock files, images, generated and vendored code aren't worth reviewing. */
    public boolean isReviewable() {
        if (deleted || binary || addedLines.isEmpty()) {
            return false;
        }
        String p = path.toLowerCase(Locale.ROOT);
        return !(p.endsWith(".lock") || p.endsWith("package-lock.json") || p.endsWith(".min.js") || p.endsWith(".svg")
                || p.endsWith(".png") || p.endsWith(".jpg") || p.endsWith(".gif") || p.contains("/generated/")
                || p.startsWith("vendor/") || p.contains("/node_modules/"));
    }
}
