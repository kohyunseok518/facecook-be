package com.facecook.common.serviceend;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class ServiceEndPolicyTest {

    private static final OffsetDateTime END_AT = OffsetDateTime.parse("2026-10-03T00:00:00+09:00");

    @Test
    void neverEndsWhenEndTimeIsNotSet() {
        assertThat(policy(null, "2030-01-01T00:00:00Z").isEnded()).isFalse();
    }

    @Test
    void endsExactlyAtEndTime() {
        assertThat(policy(END_AT, "2026-10-02T14:59:59Z").isEnded()).isFalse();
        assertThat(policy(END_AT, "2026-10-02T15:00:00Z").isEnded()).isTrue();
    }

    @Test
    void handshakeIsRejectedWith401AfterEnd() {
        ServerHttpResponse response = mock(ServerHttpResponse.class);
        ServiceEndHandshakeInterceptor interceptor =
                new ServiceEndHandshakeInterceptor(policy(END_AT, "2026-10-02T15:00:00Z"));

        boolean proceed = interceptor.beforeHandshake(
                mock(ServerHttpRequest.class), response, mock(WebSocketHandler.class), new HashMap<>());

        assertThat(proceed).isFalse();
        verify(response).setStatusCode(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void handshakePassesBeforeEnd() {
        ServerHttpResponse response = mock(ServerHttpResponse.class);
        ServiceEndHandshakeInterceptor interceptor =
                new ServiceEndHandshakeInterceptor(policy(END_AT, "2026-10-02T14:59:59Z"));

        boolean proceed = interceptor.beforeHandshake(
                mock(ServerHttpRequest.class), response, mock(WebSocketHandler.class), new HashMap<>());

        assertThat(proceed).isTrue();
        verify(response, never()).setStatusCode(HttpStatus.UNAUTHORIZED);
    }

    private static ServiceEndPolicy policy(OffsetDateTime endAt, String now) {
        return new ServiceEndPolicy(new ServiceEndProperties(endAt), Clock.fixed(Instant.parse(now), ZoneOffset.UTC));
    }
}
