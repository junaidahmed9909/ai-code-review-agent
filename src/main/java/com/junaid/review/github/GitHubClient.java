package com.junaid.review.github;

import com.junaid.review.core.Finding;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/** The two GitHub REST calls the agent needs: read a PR's diff, and post a review. */
public class GitHubClient {

    private static final Logger log = LoggerFactory.getLogger(GitHubClient.class);

    private final RestClient http;

    public GitHubClient(RestClient.Builder builder, GitHubProperties props) {
        RestClient.Builder b = builder
                .baseUrl(props.apiUrl())
                .defaultHeader("Accept", "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28");
        if (props.token() != null && !props.token().isBlank()) {
            b = b.defaultHeader("Authorization", "Bearer " + props.token());
        }
        this.http = b.build();
    }

    public String fetchDiff(String owner, String repo, int number) {
        String diff = http.get()
                .uri("/repos/{owner}/{repo}/pulls/{number}", owner, repo, number)
                .header("Accept", "application/vnd.github.diff")
                .retrieve()
                .body(String.class);
        return diff == null ? "" : diff;
    }

    /**
     * Posts one review with inline comments. If GitHub rejects a comment position (422), it falls back to a single
     * review whose body lists the findings, so the author still gets the feedback.
     */
    public void postReview(String owner, String repo, int number, String commitSha, String summary, List<Finding> findings) {
        List<Map<String, Object>> comments = findings.stream().map(f -> {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("path", f.path());
            c.put("line", f.line());
            c.put("side", "RIGHT");
            c.put("body", f.toCommentBody());
            return c;
        }).toList();
        try {
            send(owner, repo, number, commitSha, summary, comments);
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode().value() != 422 || comments.isEmpty()) {
                throw e;
            }
            log.warn("GitHub rejected inline comments on {}/{}#{}; posting them in the review body instead", owner, repo, number);
            send(owner, repo, number, commitSha, summary + "\n" + asList(findings), List.of());
        }
    }

    private void send(String owner, String repo, int number, String commitSha, String body, List<Map<String, Object>> comments) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (commitSha != null && !commitSha.isBlank()) {
            payload.put("commit_id", commitSha);
        }
        payload.put("event", "COMMENT");
        payload.put("body", body);
        payload.put("comments", comments);
        http.post()
                .uri("/repos/{owner}/{repo}/pulls/{number}/reviews", owner, repo, number)
                .contentType(MediaType.APPLICATION_JSON)
                .body(payload)
                .retrieve()
                .toBodilessEntity();
    }

    private static String asList(List<Finding> findings) {
        StringBuilder sb = new StringBuilder("\n### Findings\n\n");
        for (Finding f : findings) {
            sb.append("- `").append(f.path()).append(':').append(f.line()).append("` **").append(f.severity())
                    .append("** ").append(f.message()).append('\n');
        }
        return sb.toString();
    }
}
