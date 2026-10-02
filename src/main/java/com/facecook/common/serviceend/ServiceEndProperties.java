package com.facecook.common.serviceend;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.OffsetDateTime;

/**
 * 서비스 종료 시각({@code app.service-end.end-at}, 환경변수 {@code SERVICE_END_AT}, facecook-be#155).
 * 시간대를 포함한 ISO 형식으로 적는다(예: {@code 2026-10-03T00:00:00+09:00}). 비워 두면 종료하지 않는다.
 * 이 시각부터 {@link ServiceEndPolicy}가 기존 API·채팅·푸시를 막는다.
 */
@ConfigurationProperties(prefix = "app.service-end")
public record ServiceEndProperties(OffsetDateTime endAt) {
}
