package com.facecook.cook.service;

import com.facecook.common.exception.ApiException;
import com.facecook.common.exception.ErrorCode;
import com.facecook.cook.config.CookSendLimitProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link CookSendGate}의 자리 관리: 끝나면 반납, 자리가 없으면 최대 대기 시간까지 기다렸다가 거절, 기다리는 사이 자리가
 * 나면 처리, 실행이 예외로 끝나도 반납. 동시 상황은 다른 스레드가 자리를 쥔 채 멈춰 있게 해서 시간 추측 없이 만든다.
 */
class CookSendGateTest {

    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    @AfterEach
    void shutDown() {
        executor.shutdownNow();
    }

    @Test
    void returnsTheResultAndGivesThePermitBackSoLaterCallsPass() {
        CookSendGate gate = gate(1, Duration.ZERO);

        for (int i = 0; i < 3; i++) {
            assertThat(gate.run(() -> "ok")).isEqualTo("ok");
        }
    }

    @Test
    void rejectsWithCookBusyWithoutRunningWhenNoPermitFreesUpWithinTheWait() throws Exception {
        CookSendGate gate = gate(1, Duration.ofMillis(100));
        CountDownLatch release = holdThePermit(gate);
        AtomicBoolean ran = new AtomicBoolean();

        try {
            assertThatThrownBy(() -> gate.run(() -> ran.getAndSet(true)))
                    .isInstanceOfSatisfying(ApiException.class,
                            exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.COOK_BUSY));
            assertThat(ran).isFalse();
        } finally {
            release.countDown();
        }
    }

    @Test
    void waitsAndRunsWhenThePermitFreesUpWithinTheWait() throws Exception {
        CookSendGate gate = gate(1, Duration.ofSeconds(10));
        CountDownLatch release = holdThePermit(gate);

        Future<String> waiting = executor.submit(() -> gate.run(() -> "ran after waiting"));
        release.countDown();

        assertThat(waiting.get(10, TimeUnit.SECONDS)).isEqualTo("ran after waiting");
    }

    @Test
    void givesThePermitBackWhenTheActionThrows() {
        CookSendGate gate = gate(1, Duration.ZERO);

        assertThatThrownBy(() -> gate.run(() -> {
            throw new ApiException(ErrorCode.DUPLICATE);
        })).isInstanceOfSatisfying(ApiException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.DUPLICATE));

        assertThat(gate.run(() -> "ok")).isEqualTo("ok");
    }

    @Test
    void rejectsInvalidSettings() {
        assertThatThrownBy(() -> new CookSendLimitProperties(0, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CookSendLimitProperties(1, Duration.ofMillis(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static CookSendGate gate(int maxConcurrent, Duration maxWait) {
        return new CookSendGate(new CookSendLimitProperties(maxConcurrent, maxWait));
    }

    /** 다른 스레드가 자리 하나를 쥔 채 멈춰 있게 한다. 돌려준 래치를 내리면 그 스레드가 끝나며 자리를 반납한다. */
    private CountDownLatch holdThePermit(CookSendGate gate) throws InterruptedException {
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        executor.submit(() -> gate.run(() -> {
            holding.countDown();
            try {
                release.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            return null;
        }));
        assertThat(holding.await(10, TimeUnit.SECONDS)).isTrue();
        return release;
    }
}
