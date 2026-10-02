package com.facecook.push.service;

import com.facecook.chat.redis.ChatPresenceService;
import com.facecook.common.serviceend.ServiceEndPolicy;
import com.facecook.push.dto.PushNotificationPayload;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.RejectedExecutionException;

/**
 * 참가자에게 보내는 웹 푸시 3종(콕 받음·매칭 성사·메시지 도착)의
 * 진입점. 세 메서드 다 같은 규칙을 따른다 — 트랜잭션이 있으면 커밋된
 * 뒤에만 보내고({@link #sendAfterCommit}, 아직 반영 안 된 걸 알리지
 * 않기 위함), 받는 사람이 지금 앱을 켜서 WebSocket이 연결돼 있으면
 * 세 종류 전부 안 보낸다({@link #sendIfOffline},
 * {@link ChatPresenceService#isConnected} — 특정 채팅방이 아니라
 * "앱이 켜져있는지" 기준이다).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ParticipantPushNotificationService {

    private final ChatPresenceService chatPresenceService;
    private final PushDeliveryService pushDeliveryService;
    private final PushDeliveryMonitor monitor;
    private final ServiceEndPolicy serviceEndPolicy;

    /**
     * receiverId에게 "콕이 도착했다" 푸시를 보낸다.
     *
     * <p>전제조건: 없음.</p>
     *
     * <p>부작용: 클래스 doc의 공통 규칙(커밋 후 발송, 접속 중이면 생략)을
     * 따른다. 실패해도 예외를 던지지 않는다.</p>
     *
     * <p>예외 없음.</p>
     *
     * @see #matchCreated(Long, Long)
     * @see #chatMessageReceived(Long, Long)
     */
    public void cookReceived(Long receiverId) {
        sendAfterCommit(receiverId, new PushNotificationPayload(
                "COOK_RECEIVED",
                "새로운 콕이 도착했어요",
                "누군가 회원님을 콕 찔렀어요.",
                "/main"
        ));
    }

    /**
     * userId에게 "matchId 매칭이 성사됐다" 푸시를 보낸다.
     *
     * <p>전제조건: 없음.</p>
     *
     * <p>부작용: 클래스 doc의 공통 규칙과 동일.</p>
     *
     * <p>예외 없음.</p>
     *
     * @see #cookReceived(Long)
     */
    public void matchCreated(Long userId, Long matchId) {
        sendAfterCommit(userId, new PushNotificationPayload(
                "MATCH_CREATED",
                "매칭이 성사됐어요",
                "서로의 마음이 통했어요. 지금 대화를 시작해 보세요.",
                "/match/" + matchId + "/matched"
        ));
    }

    /**
     * receiverId에게 "matchId 상대에게서 새 메시지가 왔다" 푸시를 보낸다.
     *
     * <p>전제조건: 없음.</p>
     *
     * <p>부작용: 클래스 doc의 공통 규칙과 동일 — 특히 receiverId가 지금
     * 채팅에 WebSocket으로 연결돼 있으면(그 채팅방이 아니어도) 이 푸시도
     * 생략된다.</p>
     *
     * <p>예외 없음.</p>
     *
     * @see #cookReceived(Long)
     */
    public void chatMessageReceived(Long receiverId, Long matchId) {
        sendAfterCommit(receiverId, new PushNotificationPayload(
                "CHAT_MESSAGE_RECEIVED",
                "새 메시지가 도착했어요",
                "매칭 상대가 새로운 메시지를 보냈어요.",
                "/match/" + matchId
        ));
    }

    private void sendAfterCommit(Long userId, PushNotificationPayload payload) {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    sendIfOffline(userId, payload);
                }
            });
            return;
        }
        sendIfOffline(userId, payload);
    }

    private void sendIfOffline(Long userId, PushNotificationPayload payload) {
        try {
            // 서비스 종료 뒤에는 보내지 않는다(#155). 알림을 눌러도 종료 화면만 나온다.
            if (serviceEndPolicy.isEnded() || chatPresenceService.isConnected(userId)) {
                return;
            }
            pushDeliveryService.sendToUser(userId, payload);
        } catch (RejectedExecutionException exception) {
            // 푸시 실행기 대기열이 가득 찼다. 몰릴 때는 건마다 남기면 로그가 넘치므로
            // 횟수만 세고 PushDeliveryMonitor가 주기적으로 한 줄로 남긴다.
            monitor.recordRejected();
        } catch (RuntimeException exception) {
            // 푸시는 best-effort 부가 기능이다. 알림 인프라 장애가 이미 성공한
            // 콕/매칭/메시지 처리를 실패로 바꾸지 않도록 경계에서 흡수한다.
            log.warn("참가자 푸시 처리에 실패했습니다. userId={}, type={}", userId, payload.type(), exception);
        }
    }
}
