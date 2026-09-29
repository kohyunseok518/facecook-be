package com.facecook.admin.service;

import com.facecook.admin.config.ActiveUserCriterion;
import com.facecook.admin.config.AdminStatsProperties;
import com.facecook.auth.entity.UserRole;
import com.facecook.auth.entity.UserStatus;
import com.facecook.auth.repository.UserRepository;
import com.facecook.cook.repository.CookRepository;
import com.facecook.match.repository.MatchInfoRepository;
import com.facecook.mission.entity.MatchMission;
import com.facecook.mission.repository.MatchMissionRepository;
import com.facecook.report.entity.ReportStatus;
import com.facecook.report.repository.ReportRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminStatsServiceTest {
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-09-30T03:00:00Z"),
            ZoneOffset.UTC
    );

    private UserRepository userRepository;
    private CookRepository cookRepository;
    private MatchInfoRepository matchInfoRepository;
    private MatchMissionRepository matchMissionRepository;
    private ReportRepository reportRepository;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        cookRepository = mock(CookRepository.class);
        matchInfoRepository = mock(MatchInfoRepository.class);
        matchMissionRepository = mock(MatchMissionRepository.class);
        reportRepository = mock(ReportRepository.class);
    }

    @Test
    void returnsStatsUsingActiveStatusWhenConfigured() {
        AdminStatsService service = service(ActiveUserCriterion.STATUS);
        when(userRepository.countByRole(UserRole.PARTICIPANT)).thenReturn(214L);
        when(userRepository.countByRoleAndStatus(UserRole.PARTICIPANT, UserStatus.ACTIVE)).thenReturn(200L);
        when(cookRepository.count()).thenReturn(487L);
        when(matchInfoRepository.count()).thenReturn(63L);
        when(matchMissionRepository.countByCurrentStepGreaterThanEqual(MatchMission.COMPLETED_STEP))
                .thenReturn(21L);
        when(reportRepository.countByStatus(ReportStatus.PENDING)).thenReturn(2L);

        var response = service.getStats();

        assertThat(response.totalUsers()).isEqualTo(214L);
        assertThat(response.activeToday()).isEqualTo(200L);
        assertThat(response.totalCooks()).isEqualTo(487L);
        assertThat(response.totalMatches()).isEqualTo(63L);
        assertThat(response.missionCleared()).isEqualTo(21L);
        assertThat(response.pendingReports()).isEqualTo(2L);
    }

    @Test
    void canCountUsersActiveTodayFromLastActiveAt() {
        AdminStatsService service = service(ActiveUserCriterion.LAST_ACTIVE_TODAY);
        LocalDateTime startInclusive = LocalDateTime.of(2026, 9, 30, 0, 0);
        LocalDateTime endExclusive = LocalDateTime.of(2026, 10, 1, 0, 0);
        when(userRepository.countByRoleAndLastActiveAtGreaterThanEqualAndLastActiveAtLessThan(
                UserRole.PARTICIPANT,
                startInclusive,
                endExclusive
        )).thenReturn(96L);

        var response = service.getStats();

        assertThat(response.activeToday()).isEqualTo(96L);
        verify(userRepository).countByRoleAndLastActiveAtGreaterThanEqualAndLastActiveAtLessThan(
                UserRole.PARTICIPANT,
                startInclusive,
                endExclusive
        );
    }

    private AdminStatsService service(ActiveUserCriterion criterion) {
        return new AdminStatsService(
                userRepository,
                cookRepository,
                matchInfoRepository,
                matchMissionRepository,
                reportRepository,
                new AdminStatsProperties(criterion),
                CLOCK
        );
    }
}
