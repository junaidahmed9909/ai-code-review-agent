package com.junaid.review.service;

import com.junaid.review.github.GitHubClient;
import com.junaid.review.github.GitHubProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Reviews a pull request in the background. GitHub expects a webhook reply within 10 seconds,
 * and an LLM review can take longer, so the webhook returns 202 straight away and this runs afterwards.
 */
@Service
public class PullRequestReviewer {

    private static final Logger log = LoggerFactory.getLogger(PullRequestReviewer.class);

    private final GitHubClient github;
    private final ReviewEngine engine;
    private final GitHubProperties props;

    public PullRequestReviewer(GitHubClient github, ReviewEngine engine, GitHubProperties props) {
        this.github = github;
        this.engine = engine;
        this.props = props;
    }

    @Async
    public void reviewPullRequest(String owner, String repo, int number, String headSha) {
        String pr = owner + "/" + repo + "#" + number;
        try {
            String diff = github.fetchDiff(owner, repo, number);
            if (diff.isBlank()) {
                log.info("{} has an empty diff; nothing to review", pr);
                return;
            }
            ReviewEngine.ReviewResult result = engine.review(diff);
            if (!props.postReviews()) {
                log.info("Dry run for {}: {} comments would be posted", pr, result.comments().size());
                return;
            }
            github.postReview(owner, repo, number, headSha, result.summaryMarkdown(), result.comments());
            log.info("Posted review on {} with {} comments in {} ms", pr, result.comments().size(), result.durationMs());
        } catch (RuntimeException e) {
            // Background work: log it with enough context to debug, never crash the worker thread.
            log.error("Review failed for {}: {}", pr, e.getMessage(), e);
        }
    }
}
