package com.junaid.review.api;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.junaid.review.core.Finding;
import com.junaid.review.github.GitHubClient;
import com.junaid.review.github.GitHubProperties;
import java.util.List;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class GitHubClientTest {

    private static final String BASE = "https://github.example.test";

    private MockRestServiceServer server;
    private GitHubClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new GitHubClient(builder, new GitHubProperties("tok", "secret", BASE, true));
    }

    private static List<Finding> oneFinding() {
        return List.of(new Finding("EMPTY_CATCH", Finding.Severity.HIGH, "src/A.java", 12, "Empty catch", "Log it", Finding.Source.RULE));
    }

    @Test
    void fetchesTheDiffMediaType() {
        server.expect(requestTo(BASE + "/repos/octo/demo/pulls/7"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Accept", "application/vnd.github.diff"))
                .andExpect(header("Authorization", "Bearer tok"))
                .andExpect(header("X-GitHub-Api-Version", "2022-11-28"))
                .andRespond(withSuccess("diff --git a/A.java b/A.java\n", MediaType.TEXT_PLAIN));

        Assertions.assertTrue(client.fetchDiff("octo", "demo", 7).startsWith("diff --git"));
        server.verify();
    }

    @Test
    void postsInlineCommentsOnTheRightSide() {
        server.expect(requestTo(BASE + "/repos/octo/demo/pulls/7/reviews"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.commit_id").value("abc123"))
                .andExpect(jsonPath("$.event").value("COMMENT"))
                .andExpect(jsonPath("$.comments[0].path").value("src/A.java"))
                .andExpect(jsonPath("$.comments[0].line").value(12))
                .andExpect(jsonPath("$.comments[0].side").value("RIGHT"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        client.postReview("octo", "demo", 7, "abc123", "summary", oneFinding());
        server.verify();
    }

    @Test
    void fallsBackToSummaryWhenGithubRejectsCommentPositions() {
        server.expect(requestTo(BASE + "/repos/octo/demo/pulls/7/reviews"))
                .andRespond(withStatus(HttpStatus.valueOf(422)));
        server.expect(requestTo(BASE + "/repos/octo/demo/pulls/7/reviews"))
                .andExpect(jsonPath("$.comments", Matchers.hasSize(0)))
                .andExpect(jsonPath("$.body", Matchers.containsString("src/A.java:12")))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        client.postReview("octo", "demo", 7, "abc123", "summary", oneFinding());
        server.verify();
    }
}
