package com.facecook.admin.service;

import com.facecook.admin.config.ActiveUserCriterion;
import com.facecook.admin.config.AdminStatsProperties;
import com.facecook.admin.dto.AdminStatsResponse;
import com.facecook.auth.repository.UserRepository;
import com.facecook.cook.repository.CookRepository;
import com.facecook.match.repository.MatchInfoRepository;
import com.facecook.mission.repository.MatchMissionRepository;
import com.facecook.report.repository.ReportRepository;
import com.facecook.support.MySqlIntegrationTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 관리자 대시보드의 총 가입자·오늘 활성이 참가자 계정만 세는지 실제 MySQL로 확인한다(facecook-be#151).
 * 관리자·슈퍼 계정은 오늘 요청을 보냈어도 들어가면 안 되고, 정지된 참가자는 가입자로는 센다.
 *
 * <p>공유 컨테이너라 다른 테스트의 행이 있을 수 있어서, 넣기 전과 뒤의 차이로 확인한다.</p>
 *
 * <p>부작용: 테스트마다 계정 네 개(활동 참가자·정지 참가자·관리자·슈퍼)를 커밋하고 끝나면 삭제한다.</p>
 */
class AdminStatsIntegrationTest extends MySqlIntegrationTestSupport {

    // 한국 시간 2026-09-30 12:00. 계정은 모두 한국 시간 11:55에 활동했다 — "오늘 활성" 대상.
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-30T03:00:00Z"), ZoneOffset.UTC);

    @Autowired private UserRepository userRepository;
    @Autowired private CookRepository cookRepository;
    @Autowired private MatchInfoRepository matchInfoRepository;
    @Autowired private MatchMissionRepository matchMissionRepository;
    @Autowired private ReportRepository reportRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private final List<Long> userIds = new ArrayList<>();
    private final String tag = UUID.randomUUID().toString().substring(0, 8);

    @AfterEach
    void cleanUp() {
        userIds.forEach(id -> jdbcTemplate.update("delete from users where user_id = ?", id));
        userIds.clear();
    }

    @Test
    void totalUsersAndActiveTodayCountOnlyParticipants() {
        AdminStatsService service = service(ActiveUserCriterion.LAST_ACTIVE_TODAY);
        AdminStatsResponse before = service.getStats();

        insertAccounts();
        AdminStatsResponse after = service.getStats();

        // 참가자 두 명(활동·정지)만 가입자, 오늘 활동한 참가자 두 명만 오늘 활성
        assertThat(after.totalUsers() - before.totalUsers()).isEqualTo(2);
        assertThat(after.activeToday() - before.activeToday()).isEqualTo(2);
    }

    @Test
    void statusCriterionCountsOnlyActiveParticipants() {
        AdminStatsService service = service(ActiveUserCriterion.STATUS);
        AdminStatsResponse before = service.getStats();

        insertAccounts();
        AdminStatsResponse after = service.getStats();

        // STATUS 기준: 정지되지 않은 참가자 한 명만
        assertThat(after.activeToday() - before.activeToday()).isEqualTo(1);
    }

    private AdminStatsService service(ActiveUserCriterion criterion) {
        return new AdminStatsService(userRepository, cookRepository, matchInfoRepository, matchMissionRepository,
                reportRepository, new AdminStatsProperties(criterion), CLOCK);
    }

    private void insertAccounts() {
        insert("participant", "active");
        insert("participant", "suspended");
        insert("admin", "active");
        insert("super", "active");
    }

    private void insert(String role, String status) {
        String email = "admin-stats-" + tag + "-" + role + "-" + status + "@test.local";
        jdbcTemplate.update(
                "insert into users (email, role, status, last_active_at) values (?, ?, ?, '2026-09-30 11:55:00')",
                email, role, status);
        userIds.add(jdbcTemplate.queryForObject("select user_id from users where email = ?", Long.class, email));
    }
}
