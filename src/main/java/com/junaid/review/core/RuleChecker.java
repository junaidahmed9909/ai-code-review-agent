package com.junaid.review.core;

import com.junaid.review.core.Finding.Severity;
import com.junaid.review.core.Finding.Source;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Fast, deterministic checks for common Java and security problems, run on added lines only.
 * They catch the boring stuff for free so the LLM (and human reviewers) can focus on design and logic.
 */
public final class RuleChecker {

    private record LineRule(String id, Severity severity, Pattern pattern, boolean javaOnly, boolean skipComments,
                            String message, String suggestion) {}

    private static final List<LineRule> LINE_RULES = List.of(
            new LineRule("HARDCODED_SECRET", Severity.HIGH,
                    Pattern.compile("(?i)\\b(password|passwd|pwd|secret|api[_-]?key|access[_-]?token|auth[_-]?token)\\b\\s*[=:]\\s*\"[^\"\\s]{4,}\""),
                    false, false,
                    "This looks like a hard-coded credential. Anything committed to git should be treated as leaked.",
                    "Load it from configuration or a secrets manager (environment variable, AWS Secrets Manager, Vault) and rotate the exposed value."),
            new LineRule("AWS_ACCESS_KEY", Severity.HIGH, Pattern.compile("\\bAKIA[0-9A-Z]{16}\\b"), false, false,
                    "This looks like an AWS access key ID.",
                    "Remove it, rotate the key in IAM, and use an IAM role or a secrets manager instead."),
            new LineRule("SQL_CONCATENATION", Severity.HIGH,
                    Pattern.compile("(?i)\"\\s*(select|insert|update|delete)\\b[^\"]*\"\\s*\\+"), true, true,
                    "SQL built with string concatenation is open to SQL injection.",
                    "Use a PreparedStatement, JdbcTemplate placeholders or a JPA query parameter."),
            new LineRule("STRING_REFERENCE_EQUALITY", Severity.MEDIUM,
                    Pattern.compile("\"[^\"]*\"\\s*[!=]=[^=]|[^=!<>]\\s*[!=]=\\s*\"[^\"]*\""), true, true,
                    "Comparing strings with == checks object identity, not content.",
                    "Use \"literal\".equals(value) or Objects.equals(a, b)."),
            new LineRule("BIGDECIMAL_FROM_DOUBLE", Severity.MEDIUM,
                    Pattern.compile("new\\s+BigDecimal\\s*\\(\\s*-?\\d+\\.\\d+\\s*\\)"), true, true,
                    "new BigDecimal(0.1) captures the binary floating-point error (0.1000000000000000055...). That matters for money.",
                    "Use new BigDecimal(\"0.1\") or BigDecimal.valueOf(0.1)."),
            new LineRule("CATCH_GENERIC_EXCEPTION", Severity.MEDIUM,
                    Pattern.compile("catch\\s*\\(\\s*(?:final\\s+)?(?:Exception|Throwable|RuntimeException)\\s+\\w+\\s*\\)"), true, true,
                    "Catching a very broad exception type can hide bugs and swallow errors you didn't plan for.",
                    "Catch the specific exceptions this code can throw, or rethrow with context."),
            new LineRule("PRINT_STACK_TRACE", Severity.MEDIUM, Pattern.compile("\\.printStackTrace\\s*\\(\\s*\\)"), true, true,
                    "printStackTrace() writes to stderr and bypasses logging, so the error won't reach your log tooling.",
                    "Log it: log.error(\"What failed\", e);"),
            new LineRule("SYSTEM_OUT", Severity.LOW, Pattern.compile("System\\.(?:out|err)\\.print"), true, true,
                    "System.out/err isn't captured by the logging setup in production.",
                    "Use an SLF4J logger at the right level."),
            new LineRule("THREAD_SLEEP", Severity.LOW, Pattern.compile("Thread\\.sleep\\s*\\("), true, true,
                    "Thread.sleep in application code often hides a timing problem and blocks a thread.",
                    "Consider a scheduler, a retry with backoff, or waiting on the actual condition."),
            new LineRule("TODO_COMMENT", Severity.LOW, Pattern.compile("(?i)(?://|#|/\\*|\\*)\\s*(TODO|FIXME|HACK)\\b"), false, false,
                    "New TODO/FIXME added in this change.",
                    "Link it to a ticket so it doesn't get lost, or resolve it before merging."));

    private static final Pattern EMPTY_CATCH_ONE_LINE = Pattern.compile("catch\\s*\\([^)]*\\)\\s*\\{\\s*}");
    private static final Pattern CATCH_OPEN = Pattern.compile("catch\\s*\\([^)]*\\)\\s*\\{\\s*$");
    private static final Pattern CLOSE_BRACE = Pattern.compile("^\\s*}.*$");
    private static final Pattern AUTOWIRED = Pattern.compile("^\\s*@Autowired\\s*$");
    private static final Pattern FIELD_DECL = Pattern.compile("^\\s*(?:private|protected|public)?\\s*(?:final\\s+)?[\\w.<>,?\\[\\] ]+\\s+\\w+\\s*;\\s*$");

    public List<Finding> check(FileDiff file) {
        List<Finding> out = new ArrayList<>();
        if (!file.isReviewable()) {
            return out;
        }
        List<FileDiff.AddedLine> lines = file.addedLines();
        for (int i = 0; i < lines.size(); i++) {
            FileDiff.AddedLine line = lines.get(i);
            String code = line.content();
            boolean comment = isComment(code);
            for (LineRule rule : LINE_RULES) {
                if ((rule.javaOnly() && !file.isJava()) || (rule.skipComments() && comment)) {
                    continue;
                }
                if (rule.pattern().matcher(code).find()) {
                    out.add(new Finding(rule.id(), rule.severity(), file.path(), line.number(), rule.message(), rule.suggestion(), Source.RULE));
                }
            }
            if (file.isJava() && !comment) {
                checkMultiLine(file, lines, i, out);
            }
        }
        return out;
    }

    private static void checkMultiLine(FileDiff file, List<FileDiff.AddedLine> lines, int i, List<Finding> out) {
        FileDiff.AddedLine line = lines.get(i);
        FileDiff.AddedLine next = i + 1 < lines.size() ? lines.get(i + 1) : null;
        boolean nextIsAdjacent = next != null && next.number() == line.number() + 1;

        boolean emptyCatch = EMPTY_CATCH_ONE_LINE.matcher(line.content()).find()
                || (CATCH_OPEN.matcher(line.content()).find() && nextIsAdjacent && CLOSE_BRACE.matcher(next.content()).matches());
        if (emptyCatch) {
            out.add(new Finding("EMPTY_CATCH", Severity.HIGH, file.path(), line.number(),
                    "This catch block is empty, so the exception disappears without a trace.",
                    "Log it, rethrow it wrapped with context, or add a comment explaining why ignoring it is safe.", Source.RULE));
        }
        if (AUTOWIRED.matcher(line.content()).matches() && nextIsAdjacent && FIELD_DECL.matcher(next.content()).matches()) {
            out.add(new Finding("FIELD_INJECTION", Severity.LOW, file.path(), line.number(),
                    "Field injection hides dependencies and makes the class harder to test.",
                    "Use constructor injection with a final field.", Source.RULE));
        }
    }

    private static boolean isComment(String code) {
        String t = code.trim();
        return t.startsWith("//") || t.startsWith("*") || t.startsWith("/*");
    }
}
