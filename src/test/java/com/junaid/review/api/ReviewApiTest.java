package com.junaid.review.api;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.junaid.review.core.WebhookSignature;
import com.junaid.review.service.PullRequestReviewer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReviewApiTest {

    private static final String SECRET = "test-secret";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private PullRequestReviewer reviewer;

    @Test
    void reviewsARawDiff() throws Exception {
        String diff = Files.readString(Path.of("sample-diffs", "payment-service.diff"));

        mvc.perform(post("/api/reviews").contentType(MediaType.TEXT_PLAIN).content(diff))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.comments.length()").value(12))
                .andExpect(jsonPath("$.comments[0].severity").value("HIGH"))
                .andExpect(jsonPath("$.filesReviewed").value(2))
                .andExpect(jsonPath("$.aiUsed").value(false))
                .andExpect(jsonPath("$.summaryMarkdown").exists());

        mvc.perform(get("/api/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviews").value(greaterThanOrEqualTo(1)));
    }

    @Test
    void rejectsTextThatIsNotADiff() throws Exception {
        mvc.perform(post("/api/reviews").contentType(MediaType.TEXT_PLAIN).content("hello"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void webhookRejectsBadSignature() throws Exception {
        mvc.perform(post("/webhooks/github").header("X-GitHub-Event", "ping")
                        .header("X-Hub-Signature-256", "sha256=deadbeef")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void webhookAnswersPing() throws Exception {
        mvc.perform(signed("ping", "{\"zen\":\"Keep it simple.\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("pong"));
    }

    @Test
    void webhookStartsReviewForOpenedPullRequest() throws Exception {
        String payload = """
                {"action":"opened","number":7,
                 "pull_request":{"number":7,"draft":false,"head":{"sha":"abc123"}},
                 "repository":{"name":"demo","owner":{"login":"octo"}}}""";

        mvc.perform(signed("pull_request", payload)).andExpect(status().isAccepted());
        verify(reviewer).reviewPullRequest("octo", "demo", 7, "abc123");
    }

    @Test
    void webhookSkipsDraftsAndOtherActions() throws Exception {
        mvc.perform(signed("pull_request", """
                {"action":"opened","number":8,"pull_request":{"draft":true},
                 "repository":{"name":"demo","owner":{"login":"octo"}}}"""))
                .andExpect(status().isOk());
        mvc.perform(signed("pull_request", """
                {"action":"closed","number":9,"pull_request":{"draft":false},
                 "repository":{"name":"demo","owner":{"login":"octo"}}}"""))
                .andExpect(status().isOk());
        verify(reviewer, never()).reviewPullRequest(anyString(), anyString(), anyInt(), anyString());
    }

    private static MockHttpServletRequestBuilder signed(String event, String body) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return post("/webhooks/github")
                .header("X-GitHub-Event", event)
                .header("X-Hub-Signature-256", WebhookSignature.sign(SECRET, bytes))
                .contentType(MediaType.APPLICATION_JSON)
                .content(bytes);
    }
}
