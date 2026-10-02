package com.facecook.feedback.service;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class FeedbackRateLimiterTest {

    @Test
    void allowsUpToLimitPerMinuteThenRejectsUntilNextMinute() {
        MovableClock clock = new MovableClock(Instant.parse("2026-10-02T15:00:00Z"));
        FeedbackRateLimiter limiter = new FeedbackRateLimiter(clock);

        for (int i = 0; i < FeedbackRateLimiter.MAX_PER_MINUTE; i++) {
            assertThat(limiter.tryAcquire()).isTrue();
        }
        assertThat(limiter.tryAcquire()).isFalse();

        clock.advance(Duration.ofSeconds(59));
        assertThat(limiter.tryAcquire()).isFalse();

        clock.advance(Duration.ofSeconds(1));
        assertThat(limiter.tryAcquire()).isTrue();
    }

    private static class MovableClock extends Clock {
        private Instant instant;

        MovableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
