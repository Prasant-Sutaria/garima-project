package org.study.usageapi.service;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.study.usageapi.api.ApiException;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class RateLimiter {

    private static final long WINDOW_SECONDS = 60;
    private static final long WINDOW_MILLIS = WINDOW_SECONDS * 1000;
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final Clock clock;

    public RateLimiter(Clock clock) {
        this.clock = clock;
    }

    public void check(String key, int limit) {
        long now = clock.millis();
        buckets.compute(key, (ignored, existing) -> {
            Bucket bucket = existing == null ? new Bucket() : existing;
            bucket.check(now, limit);
            return bucket;
        });
        if (buckets.size() > 10_000) {
            buckets.forEach((bucketKey, bucket) -> buckets.computeIfPresent(bucketKey, (keyToCheck, current) ->
                    current.lastRequestMillis() < now - WINDOW_MILLIS ? null : current));
        }
    }

    private static final class Bucket {

        private final ArrayDeque<Long> requestTimes = new ArrayDeque<>();
        private long lastRequestMillis;

        private synchronized void check(long now, int limit) {
            long oldestAcceptedTime = now - WINDOW_MILLIS;
            while (!requestTimes.isEmpty() && requestTimes.peekFirst() <= oldestAcceptedTime) {
                requestTimes.removeFirst();
            }
            lastRequestMillis = now;
            if (requestTimes.size() >= limit) {
                throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "rate_limited",
                        "Too many requests. Try again later.");
            }
            requestTimes.addLast(now);
        }

        private synchronized long lastRequestMillis() {
            return lastRequestMillis;
        }
    }
}
