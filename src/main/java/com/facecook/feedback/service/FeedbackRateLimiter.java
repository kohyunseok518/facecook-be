package com.facecook.feedback.service;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;

/**
 * 후기 제출 수를 1분 단위로 제한한다(facecook-be#155). 두 가지를 따로 센다.
 *
 * <ul>
 * <li>접속 주소별 {@link #MAX_PER_CLIENT_PER_MINUTE}건 — 한 사람이 반복해서 보내 다른 사람의 자리를 차지하지 못하게 한다.
 *     같은 와이파이를 쓰는 여러 사람이 동시에 보내도 넉넉한 값이다.</li>
 * <li>서버 전체 {@link #MAX_PER_MINUTE}건 — DB 보호용. 참가자 전원(약 600명)이 1분 안에 보내도 서버 한 대가 다 받는다.</li>
 * </ul>
 *
 * <p>1분이 지나면 두 개수와 주소별 기록을 모두 비우고 0부터 다시 센다 — 주소별 기록은 최근 1분 것만 남아 메모리가 늘지 않는다.
 * 서버마다 따로 센다.</p>
 */
@Component
public class FeedbackRateLimiter {

    static final int MAX_PER_MINUTE = 600;
    static final int MAX_PER_CLIENT_PER_MINUTE = 5;
    private static final long WINDOW_MS = 60_000;

    private final Clock clock;
    private final Map<String, Integer> countsByClient = new HashMap<>();
    private long windowStartedAt;
    private int count;

    public FeedbackRateLimiter(Clock clock) {
        this.clock = clock;
        this.windowStartedAt = clock.millis();
    }

    /** clientKey(접속 주소)로 한 건 더 받을 수 있으면 세고 true. 둘 중 하나라도 넘으면 세지 않고 false. */
    public synchronized boolean tryAcquire(String clientKey) {
        long now = clock.millis();
        if (now - windowStartedAt >= WINDOW_MS) {
            windowStartedAt = now;
            count = 0;
            countsByClient.clear();
        }
        int clientCount = countsByClient.getOrDefault(clientKey, 0);
        if (count >= MAX_PER_MINUTE || clientCount >= MAX_PER_CLIENT_PER_MINUTE) {
            return false;
        }
        count++;
        countsByClient.put(clientKey, clientCount + 1);
        return true;
    }
}
