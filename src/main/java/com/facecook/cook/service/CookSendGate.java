package com.facecook.cook.service;

import com.facecook.common.exception.ApiException;
import com.facecook.common.exception.ErrorCode;
import com.facecook.cook.config.CookSendLimitProperties;
import org.springframework.stereotype.Component;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 서버 한 대에서 동시에 처리하는 콕 전송 수를 제한한다(#143).
 *
 * <p>행사일 콕 전송은 모두 {@code event_limit_lock}을 차례로 지나간다. 초당 전송이 그 처리 속도를 넘으면 줄 선
 * 요청이 DB 연결을 쥔 채 기다려 연결 풀이 바닥나고, 콕과 무관한 API와 헬스체크까지 느려진다(2026-09-27 운영 측정:
 * 초당 90건에서 콕 목록 조회 중앙값 20ms → 2초). 이 게이트가 콕 전송이 동시에 쓰는 연결 수를
 * {@link CookSendLimitProperties#maxConcurrent()}로 묶어 나머지 연결을 다른 API 몫으로 남긴다.</p>
 *
 * <p>자리가 없으면 {@link CookSendLimitProperties#maxWait()}까지 기다린다. 순간 몰림은 대부분 이 안에서
 * 흡수되고, 그래도 자리가 없으면 DB에 가지 않고 {@code COOK_BUSY}로 거절한다 — 하루 사용량은 줄지 않는다.
 * 먼저 기다린 요청이 먼저 들어가도록 공정(fair) 세마포어를 쓴다. 기다리는 동안 웹 서버 스레드를 쥐므로 대기 시간은
 * 짧게 둔다({@link CookSendLimitProperties#maxWait()} 참고).</p>
 *
 * <p><b>트랜잭션 밖에서 불러야 한다.</b> {@link CookService#send}는 들어가는 순간 DB 연결을 잡으므로, 그 안에서
 * 기다리면 연결을 쥔 채 기다려 이 게이트가 막으려는 고갈이 그대로 생긴다. 그래서 컨트롤러가 호출한다.</p>
 */
@Component
public class CookSendGate {

    private final Semaphore permits;
    private final long maxWaitMillis;

    public CookSendGate(CookSendLimitProperties properties) {
        this.permits = new Semaphore(properties.maxConcurrent(), true);
        this.maxWaitMillis = properties.maxWait().toMillis();
    }

    /**
     * 자리를 얻어 action을 실행하고 결과를 돌려준다. action이 끝나면(예외여도) 자리를 반납한다.
     *
     * <p>전제조건: 호출한 스레드에 진행 중인 트랜잭션이 없다(클래스 설명 참고).</p>
     *
     * <p>예외: {@code COOK_BUSY} — 최대 대기 시간 안에 자리가 나지 않았거나 기다리는 중에 스레드가 중단됐다. 이때
     * action은 실행하지 않는다. action이 던진 예외는 그대로 전달한다.</p>
     */
    public <T> T run(Supplier<T> action) {
        if (!acquire()) {
            throw new ApiException(ErrorCode.COOK_BUSY);
        }
        try {
            return action.get();
        } finally {
            permits.release();
        }
    }

    private boolean acquire() {
        try {
            return permits.tryAcquire(maxWaitMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
