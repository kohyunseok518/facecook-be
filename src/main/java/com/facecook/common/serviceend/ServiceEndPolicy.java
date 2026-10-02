package com.facecook.common.serviceend;

import lombok.RequiredArgsConstructor;

import java.time.Clock;

/**
 * 지금이 서비스 종료 시각 이후인지 판단한다(facecook-be#155). REST({@link ServiceEndInterceptor}),
 * 채팅 웹소켓({@link ServiceEndHandshakeInterceptor}, {@link ServiceEndChannelInterceptor}),
 * 웹 푸시({@code ParticipantPushNotificationService})가 같은 기준을 쓴다.
 *
 * <p>종료 시각은 포함한다 — 종료 시각 정각부터 막힌다.</p>
 */
@RequiredArgsConstructor
public class ServiceEndPolicy {

    private final ServiceEndProperties properties;
    private final Clock clock;

    public boolean isEnded() {
        return properties.endAt() != null && !clock.instant().isBefore(properties.endAt().toInstant());
    }
}
