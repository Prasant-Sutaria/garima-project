package org.study.usageapi.service;

import org.junit.jupiter.api.Test;
import org.study.usageapi.api.ApiException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RateLimiterTest {

    @Test
    void enforcesALeadingSixtySecondWindowRatherThanCalendarMinuteBuckets() {
        var clock = new MutableClock(Instant.parse("2026-11-20T12:00:59Z"));
        var limiter = new RateLimiter(clock);
        limiter.check("participant", 2);
        clock.advanceMillis(2_000);
        limiter.check("participant", 2);

        ApiException exception = assertThrows(ApiException.class,
                () -> limiter.check("participant", 2));
        assertEquals(429, exception.status().value());

        clock.advanceMillis(60_000);
        limiter.check("participant", 2);
    }

    private static final class MutableClock extends Clock {

        private final AtomicLong currentMillis;

        private MutableClock(Instant initialInstant) {
            currentMillis = new AtomicLong(initialInstant.toEpochMilli());
        }

        private void advanceMillis(long milliseconds) {
            currentMillis.addAndGet(milliseconds);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(currentMillis.get());
        }
    }
}
