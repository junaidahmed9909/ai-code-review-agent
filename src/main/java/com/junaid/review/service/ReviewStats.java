package com.junaid.review.service;

import com.junaid.review.core.Finding;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/** Running totals since startup, exposed at /api/stats and as Micrometer metrics. */
@Component
public class ReviewStats {

    private final MeterRegistry meters;
    private final AtomicLong reviews = new AtomicLong();
    private final AtomicLong files = new AtomicLong();
    private final AtomicLong comments = new AtomicLong();
    private final AtomicLong hallucinatedAi = new AtomicLong();
    private final AtomicLong totalMillis = new AtomicLong();
    private final Map<String, AtomicLong> byRule = new ConcurrentHashMap<>();

    public ReviewStats(MeterRegistry meters) {
        this.meters = meters;
    }

    public void record(List<Finding> posted, int filesReviewed, long durationMs, int droppedInvalidAi) {
        reviews.incrementAndGet();
        files.addAndGet(filesReviewed);
        comments.addAndGet(posted.size());
        hallucinatedAi.addAndGet(droppedInvalidAi);
        totalMillis.addAndGet(durationMs);
        for (Finding f : posted) {
            byRule.computeIfAbsent(f.ruleId(), k -> new AtomicLong()).incrementAndGet();
            meters.counter("review.findings", "rule", f.ruleId(), "severity", f.severity().name(), "source", f.source().name()).increment();
        }
        meters.counter("review.reviews").increment();
    }

    public Map<String, Object> snapshot() {
        long r = reviews.get();
        Map<String, Long> rules = new TreeMap<>();
        byRule.forEach((k, v) -> rules.put(k, v.get()));
        return Map.of(
                "reviews", r,
                "filesReviewed", files.get(),
                "commentsPosted", comments.get(),
                "aiCommentsRejectedAsOffDiff", hallucinatedAi.get(),
                "averageReviewMillis", r == 0 ? 0 : totalMillis.get() / r,
                "findingsByRule", rules);
    }
}
