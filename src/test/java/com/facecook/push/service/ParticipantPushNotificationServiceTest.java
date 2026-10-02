package com.facecook.push.service;

import com.facecook.chat.redis.ChatPresenceService;
import com.facecook.common.serviceend.ServiceEndPolicy;
import com.facecook.common.serviceend.ServiceEndProperties;
import com.facecook.push.dto.PushNotificationPayload;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ParticipantPushNotificationServiceTest {

    private ChatPresenceService chatPresenceService;
    private PushDeliveryService pushDeliveryService;
    private PushDeliveryMonitor monitor;
    private ParticipantPushNotificationService notificationService;

    @BeforeEach
    void setUp() {
        chatPresenceService = mock(ChatPresenceService.class);
        pushDeliveryService = mock(PushDeliveryService.class);
        monitor = mock(PushDeliveryMonitor.class);
        notificationService = new ParticipantPushNotificationService(
                chatPresenceService, pushDeliveryService, monitor, new ServiceEndPolicy(new ServiceEndProperties(null), Clock.systemUTC()));
    }

    @Test
    void doesNotSendPushAfterServiceEnd() {
        // 종료 시각(한국 시간 10/3 00:00) 1초 뒤
        ServiceEndPolicy ended = new ServiceEndPolicy(
                new ServiceEndProperties(OffsetDateTime.parse("2026-10-03T00:00:00+09:00")),
                Clock.fixed(Instant.parse("2026-10-02T15:00:01Z"), ZoneOffset.UTC));
        notificationService = new ParticipantPushNotificationService(chatPresenceService, pushDeliveryService, monitor, ended);
        when(chatPresenceService.isConnected(2L)).thenReturn(false);

        notificationService.cookReceived(2L);

        verify(pushDeliveryService, never()).sendToUser(any(), any());
    }

    @Test
    void doesNotSendPushWhenReceiverIsConnected() {
        when(chatPresenceService.isConnected(2L)).thenReturn(true);

        notificationService.cookReceived(2L);

        verify(pushDeliveryService, never()).sendToUser(any(), any());
    }

    @Test
    void attemptsPushWhenReceiverIsOffline() {
        when(chatPresenceService.isConnected(2L)).thenReturn(false);

        notificationService.chatMessageReceived(2L, 20L);

        ArgumentCaptor<PushNotificationPayload> payloadCaptor =
                ArgumentCaptor.forClass(PushNotificationPayload.class);
        verify(pushDeliveryService).sendToUser(eq(2L), payloadCaptor.capture());
        assertThat(payloadCaptor.getValue().type()).isEqualTo("CHAT_MESSAGE_RECEIVED");
        assertThat(payloadCaptor.getValue().url()).isEqualTo("/match/20");
    }

    @Test
    void waitsForActiveTransactionCommitBeforeSending() {
        when(chatPresenceService.isConnected(1L)).thenReturn(false);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
        try {
            notificationService.matchCreated(1L, 20L);

            verify(pushDeliveryService, never()).sendToUser(any(), any());

            TransactionSynchronization synchronization =
                    TransactionSynchronizationManager.getSynchronizations().getFirst();
            synchronization.afterCommit();

            verify(pushDeliveryService).sendToUser(eq(1L), any());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    @Test
    void countsExecutorRejectionInsteadOfFailingTheCaller() {
        when(chatPresenceService.isConnected(2L)).thenReturn(false);
        org.mockito.Mockito.doThrow(new org.springframework.core.task.TaskRejectedException("queue full"))
                .when(pushDeliveryService).sendToUser(eq(2L), any());

        org.assertj.core.api.Assertions.assertThatCode(() -> notificationService.cookReceived(2L))
                .doesNotThrowAnyException();

        verify(monitor).recordRejected();
    }
}
