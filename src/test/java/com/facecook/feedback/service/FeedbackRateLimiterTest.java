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
    void limitsEachClientPerMinuteWithoutBlockingOthers() {
        MovableClock clock = new MovableClock(Instant.parse("2026-10-02T15:00:00Z"));
        FeedbackRateLimiter limiter = new FeedbackRateLimiter(clock);

        for (int i = 0; i < FeedbackRateLimiter.MAX_PER_CLIENT_PER_MINUTE; i++) {
            assertThat(limiter.tryAcquire("1.1.1.1")).isTrue();
        }
        assertThat(limiter.tryAcquire("1.1.1.1")).isFalse();
        // 한 주소가 한도를 채워도 다른 주소는 그대로 보낸다
        assertThat(limiter.tryAcquire("2.2.2.2")).isTrue();

        clock.advance(Duration.ofSeconds(59));
        assertThat(limiter.tryAcquire("1.1.1.1")).isFalse();

        clock.advance(Duration.ofSeconds(1));
        assertThat(limiter.tryAcquire("1.1.1.1")).isTrue();
    }

    @Test
    void acceptsEveryParticipantInOneMinuteUpToServerLimit() {
        MovableClock clock = new MovableClock(Instant.parse("2026-10-02T15:00:00Z"));
        FeedbackRateLimiter limiter = new FeedbackRateLimiter(clock);

        // 참가자 600명이 서로 다른 주소에서 1분 안에 한 번씩 보낸다
        for (int i = 0; i < FeedbackRateLimiter.MAX_PER_MINUTE; i++) {
            assertThat(limiter.tryAcquire("10.0." + (i / 256) + "." + (i % 256))).isTrue();
        }
        assertThat(limiter.tryAcquire("10.9.9.9")).isFalse();

        clock.advance(Duration.ofMinutes(1));
        assertThat(limiter.tryAcquire("10.9.9.9")).isTrue();
    }

    @Test
    void rejectedRequestDoesNotUseServerQuota() {
        MovableClock clock = new MovableClock(Instant.parse("2026-10-02T15:00:00Z"));
        FeedbackRateLimiter limiter = new FeedbackRateLimiter(clock);

        // 한 주소가 한도를 넘겨 계속 보내도 서버 전체 자리는 줄지 않는다
        for (int i = 0; i < FeedbackRateLimiter.MAX_PER_MINUTE * 2; i++) {
            limiter.tryAcquire("1.1.1.1");
        }
        for (int i = 0; i < FeedbackRateLimiter.MAX_PER_MINUTE - FeedbackRateLimiter.MAX_PER_CLIENT_PER_MINUTE; i++) {
            assertThat(limiter.tryAcquire("10.0." + (i / 256) + "." + (i % 256))).isTrue();
        }
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
