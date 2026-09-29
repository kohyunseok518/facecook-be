package com.facecook.profile.repository;

import com.facecook.auth.entity.UserRole;
import com.facecook.auth.entity.UserStatus;
import com.facecook.profile.entity.Profile;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * {@code profile} 테이블 조회·저장. 읽는 법은 {@code UserRepository} 설명과 같다.
 *
 * <p>{@code @EntityGraph(attributePaths = "user")}: 프로필을 가져올 때 연결된
 * {@code User}도 같은 SQL(JOIN)로 함께 가져오라는 뜻이다. 목록 300건을 가져온 뒤
 * 건마다 {@code users}를 따로 조회하는 N+1(쿼리 1 + N번) 문제를 막는다.</p>
 *
 * <p>{@code p.user.lastActiveAt}처럼 JPQL에서 연결된 엔티티의 필드를 쓰면 JPA가
 * {@code users}와 조인한 SQL로 바꿔 준다.</p>
 */
public interface ProfileRepository extends JpaRepository<Profile, Long> {

    /**
     * ProfileActivityLookup이 profile.getUser()를 바로 읽으므로, User를
     * 나중에 각자 따로 불러오지 않도록(N+1) 목록/단건 조회 모두 미리
     * 조인해서 가져온다.
     */
    @EntityGraph(attributePaths = "user")
    @Override
    Optional<Profile> findById(Long userId);

    @EntityGraph(attributePaths = "user")
    @Override
    List<Profile> findAllById(Iterable<Long> userIds);

    /*
     * 탐색 목록·참가자 통계·탐색 필터는 "보이는 참가자" — 활동 상태(ACTIVE)인 참가자(PARTICIPANT) — 의 프로필만
     * 다룬다(facecook-be#149). 관리자·슈퍼 계정에 프로필이 있거나 참가자가 신고 처리로 정지되면 목록에 보이면 안 된다.
     * 기준은 아래 default 메서드들에만 두고, 호출부는 역할·상태를 넘기지 않는다.
     */

    /** 보이는 참가자 전원을 userId 오름차순으로. 탐색 목록 보관본을 만들 때 쓴다({@code ParticipantListCache}). */
    default List<Profile> findAllVisibleParticipants() {
        return findAllByRoleAndStatus(UserRole.PARTICIPANT, UserStatus.ACTIVE);
    }

    /** 보이는 참가자 수. 참가자 통계의 전체 인원. */
    default long countVisibleParticipants() {
        return countByRoleAndStatus(UserRole.PARTICIPANT, UserStatus.ACTIVE);
    }

    /** since 이후 활동한 보이는 참가자 수. 참가자 통계의 "활동 중" 인원. */
    default long countVisibleParticipantsActiveSince(LocalDateTime since) {
        return countByRoleAndStatusActiveSince(UserRole.PARTICIPANT, UserStatus.ACTIVE, since);
    }

    /** since 이후 활동한 보이는 참가자. 탐색 필터(활동 중만)의 값을 모을 때 쓴다. */
    default List<Profile> findVisibleParticipantsActiveSince(LocalDateTime since) {
        return findAllByRoleAndStatusActiveSince(UserRole.PARTICIPANT, UserStatus.ACTIVE, since);
    }

    @EntityGraph(attributePaths = "user")
    @Query("SELECT p FROM Profile p WHERE p.user.role = :role AND p.user.status = :status ORDER BY p.userId ASC")
    List<Profile> findAllByRoleAndStatus(@Param("role") UserRole role, @Param("status") UserStatus status);

    @Query("SELECT COUNT(p) FROM Profile p WHERE p.user.role = :role AND p.user.status = :status")
    long countByRoleAndStatus(@Param("role") UserRole role, @Param("status") UserStatus status);

    @Query("SELECT COUNT(p) FROM Profile p WHERE p.user.role = :role AND p.user.status = :status"
            + " AND p.user.lastActiveAt >= :since")
    long countByRoleAndStatusActiveSince(
            @Param("role") UserRole role, @Param("status") UserStatus status, @Param("since") LocalDateTime since);

    @Query("SELECT p FROM Profile p WHERE p.user.role = :role AND p.user.status = :status"
            + " AND p.user.lastActiveAt >= :since")
    List<Profile> findAllByRoleAndStatusActiveSince(
            @Param("role") UserRole role, @Param("status") UserStatus status, @Param("since") LocalDateTime since);
}
