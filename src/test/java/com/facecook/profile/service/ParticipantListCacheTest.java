package com.facecook.profile.service;

import com.facecook.profile.dto.ProfileResponse;
import com.facecook.profile.entity.Profile;
import com.facecook.profile.repository.ProfileRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ParticipantListCacheTest {

    private final ProfileRepository profileRepository = mock(ProfileRepository.class);
    private final ProfileActivityLookup activityLookup = mock(ProfileActivityLookup.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private final MutableClock clock = new MutableClock(Instant.parse("2026-09-30T03:00:00Z"));

    @BeforeEach
    void setUp() {
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(profileRepository.findAllVisibleParticipants())
                .thenReturn(List.of(profile(1L), profile(2L), profile(3L)));
        when(activityLookup.toResponses(any())).thenAnswer(invocation -> {
            List<Profile> profiles = invocation.getArgument(0);
            return profiles.stream().map(profile -> ProfileResponse.from(profile, null, false)).toList();
        });
    }

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void excludesTheRequesterAndKeepsUserIdOrder() {
        ParticipantListCache cache = cache(10);

        assertThat(cache.getAllExcept(2L)).extracting(ProfileResponse::userId).containsExactly(1L, 3L);
    }

    @Test
    void reusesTheListWithinTheTtlWithoutTouchingTheDatabase() {
        ParticipantListCache cache = cache(10);

        cache.getAllExcept(1L);
        clock.advance(Duration.ofSeconds(9));
        cache.getAllExcept(2L);
        cache.getAllExcept(3L);

        verify(profileRepository, times(1)).findAllVisibleParticipants();
        verify(transactionManager, times(1)).getTransaction(any());
    }

    @Test
    void reloadsOnceTheTtlHasPassed() {
        ParticipantListCache cache = cache(10);

        cache.getAllExcept(1L);
        clock.advance(Duration.ofSeconds(10));
        cache.getAllExcept(1L);

        verify(profileRepository, times(2)).findAllVisibleParticipants();
    }

    @Test
    void zeroTtlMeansReadEveryTime() {
        ParticipantListCache cache = cache(0);

        cache.getAllExcept(1L);
        cache.getAllExcept(1L);

        verify(profileRepository, times(2)).findAllVisibleParticipants();
    }

    @Test
    void evictOutsideATransactionDropsTheListImmediately() {
        ParticipantListCache cache = cache(10);
        cache.getAllExcept(1L);

        cache.evictAfterCommit();
        cache.getAllExcept(1L);

        verify(profileRepository, times(2)).findAllVisibleParticipants();
    }

    @Test
    void evictInsideATransactionWaitsForTheCommit() {
        ParticipantListCache cache = cache(10);
        cache.getAllExcept(1L);
        TransactionSynchronizationManager.initSynchronization();

        cache.evictAfterCommit();
        cache.getAllExcept(1L); // 아직 커밋 전: 보관본을 그대로 쓴다.
        verify(profileRepository, times(1)).findAllVisibleParticipants();

        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        TransactionSynchronizationManager.clearSynchronization();
        cache.getAllExcept(1L);
        verify(profileRepository, times(2)).findAllVisibleParticipants();
    }

    @Test
    void concurrentRequestsAfterExpiryLoadOnlyOnce() throws Exception {
        ParticipantListCache cache = cache(10);
        CountDownLatch loading = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(profileRepository.findAllVisibleParticipants()).thenAnswer(invocation -> {
            loading.countDown();
            release.await(5, TimeUnit.SECONDS);
            return List.of(profile(1L), profile(2L));
        });
        ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            Future<List<ProfileResponse>> first = executor.submit(() -> cache.getAllExcept(1L));
            assertThat(loading.await(5, TimeUnit.SECONDS)).isTrue();
            List<Future<List<ProfileResponse>>> others = List.of(
                    executor.submit(() -> cache.getAllExcept(2L)),
                    executor.submit(() -> cache.getAllExcept(1L)),
                    executor.submit(() -> cache.getAllExcept(2L)));
            release.countDown();

            assertThat(first.get(5, TimeUnit.SECONDS)).extracting(ProfileResponse::userId).containsExactly(2L);
            for (Future<List<ProfileResponse>> other : others) {
                other.get(5, TimeUnit.SECONDS);
            }
            verify(profileRepository, times(1)).findAllVisibleParticipants();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void doesNotLoadUntilAsked() {
        cache(10);

        verify(profileRepository, never()).findAllVisibleParticipants();
    }

    private ParticipantListCache cache(long ttlSeconds) {
        return new ParticipantListCache(profileRepository, activityLookup, transactionManager, clock, ttlSeconds);
    }

    private static Profile profile(Long userId) {
        return Profile.create(userId, new Profile.NewProfile(
                "user" + userId, "F", 22, "ENFP", "산책", "A", null, null, null, null, null));
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
