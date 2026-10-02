package com.facecook.common.serviceend;

import com.facecook.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

/**
 * 서비스 종료 시각 이후 채팅 웹소켓 연결({@code /ws})을 401로 거절한다(facecook-be#155).
 * {@code WebSocketConfig}가 로그인 확인({@code ChatHandshakeInterceptor})보다 앞에 둔다.
 */
@RequiredArgsConstructor
public class ServiceEndHandshakeInterceptor implements HandshakeInterceptor {

    private final ServiceEndPolicy policy;

    @Override
    public boolean beforeHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Map<String, Object> attributes
    ) {
        if (!policy.isEnded()) {
            return true;
        }
        response.setStatusCode(ErrorCode.SERVICE_ENDED.getStatus());
        return false;
    }

    @Override
    public void afterHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Exception exception
    ) {
        // no-op
    }
}
