package com.facecook.admin.service;

import com.facecook.admin.config.ActiveUserCriterion;
import com.facecook.admin.config.AdminStatsProperties;
import com.facecook.admin.dto.AdminStatsResponse;
import com.facecook.auth.entity.UserRole;
import com.facecook.auth.entity.UserStatus;
import com.facecook.auth.repository.UserRepository;
import com.facecook.cook.repository.CookRepository;
import com.facecook.match.repository.MatchInfoRepository;
import com.facecook.mission.entity.MatchMission;
import com.facecook.mission.repository.MatchMissionRepository;
import com.facecook.report.entity.ReportStatus;
import com.facecook.report.repository.ReportRepository;
import com.facecook.common.time.EventTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 관리자 대시보드용 전체 통계 스냅샷을 만든다.
 *
 * <p>사용자·콕·매칭·미션완료·신고 다섯 도메인의 카운트를 한 응답으로
 * 묶어서 보여주는 게 이 클래스의 유일한 역할이라, 의존성(리포지토리)이
 * 도메인 수만큼 많아 보여도 그 자체가 문제는 아니다 — 각 필드가 정확히
 * 한 값만 채우고 서로 얽히지 않는다({@link #getStats} 참고).</p>
 */
@Service
@RequiredArgsConstructor
public class AdminStatsService {
    private final UserRepository userRepository;
    private final CookRepository cookRepository;
    private final MatchInfoRepository matchInfoRepository;
    private final MatchMissionRepository matchMissionRepository;
    private final ReportRepository reportRepository;
    private final AdminStatsProperties properties;
    private final Clock clock;

    /**
     * 참가자 수, 오늘 활동한 참가자 수, 누적 콕 수, 누적 매칭 수, 미션
     * 완주(STEP 3까지 완료) 매칭 수, 처리 대기 중인 신고 수를 한 번에
     * 반환한다. 참가자 수와 오늘 활성은 참가자(PARTICIPANT) 계정만 센다 — 관리자·슈퍼 계정은 빼고, 정지된 참가자는
     * 가입자로 센다(facecook-be#151).
     *
     * <p>전제조건: 없음.</p>
     *
     * <p>부작용: 없음(읽기 전용). 6개 값 각각 서로 다른 리포지토리에서
     * 독립적으로 집계하는 단순 COUNT라, 값 사이에 순서·일관성 보장은
     * 없다(찰나의 시차로 totalMatches와 missionCleared가 아주 살짝 안
     * 맞을 수 있음 — 대시보드 스냅샷 용도라 문제 없음).</p>
     *
     * <p>예외 없음.</p>
     *
     * @see #countActiveUsers()
     */
    @Transactional(readOnly = true)
    public AdminStatsResponse getStats() {
        return new AdminStatsResponse(
                userRepository.countByRole(UserRole.PARTICIPANT),
                countActiveUsers(),
                cookRepository.count(),
                matchInfoRepository.count(),
                matchMissionRepository.countByCurrentStepGreaterThanEqual(MatchMission.COMPLETED_STEP),
                reportRepository.countByStatus(ReportStatus.PENDING)
        );
    }

    /**
     * {@code activeToday}를 센다. 설정({@code app.admin.stats.active-user-criterion})에 따라 기준이 다르다.
     *
     * <ul>
     * <li>{@code LAST_ACTIVE_TODAY}(기본값): {@code last_active_at}이 오늘(행사 시간대 자정 ~ 다음 날 자정)인 계정 수.
     * {@code last_active_at}은 인증된 요청마다 30초 간격으로 갱신된다({@code UserActivityService}). 참가자 계정만
     * 센다 — 관리자가 대시보드를 열어도 늘지 않는다(#151).</li>
     * <li>{@code STATUS}: {@code status = active}인 참가자 계정 수. 활동 시각을 보지 않으므로 사실상
     * "정지되지 않은 참가자 전체"다.</li>
     * </ul>
     *
     * <p>참가자용 {@code GET /api/stats}의 "활동 중"({@code ProfileActivityLookup#activeSince}, 기본 최근 15분·프로필이
     * 있는 계정만)과는 어느 기준이든 세는 대상이 다르다. 두 숫자가 달라도 같은 값을 다르게 센 것이 아니다.</p>
     */
    private long countActiveUsers() {
        if (properties.activeUserCriterion() == ActiveUserCriterion.STATUS) {
            return userRepository.countByRoleAndStatus(UserRole.PARTICIPANT, UserStatus.ACTIVE);
        }

        LocalDate eventDate = EventTime.today(clock);
        LocalDateTime startInclusive = eventDate.atStartOfDay();
        return userRepository.countByRoleAndLastActiveAtGreaterThanEqualAndLastActiveAtLessThan(
                UserRole.PARTICIPANT,
                startInclusive,
                startInclusive.plusDays(1)
        );
    }
}
