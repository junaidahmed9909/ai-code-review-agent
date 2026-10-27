package com.junaid.review.core;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses a unified diff (what GitHub returns for a pull request with the diff media type).
 * The important job is tracking line numbers in the new file, because GitHub review comments
 * must point at a line that's actually part of the diff.
 */
public final class DiffParser {

    private static final Pattern FILE_HEADER = Pattern.compile("^diff --git a/(.+?) b/(.+)$");
    private static final Pattern HUNK = Pattern.compile("^@@ -\\d+(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@.*$");

    public List<FileDiff> parse(String diff) {
        List<FileDiff> files = new ArrayList<>();
        if (diff == null || diff.isBlank()) {
            return files;
        }
        Builder current = null;
        int newLine = 0;
        boolean inHunk = false;

        for (String line : diff.replace("\r\n", "\n").split("\n", -1)) {
            Matcher header = FILE_HEADER.matcher(line);
            if (header.matches()) {
                if (current != null) {
                    files.add(current.build());
                }
                current = new Builder(header.group(2));
                current.patch.append(line).append('\n');
                inHunk = false;
                continue;
            }
            if (current == null) {
                continue; // preamble before the first file, e.g. an email header
            }
            current.patch.append(line).append('\n');

            Matcher hunk = HUNK.matcher(line);
            if (hunk.matches()) {
                newLine = Integer.parseInt(hunk.group(1));
                inHunk = true;
                continue;
            }
            if (!inHunk) {
                if (line.startsWith("+++ ")) {
                    String target = line.substring(4).trim();
                    if (target.equals("/dev/null")) {
                        current.deleted = true;
                    } else if (target.startsWith("b/")) {
                        current.path = target.substring(2);
                    }
                } else if (line.startsWith("Binary files") || line.startsWith("GIT binary patch")) {
                    current.binary = true;
                } else if (line.startsWith("deleted file mode")) {
                    current.deleted = true;
                }
                continue;
            }
            if (line.startsWith("+")) {
                current.added.add(new FileDiff.AddedLine(newLine, line.substring(1)));
                newLine++;
            } else if (line.startsWith("-")) {
                current.removed++;
            } else if (line.startsWith(" ")) {
                newLine++;
            } else if (line.isEmpty()) {
                // Some tools strip the leading space from blank context lines; treat as context.
                newLine++;
            }
            // Lines starting with '\' ("No newline at end of file") don't move the counter.
        }
        if (current != null) {
            files.add(current.build());
        }
        return files;
    }

    private static final class Builder {
        private String path;
        private boolean deleted;
        private boolean binary;
        private final List<FileDiff.AddedLine> added = new ArrayList<>();
        private int removed;
        private final StringBuilder patch = new StringBuilder();

        Builder(String path) {
            this.path = path;
        }

        FileDiff build() {
            return new FileDiff(path, deleted, binary, added, removed, patch.toString());
        }
    }
}
