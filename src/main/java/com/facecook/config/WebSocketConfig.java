package com.facecook.config;

import com.facecook.chat.websocket.ChatHandshakeHandler;
import com.facecook.chat.websocket.ChatHandshakeInterceptor;
import com.facecook.chat.websocket.ChatInboundChannelInterceptor;
import com.facecook.chat.websocket.ChatStompErrorHandler;
import com.facecook.common.serviceend.ServiceEndChannelInterceptor;
import com.facecook.common.serviceend.ServiceEndHandshakeInterceptor;
import com.facecook.common.serviceend.ServiceEndPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * 채팅·미션 실시간 통신(WebSocket + STOMP) 설정.
 *
 * <p>STOMP는 WebSocket 위에서 "어느 주소로 보낸다/어느 주소를 구독한다"를
 * 표현하는 약속이다. 여기서 정하는 주소 규칙:</p>
 * <ul>
 * <li>{@code /ws} — 브라우저가 처음 연결하는 주소. 연결 전에 세션 쿠키를 확인한다
 * ({@code ChatHandshakeInterceptor})</li>
 * <li>{@code /app/...} — 클라이언트가 보내면 {@code @MessageMapping} 컨트롤러가 받는다
 * (예: {@code /app/chat/{matchId}/send} → {@code ChatMessageController})</li>
 * <li>{@code /topic/...}, {@code /queue/...} — 서버 안의 메시지 브로커(SimpleBroker)가
 * 구독자에게 나눠 준다. 구독할 수 있는 주소는 {@code StompSubscriptionPolicy}가 제한한다</li>
 * </ul>
 *
 * <p>들어오는 모든 STOMP 프레임(CONNECT/SUBSCRIBE/SEND)은 먼저
 * {@code ChatInboundChannelInterceptor}를 지나며 세션을 다시 확인받는다.</p>
 */
@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final CorsProperties corsProperties;
    private final ChatHandshakeInterceptor handshakeInterceptor;
    private final ChatHandshakeHandler handshakeHandler;
    private final ChatInboundChannelInterceptor inboundChannelInterceptor;
    private final ChatStompErrorHandler errorHandler;
    private final ServiceEndPolicy serviceEndPolicy;

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .setAllowedOrigins(corsProperties.allowedOrigins().toArray(String[]::new))
                // 서비스 종료 확인이 로그인 확인보다 먼저다(#155) — 종료 뒤에는 로그인 여부와 관계없이 막는다.
                .addInterceptors(new ServiceEndHandshakeInterceptor(serviceEndPolicy), handshakeInterceptor)
                .setHandshakeHandler(handshakeHandler);
        registry.setErrorHandler(errorHandler);
    }

    /**
     * 서버·클라이언트 STOMP 하트비트 간격(ms). FE({@code chatSocket.ts}, {@code missionSocket.ts})도 10초로
     * 보내고 받는다. 서버가 하트비트를 요구하지 않으면 휴대폰 네트워크 전환처럼 조용히 끊긴 연결을 서버가
     * 알아채지 못한다 — 그 연결의 접속 기록을 계속 연장해서 푸시가 막힌다(#81). 요구하면 클라이언트
     * 하트비트가 끊긴 연결을 브로커가 닫고, 연결 종료 이벤트로 접속 기록도 지워진다.
     */
    private static final long[] STOMP_HEARTBEAT_MS = {10_000, 10_000};

    private TaskScheduler messageBrokerTaskScheduler;

    @Autowired
    public void setMessageBrokerTaskScheduler(@Lazy @Qualifier("messageBrokerTaskScheduler") TaskScheduler scheduler) {
        this.messageBrokerTaskScheduler = scheduler;
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.setApplicationDestinationPrefixes("/app");
        registry.enableSimpleBroker("/topic", "/queue")
                .setHeartbeatValue(STOMP_HEARTBEAT_MS)
                .setTaskScheduler(messageBrokerTaskScheduler);
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        // CONNECT/SUBSCRIBE/SEND마다 세션을 재검증하고, 구독은 허용 목록
        // (StompSubscriptionPolicy), 전송은 채팅 전송 경로만 통과시킨다.
        // 서비스 종료 뒤에는 그 전에 연결해 둔 소켓의 프레임도 여기서 먼저 거절한다(#155).
        registration.interceptors(new ServiceEndChannelInterceptor(serviceEndPolicy), inboundChannelInterceptor);
    }
}
