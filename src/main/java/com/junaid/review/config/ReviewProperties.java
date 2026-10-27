package com.junaid.review.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param maxComments       most inline comments per review; extra low-priority ones are summarised instead
 * @param maxAiFiles        most files sent to the LLM per pull request (largest changes first)
 * @param maxAiCharsPerFile size cap for each file's prompt, which keeps cost predictable
 */
@ConfigurationProperties(prefix = "review")
public record ReviewProperties(int maxComments, int maxAiFiles, int maxAiCharsPerFile) {}
