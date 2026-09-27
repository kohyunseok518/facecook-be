package com.facecook.cook.service;

import com.facecook.auth.entity.User;
import com.facecook.common.exception.ApiException;
import com.facecook.common.exception.ErrorCode;
import com.facecook.cook.dto.SendCookRequest;
import com.facecook.cook.entity.Cook;
import com.facecook.cook.entity.CookStatus;
import com.facecook.match.entity.MatchInfo;
import com.facecook.cook.repository.CookParticipants;
import com.facecook.cook.repository.CookRepository;
import com.facecook.cook.repository.CookUserRepository;
import com.facecook.match.repository.MatchInfoRepository;
import com.facecook.profile.dto.ProfileResponse;
import com.facecook.profile.entity.Profile;
import com.facecook.profile.repository.ProfileRepository;
import com.facecook.profile.service.ProfileActivityLookup;
import com.facecook.push.service.ParticipantPushNotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CookServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-30T03:00:00Z");
    private static final LocalDateTime EVENT_NOW = LocalDateTime.of(2026, 9, 30, 12, 0);

    @Mock
    private CookRepository cookRepository;

    @Mock
    private MatchInfoRepository matchInfoRepository;

    @Mock
    private CookUserRepository cookUserRepository;

    @Mock
    private EventLimitLock eventLimitLock;

    @Mock
    private ProfileRepository profileRepository;

    @Mock
    private ProfileActivityLookup activityLookup;

    @Mock
    private ParticipantPushNotificationService pushNotificationService;

    private CookService cookService;

    @BeforeEach
    void setUp() {
        // 활동 정보(lastActiveAt/isActive)는 이 테스트의 관심사가 아니라, 실제
        // ProfileResponse.from을 그대로 위임해 null/false로 채운다.
        org.mockito.Mockito.lenient().when(activityLookup.toResponse(any(Profile.class)))
                .thenAnswer(invocation -> ProfileResponse.from(invocation.getArgument(0), null, false));
        org.mockito.Mockito.lenient().when(activityLookup.toResponses(any()))
                .thenAnswer(invocation -> {
                    List<Profile> profiles = invocation.getArgument(0);
                    return profiles.stream()
                            .map(profile -> ProfileResponse.from(profile, null, false))
                            .toList();
                });

        cookService = new CookService(
                cookRepository,
                matchInfoRepository,
                cookUserRepository,
                eventLimitLock,
                profileRepository,
                activityLookup,
                pushNotificationService,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void rejectsCookToSelf() {
        assertErrorCode(() -> cookService.send(1L, new SendCookRequest(1L)), ErrorCode.SELF);

        verify(cookUserRepository, never()).findAllByIdForUpdate(anyCollection());
    }

    @Test
    void rejectsMissingReceiver() {
        when(cookUserRepository.findAllByIdForUpdate(List.of(1L, 2L))).thenReturn(List.of(user(1L)));

        assertErrorCode(() -> cookService.send(1L, new SendCookRequest(2L)), ErrorCode.NOT_FOUND);
    }

    @Test
    void rejectsAlreadyMatchedReceiverBeforeDuplicateCheck() {
        givenLockedUsers(1L, 2L);
        when(matchInfoRepository.existsBetween(1L, 2L)).thenReturn(true);

        assertErrorCode(() -> cookService.send(1L, new SendCookRequest(2L)), ErrorCode.ALREADY_MATCHED);

        verify(cookRepository, never()).existsBySenderIdAndReceiverId(any(), any());
    }

    @Test
    void rejectsDuplicateCook() {
        givenLockedUsers(1L, 2L);
        when(cookRepository.existsBySenderIdAndReceiverId(1L, 2L)).thenReturn(true);

        assertErrorCode(() -> cookService.send(1L, new SendCookRequest(2L)), ErrorCode.DUPLICATE);

        verify(cookRepository, never()).saveAndFlush(any());
    }

    @Test
    void rejectsEleventhCookOfTheDay() {
        givenLockedUsers(1L, 2L);
        when(cookRepository.countBySenderIdAndSentAtGreaterThanEqualAndSentAtLessThan(
                1L,
                LocalDateTime.of(2026, 9, 30, 0, 0),
                LocalDateTime.of(2026, 10, 1, 0, 0)
        )).thenReturn(10L);

        assertErrorCode(() -> cookService.send(1L, new SendCookRequest(2L)), ErrorCode.DAILY_LIMIT);

        verify(cookRepository, never()).saveAndFlush(any());
    }

    @Test
    void allowsTenthCookOfTheDay() {
        givenLockedUsers(1L, 2L);
        when(cookRepository.countBySenderIdAndSentAtGreaterThanEqualAndSentAtLessThan(
                1L,
                LocalDateTime.of(2026, 9, 30, 0, 0),
                LocalDateTime.of(2026, 10, 1, 0, 0)
        )).thenReturn(9L);
        when(cookRepository.saveAndFlush(any(Cook.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var response = cookService.send(1L, new SendCookRequest(2L));

        assertThat(response.status()).isEqualTo("pending");
        verify(cookRepository).saveAndFlush(any(Cook.class));
        verify(pushNotificationService).cookReceived(2L);
    }

    @Test
    void rejectsCookWhenEventWideDailyLimitReached() {
        givenLockedUsers(1L, 2L);
        when(cookRepository.countBySentAtGreaterThanEqualAndSentAtLessThan(
                LocalDateTime.of(2026, 9, 30, 0, 0),
                LocalDateTime.of(2026, 10, 1, 0, 0)
        )).thenReturn(3_000L);

        assertErrorCode(() -> cookService.send(1L, new SendCookRequest(2L)), ErrorCode.EVENT_LIMIT);

        verify(cookRepository, never()).saveAndFlush(any());
    }

    @Test
    void personalDailyLimitIsCheckedBeforeEventWideLimit() {
        givenLockedUsers(1L, 2L);
        when(cookRepository.countBySenderIdAndSentAtGreaterThanEqualAndSentAtLessThan(eq(1L), any(), any()))
                .thenReturn(10L);

        assertErrorCode(() -> cookService.send(1L, new SendCookRequest(2L)), ErrorCode.DAILY_LIMIT);

        verify(cookRepository, never()).countBySentAtGreaterThanEqualAndSentAtLessThan(any(), any());
        verify(cookRepository, never()).saveAndFlush(any());
    }

    @Test
    void duplicateIsCheckedBeforeAnyLimitCount() {
        givenLockedUsers(1L, 2L);
        when(cookRepository.existsBySenderIdAndReceiverId(1L, 2L)).thenReturn(true);

        assertErrorCode(() -> cookService.send(1L, new SendCookRequest(2L)), ErrorCode.DUPLICATE);

        verify(cookRepository, never())
                .countBySenderIdAndSentAtGreaterThanEqualAndSentAtLessThan(any(), any(), any());
        verify(cookRepository, never()).countBySentAtGreaterThanEqualAndSentAtLessThan(any(), any());
    }

    @Test
    void allowsCookJustBelowEventWideDailyLimit() {
        givenLockedUsers(1L, 2L);
        when(cookRepository.countBySentAtGreaterThanEqualAndSentAtLessThan(
                LocalDateTime.of(2026, 9, 30, 0, 0),
                LocalDateTime.of(2026, 10, 1, 0, 0)
        )).thenReturn(2_999L);
        when(cookRepository.saveAndFlush(any(Cook.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var response = cookService.send(1L, new SendCookRequest(2L));

        assertThat(response.status()).isEqualTo("pending");
    }

    @Test
    void eventWideDailyLimitGrowsWithEventDay() {
        Clock secondDay = Clock.fixed(Instant.parse("2026-10-01T03:00:00Z"), ZoneOffset.UTC);
        CookService secondDayService = new CookService(
                cookRepository, matchInfoRepository, cookUserRepository, eventLimitLock, profileRepository,
                activityLookup, pushNotificationService, secondDay
        );
        givenLockedUsers(1L, 2L);
        when(cookRepository.countBySentAtGreaterThanEqualAndSentAtLessThan(
                LocalDateTime.of(2026, 10, 1, 0, 0),
                LocalDateTime.of(2026, 10, 2, 0, 0)
        )).thenReturn(6_000L);

        assertErrorCode(() -> secondDayService.send(1L, new SendCookRequest(2L)), ErrorCode.EVENT_LIMIT);
    }

    @Test
    void eventWideDailyLimitDoesNotApplyOutsideEventWindow() {
        Clock beforeEvent = Clock.fixed(Instant.parse("2026-01-01T03:00:00Z"), ZoneOffset.UTC);
        CookService devService = new CookService(
                cookRepository, matchInfoRepository, cookUserRepository, eventLimitLock, profileRepository,
                activityLookup, pushNotificationService, beforeEvent
        );
        givenLockedUsers(1L, 2L);
        when(cookRepository.saveAndFlush(any(Cook.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var response = devService.send(1L, new SendCookRequest(2L));

        assertThat(response.status()).isEqualTo("pending");
        verify(cookRepository, never()).countBySentAtGreaterThanEqualAndSentAtLessThan(any(), any());
        verify(eventLimitLock, never()).acquire();
    }

    @Test
    void sendLocksEventLimitOnlyAfterUserLockAndValidation() {
        givenLockedUsers(1L, 2L);
        when(cookRepository.saveAndFlush(any(Cook.class))).thenAnswer(invocation -> invocation.getArgument(0));

        cookService.send(1L, new SendCookRequest(2L));

        InOrder order = inOrder(cookUserRepository, eventLimitLock, matchInfoRepository, cookRepository);
        order.verify(cookUserRepository).findAllByIdForUpdate(List.of(1L, 2L));
        order.verify(matchInfoRepository).existsBetween(1L, 2L);
        order.verify(cookRepository).countBySenderIdAndSentAtGreaterThanEqualAndSentAtLessThan(eq(1L), any(), any());
        order.verify(eventLimitLock).acquire();
        order.verify(cookRepository).countBySentAtGreaterThanEqualAndSentAtLessThan(any(), any());
        order.verify(cookRepository).saveAndFlush(any(Cook.class));
    }

    @Test
    void sendDoesNotTakeEventLimitLockWhenPersonalDailyLimitIsReached() {
        givenLockedUsers(1L, 2L);
        when(cookRepository.countBySenderIdAndSentAtGreaterThanEqualAndSentAtLessThan(eq(1L), any(), any()))
                .thenReturn((long) CookService.DAILY_LIMIT);

        assertErrorCode(() -> cookService.send(1L, new SendCookRequest(2L)), ErrorCode.DAILY_LIMIT);

        verify(eventLimitLock, never()).acquire();
        verify(cookRepository, never()).countBySentAtGreaterThanEqualAndSentAtLessThan(any(), any());
    }

    @Test
    void sendDoesNotTakeEventLimitLockWhenReceiverIsMissingOrSelf() {
        when(cookUserRepository.findAllByIdForUpdate(List.of(1L, 2L))).thenReturn(List.of(user(1L)));

        assertErrorCode(() -> cookService.send(1L, new SendCookRequest(2L)), ErrorCode.NOT_FOUND);
        assertErrorCode(() -> cookService.send(1L, new SendCookRequest(1L)), ErrorCode.SELF);

        verify(eventLimitLock, never()).acquire();
    }

    @Test
    void countsDailyUsageBetweenKoreaMidnights() {
        givenLockedUsers(1L, 2L);
        when(cookRepository.saveAndFlush(any(Cook.class))).thenAnswer(invocation -> invocation.getArgument(0));

        cookService.send(1L, new SendCookRequest(2L));

        verify(cookRepository).countBySenderIdAndSentAtGreaterThanEqualAndSentAtLessThan(
                1L,
                LocalDateTime.of(2026, 9, 30, 0, 0),
                LocalDateTime.of(2026, 10, 1, 0, 0)
        );
    }

    @Test
    void mutualCookCreatesMatchAndConnectsBothCooks() {
        givenLockedUsers(1L, 2L);
        Cook reverse = cook(10L, 2L, 1L, EVENT_NOW.minusMinutes(59));
        when(cookRepository.findBySenderIdAndReceiverId(2L, 1L)).thenReturn(Optional.of(reverse));
        when(cookRepository.saveAndFlush(any(Cook.class))).thenAnswer(invocation -> {
            Cook saved = invocation.getArgument(0);
            setField(saved, "id", 11L);
            return saved;
        });
        when(matchInfoRepository.saveAndFlush(any(MatchInfo.class))).thenAnswer(invocation -> {
            MatchInfo saved = invocation.getArgument(0);
            setField(saved, "id", 20L);
            return saved;
        });

        var response = cookService.send(1L, new SendCookRequest(2L));

        assertThat(response.matched()).isTrue();
        assertThat(response.matchId()).isEqualTo(20L);
        assertThat(response.status()).isEqualTo("matched");
        assertThat(reverse.getStatus()).isEqualTo(CookStatus.MATCHED);
        assertThat(reverse.getMatchId()).isEqualTo(20L);

        ArgumentCaptor<MatchInfo> matchCaptor = ArgumentCaptor.forClass(MatchInfo.class);
        verify(matchInfoRepository).saveAndFlush(matchCaptor.capture());
        assertThat(matchCaptor.getValue().getUserAId()).isEqualTo(1L);
        assertThat(matchCaptor.getValue().getUserBId()).isEqualTo(2L);
        assertThat(matchCaptor.getValue().getMatchedAt()).isEqualTo(EVENT_NOW);
        verify(pushNotificationService).matchCreated(1L, 20L);
        verify(pushNotificationService).matchCreated(2L, 20L);
        verify(pushNotificationService, never()).cookReceived(any());
    }

    @Test
    void reverseCookAfterOneHourStillMatches() {
        givenLockedUsers(1L, 2L);
        Cook reverse = cook(10L, 2L, 1L, EVENT_NOW.minusHours(1));
        when(cookRepository.findBySenderIdAndReceiverId(2L, 1L)).thenReturn(Optional.of(reverse));
        when(cookRepository.saveAndFlush(any(Cook.class))).thenAnswer(invocation -> {
            Cook saved = invocation.getArgument(0);
            setField(saved, "id", 11L);
            return saved;
        });
        when(matchInfoRepository.saveAndFlush(any(MatchInfo.class))).thenAnswer(invocation -> {
            MatchInfo saved = invocation.getArgument(0);
            setField(saved, "id", 20L);
            return saved;
        });

        var response = cookService.send(1L, new SendCookRequest(2L));

        assertThat(response.matched()).isTrue();
        assertThat(response.matchId()).isEqualTo(20L);
        assertThat(response.status()).isEqualTo("matched");
        assertThat(reverse.getStatus()).isEqualTo(CookStatus.MATCHED);
        assertThat(reverse.getMatchId()).isEqualTo(20L);
        verify(matchInfoRepository).saveAndFlush(any(MatchInfo.class));
    }

    @Test
    void cookLookupKeepsOldPendingCooksInsteadOfExpiringThem() {
        Cook oldSent = cook(10L, 1L, 2L, EVENT_NOW.minusHours(2));
        Cook recentReceived = cook(11L, 3L, 1L, EVENT_NOW.minusMinutes(30));
        when(cookRepository.findAllBySenderIdOrReceiverIdOrderBySentAtDesc(1L, 1L))
                .thenReturn(List.of(recentReceived, oldSent));
        when(profileRepository.findAllById(anyCollection()))
                .thenReturn(List.of(profile(2L, "two"), profile(3L, "three")));
        when(cookRepository.countBySenderIdAndSentAtGreaterThanEqualAndSentAtLessThan(eq(1L), any(), any()))
                .thenReturn(4L);
        when(cookRepository.countBySenderId(1L)).thenReturn(7L);

        var response = cookService.getCooks(1L);

        assertThat(oldSent.getStatus()).isEqualTo(CookStatus.PENDING);
        assertThat(recentReceived.getStatus()).isEqualTo(CookStatus.PENDING);
        assertThat(response.sent()).singleElement().satisfies(item -> {
            assertThat(item.userId()).isEqualTo(2L);
            assertThat(item.profile().nickname()).isEqualTo("two");
            assertThat(item.status()).isEqualTo("pending");
        });
        assertThat(response.received()).singleElement().satisfies(item ->
                assertThat(item.userId()).isEqualTo(3L)
        );
        assertThat(response.usage().todayUsed()).isEqualTo(4L);
        assertThat(response.usage().dailyLimit()).isEqualTo(10);
        assertThat(response.usage().totalUsed()).isEqualTo(7L);
    }

    @Test
    void cancelRejectsMissingCook() {
        when(cookRepository.findParticipantsById(10L)).thenReturn(Optional.empty());

        assertErrorCode(() -> cookService.cancel(1L, 10L), ErrorCode.NOT_FOUND);

        verify(cookUserRepository, never()).findAllByIdForUpdate(anyCollection());
    }

    @Test
    void cancelRejectsNonSender() {
        Cook cook = cook(10L, 1L, 2L, EVENT_NOW.minusMinutes(5));
        givenLockableCook(cook);

        assertErrorCode(() -> cookService.cancel(2L, 10L), ErrorCode.FORBIDDEN);

        assertThat(cook.getStatus()).isEqualTo(CookStatus.PENDING);
    }

    @Test
    void cancelRejectsAlreadyMatchedCook() {
        Cook cook = cook(10L, 1L, 2L, EVENT_NOW.minusMinutes(5));
        cook.match(20L);
        givenLockableCook(cook);

        assertErrorCode(() -> cookService.cancel(1L, 10L), ErrorCode.ALREADY_MATCHED);
    }

    @Test
    void cancelRejectsAlreadyExpiredCook() {
        Cook cook = cook(10L, 1L, 2L, EVENT_NOW.minusHours(2));
        setField(cook, "status", CookStatus.EXPIRED);
        givenLockableCook(cook);

        assertErrorCode(() -> cookService.cancel(1L, 10L), ErrorCode.ALREADY_EXPIRED);

        assertThat(cook.getStatus()).isEqualTo(CookStatus.EXPIRED);
    }

    @Test
    void cancelRejectsRejectedCookAndKeepsRejection() {
        Cook cook = cook(10L, 1L, 2L, EVENT_NOW.minusMinutes(5));
        cook.reject(2L);
        givenLockableCook(cook);

        assertErrorCode(() -> cookService.cancel(1L, 10L), ErrorCode.ALREADY_REJECTED);

        assertThat(cook.getStatus()).isEqualTo(CookStatus.REJECTED);
    }

    @Test
    void cancelAllowsOldPendingCookWithoutHourExpiry() {
        Cook cook = cook(10L, 1L, 2L, EVENT_NOW.minusHours(2));
        givenLockableCook(cook);

        cookService.cancel(1L, 10L);

        assertThat(cook.getStatus()).isEqualTo(CookStatus.CANCELLED);
    }

    @Test
    void cancelMarksPendingCookAsCancelled() {
        Cook cook = cook(10L, 1L, 2L, EVENT_NOW.minusMinutes(5));
        givenLockableCook(cook);

        cookService.cancel(1L, 10L);

        assertThat(cook.getStatus()).isEqualTo(CookStatus.CANCELLED);
    }

    @Test
    void cancelLocksBothUsersBeforeReadingCookForUpdate() {
        Cook cook = cook(10L, 2L, 1L, EVENT_NOW.minusMinutes(5));
        givenLockableCook(cook);

        cookService.cancel(2L, 10L);

        InOrder order = inOrder(cookRepository, cookUserRepository);
        order.verify(cookRepository).findParticipantsById(10L);
        order.verify(cookUserRepository).findAllByIdForUpdate(List.of(1L, 2L));
        order.verify(cookRepository).findByIdForUpdate(10L);
        verify(cookRepository, never()).findById(any());
    }

    @Test
    void rejectRejectsMissingCook() {
        when(cookRepository.findParticipantsById(10L)).thenReturn(Optional.empty());

        assertErrorCode(() -> cookService.reject(2L, 10L), ErrorCode.NOT_FOUND);

        verify(cookUserRepository, never()).findAllByIdForUpdate(anyCollection());
    }

    @Test
    void rejectRejectsNonReceiver() {
        Cook cook = cook(10L, 1L, 2L, EVENT_NOW.minusMinutes(5));
        givenLockableCook(cook);

        assertErrorCode(() -> cookService.reject(1L, 10L), ErrorCode.FORBIDDEN);

        assertThat(cook.getStatus()).isEqualTo(CookStatus.PENDING);
    }

    @Test
    void rejectMarksPendingCookAsRejectedWithoutPushOrUsageRefund() {
        Cook cook = cook(10L, 1L, 2L, EVENT_NOW.minusMinutes(5));
        givenLockableCook(cook);

        cookService.reject(2L, 10L);

        assertThat(cook.getStatus()).isEqualTo(CookStatus.REJECTED);
        verifyNoInteractions(pushNotificationService);
        verify(cookRepository, never()).countBySenderIdAndSentAtGreaterThanEqualAndSentAtLessThan(any(), any(), any());
    }

    @Test
    void rejectingAlreadyRejectedCookSucceedsAndKeepsRejection() {
        Cook cook = cook(10L, 1L, 2L, EVENT_NOW.minusMinutes(5));
        cook.reject(2L);
        givenLockableCook(cook);

        cookService.reject(2L, 10L);

        assertThat(cook.getStatus()).isEqualTo(CookStatus.REJECTED);
    }

    @Test
    void rejectRejectsAlreadyMatchedCook() {
        Cook cook = cook(10L, 1L, 2L, EVENT_NOW.minusMinutes(5));
        cook.match(20L);
        givenLockableCook(cook);

        assertErrorCode(() -> cookService.reject(2L, 10L), ErrorCode.ALREADY_MATCHED);

        assertThat(cook.getStatus()).isEqualTo(CookStatus.MATCHED);
    }

    @Test
    void rejectTreatsCancelledCookAsNotFound() {
        Cook cook = cook(10L, 1L, 2L, EVENT_NOW.minusMinutes(5));
        cook.cancel(1L);
        givenLockableCook(cook);

        assertErrorCode(() -> cookService.reject(2L, 10L), ErrorCode.NOT_FOUND);

        assertThat(cook.getStatus()).isEqualTo(CookStatus.CANCELLED);
    }

    @Test
    void rejectRejectsAlreadyExpiredCook() {
        Cook cook = cook(10L, 1L, 2L, EVENT_NOW.minusHours(2));
        setField(cook, "status", CookStatus.EXPIRED);
        givenLockableCook(cook);

        assertErrorCode(() -> cookService.reject(2L, 10L), ErrorCode.ALREADY_EXPIRED);

        assertThat(cook.getStatus()).isEqualTo(CookStatus.EXPIRED);
    }

    @Test
    void rejectLocksBothUsersBeforeReadingCookForUpdate() {
        Cook cook = cook(10L, 2L, 1L, EVENT_NOW.minusMinutes(5));
        givenLockableCook(cook);

        cookService.reject(1L, 10L);

        InOrder order = inOrder(cookRepository, cookUserRepository);
        order.verify(cookRepository).findParticipantsById(10L);
        order.verify(cookUserRepository).findAllByIdForUpdate(List.of(1L, 2L));
        order.verify(cookRepository).findByIdForUpdate(10L);
        verify(cookRepository, never()).findById(any());
    }

    @Test
    void sendRejectsPersonWhoseCookIRejected() {
        givenLockedUsers(2L, 1L);
        Cook rejected = cook(10L, 1L, 2L, EVENT_NOW.minusMinutes(5));
        rejected.reject(2L);
        when(cookRepository.findBySenderIdAndReceiverId(1L, 2L)).thenReturn(Optional.of(rejected));

        assertThatThrownBy(() -> cookService.send(2L, new SendCookRequest(1L)))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.ALREADY_REJECTED);
                    assertThat(exception.getMessage()).isEqualTo("이미 거절한 상대예요.");
                });

        verify(cookRepository, never()).saveAndFlush(any());
        verifyNoInteractions(pushNotificationService);
        verify(matchInfoRepository, never()).saveAndFlush(any());
    }

    @Test
    void sendChecksMatchBeforeRejectedRelationship() {
        givenLockedUsers(2L, 1L);
        when(matchInfoRepository.existsBetween(2L, 1L)).thenReturn(true);

        assertErrorCode(() -> cookService.send(2L, new SendCookRequest(1L)), ErrorCode.ALREADY_MATCHED);

        verify(cookRepository, never()).findBySenderIdAndReceiverId(any(), any());
    }

    @Test
    void sendChecksRejectedRelationshipBeforeDuplicate() {
        givenLockedUsers(1L, 2L);
        Cook rejectedByOtherSide = cook(11L, 2L, 1L, EVENT_NOW.minusMinutes(5));
        rejectedByOtherSide.reject(1L);
        when(cookRepository.findBySenderIdAndReceiverId(2L, 1L)).thenReturn(Optional.of(rejectedByOtherSide));

        assertErrorCode(() -> cookService.send(1L, new SendCookRequest(2L)), ErrorCode.ALREADY_REJECTED);

        verify(cookRepository, never()).existsBySenderIdAndReceiverId(any(), any());
    }

    @Test
    void resendingToPersonWhoRejectedMeIsDuplicate() {
        givenLockedUsers(1L, 2L);
        when(cookRepository.existsBySenderIdAndReceiverId(1L, 2L)).thenReturn(true);

        assertErrorCode(() -> cookService.send(1L, new SendCookRequest(2L)), ErrorCode.DUPLICATE);

        verify(cookRepository, never()).saveAndFlush(any());
    }

    @Test
    void cancelledReverseCookDoesNotMatchAndSendsAsNewPendingCook() {
        givenLockedUsers(1L, 2L);
        Cook cancelledReverse = cook(10L, 2L, 1L, EVENT_NOW.minusMinutes(5));
        cancelledReverse.cancel(2L);
        when(cookRepository.findBySenderIdAndReceiverId(2L, 1L)).thenReturn(Optional.of(cancelledReverse));
        when(cookRepository.saveAndFlush(any(Cook.class))).thenAnswer(invocation -> {
            Cook saved = invocation.getArgument(0);
            setField(saved, "id", 11L);
            return saved;
        });

        var response = cookService.send(1L, new SendCookRequest(2L));

        assertThat(response.matched()).isFalse();
        assertThat(response.status()).isEqualTo("pending");
        verify(matchInfoRepository, never()).saveAndFlush(any());
        verify(pushNotificationService).cookReceived(2L);
    }

    @Test
    void rejectedCookIsHiddenFromReceiverButListedAsRejectedForSender() {
        Cook rejected = cook(10L, 1L, 2L, EVENT_NOW.minusMinutes(30));
        rejected.reject(2L);
        when(cookRepository.findAllBySenderIdOrReceiverIdOrderBySentAtDesc(1L, 1L)).thenReturn(List.of(rejected));
        when(cookRepository.findAllBySenderIdOrReceiverIdOrderBySentAtDesc(2L, 2L)).thenReturn(List.of(rejected));
        when(profileRepository.findAllById(anyCollection()))
                .thenReturn(List.of(profile(1L, "one"), profile(2L, "two")));

        var forSender = cookService.getCooks(1L);
        var forReceiver = cookService.getCooks(2L);

        assertThat(forSender.sent()).singleElement().satisfies(item -> {
            assertThat(item.userId()).isEqualTo(2L);
            assertThat(item.status()).isEqualTo("rejected");
        });
        assertThat(forSender.received()).isEmpty();
        assertThat(forReceiver.sent()).isEmpty();
        assertThat(forReceiver.received()).isEmpty();
    }

    @Test
    void cancelledCooksAreExcludedFromBothSentAndReceivedLists() {
        Cook cancelledSent = cook(10L, 1L, 2L, EVENT_NOW.minusMinutes(30));
        cancelledSent.cancel(1L);
        Cook activeReceived = cook(11L, 3L, 1L, EVENT_NOW.minusMinutes(10));
        when(cookRepository.findAllBySenderIdOrReceiverIdOrderBySentAtDesc(1L, 1L))
                .thenReturn(List.of(activeReceived, cancelledSent));
        when(profileRepository.findAllById(anyCollection())).thenReturn(List.of(profile(3L, "three")));
        when(cookRepository.countBySenderIdAndSentAtGreaterThanEqualAndSentAtLessThan(eq(1L), any(), any()))
                .thenReturn(0L);
        when(cookRepository.countBySenderId(1L)).thenReturn(1L);

        var response = cookService.getCooks(1L);

        assertThat(response.sent()).isEmpty();
        assertThat(response.received()).singleElement().satisfies(item ->
                assertThat(item.userId()).isEqualTo(3L)
        );
    }

    /** 취소·거절이 따르는 조회 순서(쌍 조회 → 사용자 잠금 → 콕 잠금 조회)에 맞춰 콕을 준비한다. */
    private void givenLockableCook(Cook cook) {
        Long senderId = cook.getSenderId();
        Long receiverId = cook.getReceiverId();
        when(cookRepository.findParticipantsById(cook.getId())).thenReturn(Optional.of(new CookParticipants() {
            @Override
            public Long getSenderId() {
                return senderId;
            }

            @Override
            public Long getReceiverId() {
                return receiverId;
            }
        }));
        givenLockedUsers(senderId, receiverId);
        when(cookRepository.findByIdForUpdate(cook.getId())).thenReturn(Optional.of(cook));
    }

    private void givenLockedUsers(Long firstId, Long secondId) {
        List<Long> ids = List.of(Math.min(firstId, secondId), Math.max(firstId, secondId));
        when(cookUserRepository.findAllByIdForUpdate(ids)).thenReturn(List.of(user(firstId), user(secondId)));
    }

    private static User user(Long id) {
        User user = User.createParticipant("user" + id + "@example.com", EVENT_NOW);
        setField(user, "id", id);
        return user;
    }

    private static Cook cook(Long id, Long senderId, Long receiverId, LocalDateTime sentAt) {
        Cook cook = Cook.pending(senderId, receiverId, sentAt);
        setField(cook, "id", id);
        return cook;
    }

    private static MatchInfo match(Long id, Long firstUserId, Long secondUserId, LocalDateTime matchedAt) {
        MatchInfo matchInfo = MatchInfo.create(firstUserId, secondUserId, matchedAt);
        setField(matchInfo, "id", id);
        return matchInfo;
    }

    private static Profile profile(Long userId, String nickname) {
        return Profile.create(userId, new Profile.NewProfile(
                nickname,
                "female",
                21,
                "ENFP",
                "요리",
                "A",
                "컴퓨터공학과",
                "2학년",
                "안녕하세요",
                "다정한 사람",
                "https://example.com/photo.jpg"
        ));
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void assertErrorCode(Runnable action, ErrorCode expected) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(ApiException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(expected)
                );
    }
}
