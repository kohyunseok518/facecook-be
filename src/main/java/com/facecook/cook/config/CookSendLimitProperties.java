package com.facecook.cook.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 콕 전송 동시 처리 수 제한 설정({@link com.facecook.cook.service.CookSendGate}).
 *
 * <p>행사일 콕 전송은 {@code event_limit_lock} 하나를 차례로 지나가서, 몰리면 줄 선 요청이 DB 연결을 쥔 채
 * 기다린다. 서버의 연결 풀(기본 10개)이 이 요청들로 차면 콕과 무관한 API와 헬스체크까지 느려진다(#143). 콕 전송이
 * 동시에 쓸 수 있는 연결 수를 이 값으로 묶어 나머지 연결을 다른 API 몫으로 남긴다.</p>
 *
 * @param maxConcurrent 서버 한 대가 동시에 처리하는 콕 전송 수(환경변수 {@code COOK_SEND_MAX_CONCURRENT}, 기본 4).
 *                      연결 풀 크기보다 작아야 다른 API 몫이 남는다.
 * @param maxWait       자리가 없을 때 기다리는 최대 시간(환경변수 {@code COOK_SEND_MAX_WAIT}, 기본 0.5초). 이 안에 자리가
 *                      나면 처리하고, 넘기면 {@code COOK_BUSY}로 응답한다. 기다리는 요청은 웹 서버 스레드(기본 200개)를
 *                      쥐므로 "서버당 초당 콕 전송 × 이 값"이 스레드 수를 넘으면 스레드가 새 병목이 된다(로컬 측정: 초당 500건,
 *                      1.5초에서 스레드 200개가 차 다른 API가 다시 느려짐). 너무 길게 두지 않는다.
 */
@ConfigurationProperties(prefix = "app.cook.send-limit")
public record CookSendLimitProperties(int maxConcurrent, Duration maxWait) {

    public CookSendLimitProperties {
        if (maxConcurrent < 1) {
            throw new IllegalArgumentException("app.cook.send-limit.max-concurrent는 1 이상이어야 한다: " + maxConcurrent);
        }
        if (maxWait == null || maxWait.isNegative()) {
            throw new IllegalArgumentException("app.cook.send-limit.max-wait는 0 이상이어야 한다: " + maxWait);
        }
    }
}
