package com.junaid.review.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class DiffAndRulesTest {

    private final DiffParser parser = new DiffParser();
    private final RuleChecker rules = new RuleChecker();

    static String sampleDiff() throws Exception {
        return Files.readString(Path.of("sample-diffs", "payment-service.diff"));
    }

    @Test
    void parsesFilesAndNewFileLineNumbers() throws Exception {
        List<FileDiff> files = parser.parse(sampleDiff());

        assertEquals(3, files.size());
        FileDiff service = files.get(0);
        assertEquals("src/main/java/com/example/payments/PaymentService.java", service.path());
        assertEquals(4, service.addedLines().get(0).number(), "first added import is line 4 of the new file");
        assertEquals("import java.sql.Connection;", service.addedLines().get(0).content());
        assertEquals(3, service.removedCount());
        assertTrue(service.addedLineNumbers().contains(21));
        assertFalse(service.addedLineNumbers().contains(18), "line 18 is unchanged context");

        FileDiff readme = files.get(1);
        assertEquals(List.of(6), readme.addedLines().stream().map(FileDiff.AddedLine::number).toList());

        FileDiff deleted = files.get(2);
        assertTrue(deleted.deleted());
        assertFalse(deleted.isReviewable());
    }

    @Test
    void handlesMultipleHunksAndNoNewlineMarker() {
        String diff = """
                diff --git a/A.java b/A.java
                --- a/A.java
                +++ b/A.java
                @@ -1,2 +1,3 @@
                 class A {
                +  int x;
                 }
                @@ -10,2 +11,3 @@
                 void f() {
                +  g();
                 }
                \\ No newline at end of file
                """;
        List<Integer> lines = parser.parse(diff).get(0).addedLines().stream().map(FileDiff.AddedLine::number).toList();
        assertEquals(List.of(2, 12), lines);
    }

    @Test
    void handlesRenamesAndEmptyInput() {
        String diff = """
                diff --git a/old/Name.java b/new/Name.java
                similarity index 90%
                rename from old/Name.java
                rename to new/Name.java
                --- a/old/Name.java
                +++ b/new/Name.java
                @@ -1 +1,2 @@
                 class Name {}
                +// renamed
                """;
        assertEquals("new/Name.java", parser.parse(diff).get(0).path());
        assertTrue(parser.parse("").isEmpty());
        assertTrue(parser.parse(null).isEmpty());
    }

    @Test
    void catchesTheExpectedProblemsInTheSampleDiff() throws Exception {
        List<Finding> findings = new ArrayList<>();
        for (FileDiff f : parser.parse(sampleDiff())) {
            findings.addAll(rules.check(f));
        }
        Set<String> found = findings.stream().map(f -> f.ruleId() + "@" + f.line()).collect(Collectors.toSet());

        Set<String> expected = Set.of(
                "FIELD_INJECTION@13", "HARDCODED_SECRET@16", "BIGDECIMAL_FROM_DOUBLE@17", "SQL_CONCATENATION@21",
                "STRING_REFERENCE_EQUALITY@27", "SYSTEM_OUT@28", "CATCH_GENERIC_EXCEPTION@32", "EMPTY_CATCH@32",
                "TODO_COMMENT@34", "THREAD_SLEEP@36", "PRINT_STACK_TRACE@38", "HARDCODED_SECRET@6");
        assertEquals(expected, found);
    }

    @Test
    void avoidsCommonFalsePositives() {
        String diff = """
                diff --git a/B.java b/B.java
                --- a/B.java
                +++ b/B.java
                @@ -0,0 +1,9 @@
                +// System.out.println("commented out");
                +String password = System.getenv("DB_PASSWORD");
                +if ("USD".equals(currency)) { }
                +BigDecimal fee = new BigDecimal("0.029");
                +String sql = "SELECT * FROM t WHERE id = ?";
                +log.info("count == {}", n);
                +if (count == 0) return;
                +} catch (IOException e) {
                +  throw new UncheckedIOException(e);
                """;
        List<Finding> findings = rules.check(parser.parse(diff).get(0));
        assertTrue(findings.isEmpty(), findings.toString());
    }

    @Test
    void secretsAreCheckedInAnyFileTypeButJavaRulesAreNot() {
        String diff = """
                diff --git a/app.yml b/app.yml
                --- a/app.yml
                +++ b/app.yml
                @@ -0,0 +1,2 @@
                +api_key: "abcd1234efgh"
                +script: System.out.println
                """;
        List<Finding> findings = rules.check(parser.parse(diff).get(0));
        assertEquals(1, findings.size());
        assertEquals("HARDCODED_SECRET", findings.get(0).ruleId());
    }

    @Test
    void detectsAwsKeys() {
        String diff = "diff --git a/x.properties b/x.properties\n--- a/x.properties\n+++ b/x.properties\n@@ -0,0 +1 @@\n"
                + "+aws.key=AKIAIOSFODNN7EXAMPLE\n";
        assertEquals("AWS_ACCESS_KEY", rules.check(parser.parse(diff).get(0)).get(0).ruleId());
    }
}
