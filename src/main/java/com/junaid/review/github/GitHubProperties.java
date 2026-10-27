package com.junaid.review.github;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param token         token with "Pull requests: read and write" on the target repos (fine-grained PAT or app token)
 * @param webhookSecret the secret set on the GitHub webhook; required, unsigned requests are rejected
 * @param postReviews   false = dry run: review and log, but don't post to GitHub
 */
@ConfigurationProperties(prefix = "github")
public record GitHubProperties(String token, String webhookSecret, String apiUrl, boolean postReviews) {}
