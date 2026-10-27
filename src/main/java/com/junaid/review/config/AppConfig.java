package com.junaid.review.config;

import com.junaid.review.github.GitHubClient;
import com.junaid.review.github.GitHubProperties;
import com.junaid.review.llm.AnthropicLlmClient;
import com.junaid.review.llm.LlmClient;
import com.junaid.review.llm.LlmProperties;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class AppConfig {

    private static final Logger log = LoggerFactory.getLogger(AppConfig.class);

    @Bean
    LlmClient llmClient(LlmProperties props) {
        if (!props.hasKey()) {
            log.info("No LLM API key set: reviews will use the rule checks only");
            return LlmClient.DISABLED;
        }
        log.info("LLM reviews enabled with model {}", props.model());
        return new AnthropicLlmClient(RestClient.builder().requestFactory(factory(props.timeout())), props);
    }

    @Bean
    GitHubClient gitHubClient(GitHubProperties props) {
        return new GitHubClient(RestClient.builder().requestFactory(factory(Duration.ofSeconds(30))), props);
    }

    private static SimpleClientHttpRequestFactory factory(Duration timeout) {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(timeout);
        f.setReadTimeout(timeout);
        return f;
    }
}
