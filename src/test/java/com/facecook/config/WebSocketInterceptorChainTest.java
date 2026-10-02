package com.facecook.config;

import com.facecook.auth.entity.User;
import com.facecook.auth.repository.UserRepository;
import com.facecook.chat.service.ChatAuthorizationService;
import com.facecook.chat.websocket.ChatHandshakeHandler;
import com.facecook.chat.websocket.ChatHandshakeInterceptor;
import com.facecook.chat.websocket.ChatInboundChannelInterceptor;
import com.facecook.chat.websocket.ChatPrincipal;
import com.facecook.chat.websocket.ChatSessionAttributes;
import com.facecook.chat.websocket.ChatStompErrorHandler;
import com.facecook.common.session.SessionToken;
import com.facecook.common.session.SessionAuthenticator;
import com.facecook.common.session.SessionTokenSigner;
import com.facecook.common.exception.ApiException;
import com.facecook.common.exception.ErrorCode;
import com.facecook.common.serviceend.ServiceEndPolicy;
import com.facecook.common.serviceend.ServiceEndProperties;
import com.facecook.common.websocket.StompSubscriptionPolicy;
import com.facecook.mission.service.MissionAuthorizationService;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageBuilder;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WebSocketConfig에 실제로 등록된 인바운드 인터셉터 체인을 그대로 통과시켜서,
 * 구독 허용 목록이 인증 뒤에 적용되는지 확인한다.
 */
class WebSocketInterceptorChainTest {

    private final SessionTokenSigner signer = mock(SessionTokenSigner.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final ChatAuthorizationService chatAuthorizationService = mock(ChatAuthorizationService.class);
    private final MissionAuthorizationService missionAuthorizationService = mock(MissionAuthorizationService.class);
    private ServiceEndPolicy serviceEndPolicy = new ServiceEndPolicy(new ServiceEndProperties(null), Clock.systemUTC());

    @Test
    void rejectsSendAfterServiceEndBeforeAuthentication() {
        serviceEndPolicy = new ServiceEndPolicy(
                new ServiceEndProperties(OffsetDateTime.parse("2026-10-03T00:00:00+09:00")),
                Clock.fixed(Instant.parse("2026-10-02T15:00:00Z"), ZoneOffset.UTC));
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
        accessor.setDestination("/app/chat/20/send");
        accessor.setSessionAttributes(new HashMap<>(Map.of(ChatSessionAttributes.SESSION_TOKEN, "signed-token")));
        accessor.setLeaveMutable(true);

        assertThatThrownBy(() -> runThroughConfiguredChain(accessor))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.SERVICE_ENDED));
        verify(userRepository, never()).findById(1L);
    }

    @Test
    void authenticatesThenAuthorizesMissionSubscriptionInConfiguredOrder() {
        StompHeaderAccessor accessor = subscribe("/topic/mission/20");

        runThroughConfiguredChain(accessor);

        assertThat(accessor.getUser()).isInstanceOf(ChatPrincipal.class);
        verify(missionAuthorizationService).requireParticipant(20L, 1L);
    }

    @Test
    void rejectsSubscriptionOutsideAllowListThroughConfiguredChain() {
        StompHeaderAccessor accessor = subscribe("/topic/unknown");

        assertThatThrownBy(() -> runThroughConfiguredChain(accessor))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
    }

    private void runThroughConfiguredChain(StompHeaderAccessor accessor) {
        ChatInboundChannelInterceptor chatInterceptor = new ChatInboundChannelInterceptor(
                new SessionAuthenticator(signer, userRepository),
                chatAuthorizationService,
                new StompSubscriptionPolicy(chatAuthorizationService, missionAuthorizationService)
        );
        WebSocketConfig config = new WebSocketConfig(
                mock(CorsProperties.class),
                mock(ChatHandshakeInterceptor.class),
                mock(ChatHandshakeHandler.class),
                chatInterceptor,
                mock(ChatStompErrorHandler.class),
                serviceEndPolicy
        );
        TestChannelRegistration registration = new TestChannelRegistration();
        config.configureClientInboundChannel(registration);

        when(signer.verify("signed-token"))
                .thenReturn(Optional.of(new SessionToken(1L, 9999999999L)));
        when(userRepository.findById(1L)).thenReturn(Optional.of(user(1L)));
        Message<?> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
        MessageChannel channel = mock(MessageChannel.class);
        for (ChannelInterceptor interceptor : registration.registeredInterceptors()) {
            message = interceptor.preSend(message, channel);
        }
    }

    private static StompHeaderAccessor subscribe(String destination) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setDestination(destination);
        Map<String, Object> sessionAttributes = new HashMap<>();
        sessionAttributes.put(ChatSessionAttributes.SESSION_TOKEN, "signed-token");
        accessor.setSessionAttributes(sessionAttributes);
        accessor.setLeaveMutable(true);
        return accessor;
    }

    private static User user(Long id) {
        User user = User.createParticipant(
                "mission@example.com",
                LocalDateTime.of(2026, 9, 30, 12, 0)
        );
        try {
            Field field = User.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(user, id);
            return user;
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static final class TestChannelRegistration extends ChannelRegistration {
        List<ChannelInterceptor> registeredInterceptors() {
            return getInterceptors();
        }
    }
}
