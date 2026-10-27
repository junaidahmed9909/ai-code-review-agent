package com.junaid.review.api;

import com.junaid.review.service.ReviewEngine;
import com.junaid.review.service.ReviewStats;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Review any diff over HTTP, without GitHub. Handy for demos, local use and CI scripts. */
@RestController
@RequestMapping("/api")
public class ReviewController {

    static final int MAX_DIFF_CHARS = 1_000_000;

    private final ReviewEngine engine;
    private final ReviewStats stats;

    public ReviewController(ReviewEngine engine, ReviewStats stats) {
        this.engine = engine;
        this.stats = stats;
    }

    /** Send the raw diff as text/plain, for example: git diff main | curl --data-binary @- ... */
    @PostMapping(path = "/reviews", consumes = MediaType.TEXT_PLAIN_VALUE)
    public ReviewEngine.ReviewResult review(@RequestBody String diff) {
        if (diff.length() > MAX_DIFF_CHARS) {
            throw new IllegalArgumentException("Diff is too large (over " + MAX_DIFF_CHARS + " characters)");
        }
        return engine.review(diff);
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        return stats.snapshot();
    }
}
