package com.facecook.auth.repository;

import com.facecook.auth.entity.User;
import com.facecook.auth.entity.UserRole;
import com.facecook.auth.entity.UserStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * {@code users} 테이블 조회·저장.
 *
 * <p>Spring Data JPA 읽는 법: 인터페이스만 선언하면 스프링이 구현을 만들어 준다.
 * {@code JpaRepository<User, Long>}을 상속하면 {@code findById}, {@code save} 같은 기본 메서드가
 * 생기고, {@code existsByEmail}처럼 규칙에 맞는 이름을 선언하면 이름을 해석해 쿼리를 만든다
 * ({@code existsBy} + {@code Email} → {@code WHERE email = ?}). 이름으로 표현하기 어려운 건
 * {@code @Query}로 직접 쓴다.</p>
 *
 * <p>주요 호출부: 세션 확인({@code SessionAuthenticator} — 요청마다 {@code findById}),
 * 가입·로그인({@code AuthService}), 활동 시각({@code UserActivityService}),
 * 관리자 통계({@code AdminStatsService} — {@code count*} 메서드).</p>
 */
public interface UserRepository extends JpaRepository<User, Long> {

    boolean existsByEmail(String email);

    Optional<User> findByEmail(String email);

    /** 역할별 계정 수. 관리자 통계의 총 가입자(참가자만, #151). */
    long countByRole(UserRole role);

    /** 역할·상태별 계정 수. 관리자 통계 오늘 활성의 STATUS 기준(#151). */
    long countByRoleAndStatus(UserRole role, UserStatus status);

    /** 기간 안에 활동한 역할별 계정 수. 관리자 통계 오늘 활성의 LAST_ACTIVE_TODAY 기준(#151). */
    long countByRoleAndLastActiveAtGreaterThanEqualAndLastActiveAtLessThan(
            UserRole role,
            LocalDateTime startInclusive,
            LocalDateTime endExclusive
    );

    /**
     * 컬럼 하나만 바꾸는 벌크 업데이트다 — 매 요청마다 엔티티를 통째로
     * 읽고 쓰면(findById 후 save) 낭비가 크다.
     *
     * WHERE 절에 디바운스 조건(staleBefore)을 같이 걸어서, 이미 최근에
     * 갱신된 행이면 이 UPDATE 자체가 매치되는 행이 없어 아무 일도 안
     * 일어난다 — 매번 값을 먼저 읽어서 비교할 필요가 없다. 프론트가
     * 5초 간격으로 폴링하는 화면이 많아서, 디바운스가 없으면 인증된
     * 요청마다 매번 이 테이블에 쓰기가 발생한다.
     */
    /*
     * @Modifying: SELECT가 아닌 UPDATE/DELETE 쿼리라는 표시. 트랜잭션이 있어야 해서 이 메서드에
     * @Transactional을 둔다 — 호출하는 UserActivityService#touch는 서버 메모리로 먼저 걸러서
     * 필요할 때만 여기까지 오므로 트랜잭션을 열지 않는다(#135).
     */
    @Modifying
    @Transactional
    @Query("UPDATE User u SET u.lastActiveAt = :now WHERE u.id = :id AND (u.lastActiveAt IS NULL OR u.lastActiveAt < :staleBefore)")
    void touchLastActiveAt(
            @Param("id") Long id,
            @Param("now") LocalDateTime now,
            @Param("staleBefore") LocalDateTime staleBefore
    );
}
