package com.facecook.profile.service;

import com.facecook.profile.dto.ProfileResponse;
import com.facecook.profile.repository.ProfileRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.List;

/**
 * 탐색 목록(활동 상태인 참가자 전원 프로필 — 관리자·슈퍼·정지 계정 제외, #149)을 서버 메모리에 잠깐 보관한다(facecook-be#133).
 *
 * <p>이 목록은 요청마다 참가자 전원 프로필과 계정을 읽는다(900명이면 약 1,880행). 2026-09-26 부하테스트에서
 * 이 쿼리 하나가 DB 시간의 33%를 차지했다. 목록은 몇 초 사이에 거의 바뀌지 않아서, 한 번 만든 응답을
 * {@code app.profile.participant-list-cache-seconds}(기본 10초) 동안 재사용한다.</p>
 *
 * <p>동작:</p>
 * <ul>
 * <li>보관본이 기간 안이면 DB에 가지 않는다. 트랜잭션도 열지 않는다.</li>
 * <li>기간이 지나면 한 요청만 다시 읽고, 그동안 온 다른 요청은 그 결과를 함께 쓴다({@code synchronized}).</li>
 * <li>프로필을 만들거나 고치면 커밋 뒤에 이 서버의 보관본을 버린다({@link #evictAfterCommit}).</li>
 * <li>보관 기간을 0 이하로 두면 보관하지 않고 매번 읽는다.</li>
 * </ul>
 *
 * <p>서버마다 따로 보관하므로(2~4대), 다른 서버에서 고친 프로필·새 참가자·"활동 중" 표시는 최대 보관 기간만큼
 * 늦게 보인다. 보관하는 것은 만들어 둔 응답 객체라서, 사용자는 {@code isActive}도 그 시점 기준으로 받는다.</p>
 */
@Component
public class ParticipantListCache {

    private final ProfileRepository profileRepository;
    private final ProfileActivityLookup activityLookup;
    private final TransactionTemplate readOnlyTransaction;
    private final Clock clock;
    private final long ttlMillis;

    private volatile Snapshot snapshot;

    public ParticipantListCache(
            ProfileRepository profileRepository,
            ProfileActivityLookup activityLookup,
            PlatformTransactionManager transactionManager,
            Clock clock,
            @Value("${app.profile.participant-list-cache-seconds:10}") long ttlSeconds
    ) {
        this.profileRepository = profileRepository;
        this.activityLookup = activityLookup;
        this.readOnlyTransaction = new TransactionTemplate(transactionManager);
        this.readOnlyTransaction.setReadOnly(true);
        this.clock = clock;
        this.ttlMillis = ttlSeconds * 1000;
    }

    /** 참가자 전원 중 userId를 뺀 목록을 userId 오름차순으로 돌려준다. */
    public List<ProfileResponse> getAllExcept(Long userId) {
        return current().responses().stream()
                .filter(response -> !response.userId().equals(userId))
                .toList();
    }

    /**
     * 이 서버의 보관본을 버린다. 트랜잭션 안에서 부르면 커밋된 뒤에 버린다 — 커밋 전에 버리면 그 사이 다른
     * 요청이 바뀌기 전 값을 다시 읽어 보관할 수 있다.
     */
    public void evictAfterCommit() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    snapshot = null;
                }
            });
            return;
        }
        snapshot = null;
    }

    private Snapshot current() {
        Snapshot cached = snapshot;
        if (cached != null && isFresh(cached)) {
            return cached;
        }
        return reload();
    }

    private synchronized Snapshot reload() {
        Snapshot cached = snapshot;
        if (cached != null && isFresh(cached)) {
            return cached; // 기다리는 동안 앞 요청이 이미 새로 읽었다.
        }
        List<ProfileResponse> responses = readOnlyTransaction.execute(status ->
                activityLookup.toResponses(profileRepository.findAllVisibleParticipants()));
        Snapshot loaded = new Snapshot(List.copyOf(responses), clock.millis());
        if (ttlMillis > 0) {
            snapshot = loaded;
        }
        return loaded;
    }

    private boolean isFresh(Snapshot cached) {
        return clock.millis() - cached.loadedAtMillis() < ttlMillis;
    }

    private record Snapshot(List<ProfileResponse> responses, long loadedAtMillis) {
    }
}
