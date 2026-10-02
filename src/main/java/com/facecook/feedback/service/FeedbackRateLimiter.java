package com.facecook.feedback.service;

import org.springframework.stereotype.Component;

import java.time.Clock;

/**
 * 서버 하나가 1분 동안 받는 후기 수를 제한한다(facecook-be#155). 1분 단위로 개수를 세고, 다음 1분이 되면 0부터 다시 센다.
 * 서버 2대면 전체로는 1분에 최대 {@code 2 × MAX_PER_MINUTE}건이다.
 */
@Component
public class FeedbackRateLimiter {

    static final int MAX_PER_MINUTE = 30;
    private static final long WINDOW_MS = 60_000;

    private final Clock clock;
    private long windowStartedAt;
    private int count;

    public FeedbackRateLimiter(Clock clock) {
        this.clock = clock;
        this.windowStartedAt = clock.millis();
    }

    public synchronized boolean tryAcquire() {
        long now = clock.millis();
        if (now - windowStartedAt >= WINDOW_MS) {
            windowStartedAt = now;
            count = 0;
        }
        if (count >= MAX_PER_MINUTE) {
            return false;
        }
        count++;
        return true;
    }
}
