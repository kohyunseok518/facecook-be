package com.facecook.cook.service;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 행사 전체 하루 콕 한도를 검사하는 동안 다른 전송이 끼어들지 못하게 하는 배타 잠금.
 *
 * <p>사용자 행 잠금은 (보내는 사람, 받는 사람) 두 행뿐이라, 서로 겹치지 않는 두 쌍이 한도 직전에 동시에
 * 보내면 둘 다 "아직 한 자리 남았다"고 읽고 통과한다. 그래서 한도가 있는 날에는 {@code event_limit_lock}의
 * 행 하나를 모든 전송이 같은 순서로 잠근다.</p>
 *
 * <p>잠금 순서: 사용자 행(작은 userId부터) → 이 잠금. 이 순서는 {@link CookService#send}만 사용하므로
 * 순환 대기가 생기지 않는다.</p>
 */
@Component
@RequiredArgsConstructor
public class EventLimitLock {

    private final EntityManager entityManager;

    /**
     * 행사 한도 잠금을 얻는다. 이미 다른 트랜잭션이 쥐고 있으면 그 트랜잭션이 끝날 때까지 기다린다.
     *
     * <p>전제조건: 호출한 트랜잭션이 이미 진행 중이고, 사용자 행 잠금을 먼저 얻었으며, <b>READ COMMITTED다.</b>
     * 이 잠금 뒤의 건수 조회는 잠금을 기다리는 동안 다른 트랜잭션이 커밋한 콕을 봐야 한다. REPEATABLE-READ에서는
     * 첫 일반 조회 때 만든 스냅샷으로 읽으므로, 잠금보다 앞선 조회가 하나라도 있으면 그 콕이 빠진다
     * ({@link CookService#send} 참고).</p>
     *
     * <p>부작용: {@code event_limit_lock}의 행 하나를 배타 잠금한다. 잠금은 트랜잭션이 커밋되거나
     * 롤백될 때 풀린다. 데이터는 바꾸지 않는다.</p>
     *
     * <p>예외: {@link IllegalStateException} — 잠금용 행이 없다(V5 마이그레이션이 적용되지 않았다). 조용히
     * 넘어가면 한도가 동시성에서 뚫리므로 실패시킨다. 진행 중인 트랜잭션이 없으면 트랜잭션 예외.</p>
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void acquire() {
        List<?> rows = entityManager
                .createNativeQuery("select lock_id from event_limit_lock where lock_id = 1 for update")
                .getResultList();
        if (rows.isEmpty()) {
            throw new IllegalStateException("event_limit_lock 행이 없다. V5 마이그레이션이 적용되지 않았다.");
        }
    }
}
