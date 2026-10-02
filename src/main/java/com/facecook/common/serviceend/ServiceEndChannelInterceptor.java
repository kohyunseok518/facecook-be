package com.facecook.common.serviceend;

import com.facecook.common.exception.ApiException;
import com.facecook.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.lang.NonNull;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;

/**
 * 서비스 종료 시각 이후 STOMP CONNECT·SUBSCRIBE·SEND를 {@code SERVICE_ENDED}로 거절한다(facecook-be#155).
 * 종료 전에 연결해 둔 소켓도 새 메시지는 보낼 수 없다. {@code WebSocketConfig}가 채팅 인증 인터셉터보다 앞에 둔다.
 * 던진 예외는 {@code ChatStompErrorHandler}가 {@code {code, message}} ERROR 프레임으로 바꾼다.
 */
@RequiredArgsConstructor
public class ServiceEndChannelInterceptor implements ChannelInterceptor {

    private final ServiceEndPolicy policy;

    @Override
    public Message<?> preSend(@NonNull Message<?> message, @NonNull MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null) {
            return message;
        }
        StompCommand command = accessor.getCommand();
        boolean blocked = command == StompCommand.CONNECT
                || command == StompCommand.SUBSCRIBE
                || command == StompCommand.SEND;
        if (blocked && policy.isEnded()) {
            throw new ApiException(ErrorCode.SERVICE_ENDED);
        }
        return message;
    }
}
