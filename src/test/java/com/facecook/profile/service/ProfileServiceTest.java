package com.facecook.profile.service;

import com.facecook.common.exception.ApiException;
import com.facecook.common.exception.ErrorCode;
import com.facecook.profile.dto.CreateProfileRequest;
import com.facecook.profile.dto.ProfileResponse;
import com.facecook.profile.dto.UpdateProfileRequest;
import com.facecook.profile.entity.Profile;
import com.facecook.profile.repository.ProfileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProfileServiceTest {

    @Mock
    private ProfileRepository profileRepository;

    @Mock
    private ProfilePhotoUrlPolicy photoUrlPolicy;

    @Mock
    private ProfileActivityLookup activityLookup;

    @Mock
    private ParticipantListCache participantListCache;

    private ProfileService profileService;

    @BeforeEach
    void setUp() {
        lenient().when(photoUrlPolicy.isOwnPhotoUrl(any())).thenReturn(true);
        // 활동 정보(lastActiveAt/isActive)는 이 테스트의 관심사가 아니라, 실제
        // ProfileResponse.from을 그대로 위임해 null/false로 채운다.
        lenient().when(activityLookup.toResponse(any(Profile.class)))
                .thenAnswer(invocation -> ProfileResponse.from(invocation.getArgument(0), null, false));
        lenient().when(activityLookup.toResponses(any()))
                .thenAnswer(invocation -> {
                    List<Profile> profiles = invocation.getArgument(0);
                    return profiles.stream()
                            .map(profile -> ProfileResponse.from(profile, null, false))
                            .toList();
                });
        profileService = new ProfileService(profileRepository, photoUrlPolicy, activityLookup, participantListCache);
    }

    @Test
    void createsProfileForUser() {
        when(profileRepository.existsById(1L)).thenReturn(false);
        when(profileRepository.saveAndFlush(any(Profile.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ProfileResponse response = profileService.create(1L, createRequest("cook"));

        assertThat(response.userId()).isEqualTo(1L);
        assertThat(response.nickname()).isEqualTo("cook");
        assertThat(response.idealType()).isEqualTo("다정한 사람");
        verify(profileRepository).saveAndFlush(any(Profile.class));
        verify(participantListCache).evictAfterCommit();
    }

    @Test
    void rejectsDuplicateProfileCreation() {
        when(profileRepository.existsById(1L)).thenReturn(true);

        assertErrorCode(
                () -> profileService.create(1L, createRequest("cook")),
                ErrorCode.PROFILE_ALREADY_EXISTS
        );
        verify(profileRepository, never()).saveAndFlush(any());
    }

    @Test
    void getsProfileByUserId() {
        when(profileRepository.findById(1L)).thenReturn(Optional.of(profile(1L, "cook")));

        ProfileResponse response = profileService.get(1L);

        assertThat(response.userId()).isEqualTo(1L);
        assertThat(response.nickname()).isEqualTo("cook");
    }

    @Test
    void rejectsMissingProfile() {
        when(profileRepository.findById(1L)).thenReturn(Optional.empty());

        assertErrorCode(() -> profileService.get(1L), ErrorCode.PROFILE_NOT_FOUND);
    }

    @Test
    void updatesEditableFieldsWithoutChangingRequiredFields() {
        Profile profile = profile(1L, "cook");
        when(profileRepository.findById(1L)).thenReturn(Optional.of(profile));

        ProfileResponse response = profileService.update(
                1L,
                new UpdateProfileRequest("소프트웨어공학과", null, "수정된 소개", null)
        );

        assertThat(response.nickname()).isEqualTo("cook");
        assertThat(response.department()).isEqualTo("소프트웨어공학과");
        assertThat(response.bio()).isEqualTo("수정된 소개");
        assertThat(response.grade()).isEqualTo("2학년");
        verify(participantListCache).evictAfterCommit();
    }

    @Test
    void emptyStringOnUpdateClearsTheFieldUnlikeNull() {
        Profile profile = profile(1L, "cook");
        when(profileRepository.findById(1L)).thenReturn(Optional.of(profile));

        ProfileResponse response = profileService.update(
                1L,
                new UpdateProfileRequest("", null, null, null)
        );

        assertThat(response.department()).isEmpty();
        assertThat(response.grade()).isEqualTo("2학년");
    }

    @Test
    void rejectsUpdateWithoutAnyFields() {
        assertErrorCode(
                () -> profileService.update(1L, new UpdateProfileRequest(null, null, null, null)),
                ErrorCode.VALIDATION
        );
        verify(profileRepository, never()).findById(any());
    }

    @Test
    void rejectsForeignPhotoUrlOnCreate() {
        when(photoUrlPolicy.isOwnPhotoUrl("https://evil.example.com/x.jpg")).thenReturn(false);

        CreateProfileRequest request = new CreateProfileRequest(
                "cook", "female", 21, "ENFP", "요리", "A",
                "소프트웨어공학과", "2학년", "안녕하세요", "다정한 사람",
                "https://evil.example.com/x.jpg"
        );

        assertErrorCode(() -> profileService.create(1L, request), ErrorCode.VALIDATION);
    }

    @Test
    void rejectsForeignPhotoUrlOnUpdate() {
        when(photoUrlPolicy.isOwnPhotoUrl("https://evil.example.com/x.jpg")).thenReturn(false);

        assertErrorCode(
                () -> profileService.update(
                        1L,
                        new UpdateProfileRequest(null, null, null, "https://evil.example.com/x.jpg")
                ),
                ErrorCode.VALIDATION
        );
        verify(profileRepository, never()).findById(any());
    }

    @Test
    void rejectsInvalidDepartmentOnCreate() {
        CreateProfileRequest request = new CreateProfileRequest(
                "cook", "female", 21, "ENFP", "요리", "A",
                "컴공", "2학년", "안녕하세요", "다정한 사람", null
        );

        assertErrorCode(() -> profileService.create(1L, request), ErrorCode.VALIDATION);
        verify(profileRepository, never()).saveAndFlush(any());
    }

    @Test
    void rejectsInvalidDepartmentOnUpdate() {
        assertErrorCode(
                () -> profileService.update(1L, new UpdateProfileRequest("컴공", null, null, null)),
                ErrorCode.VALIDATION
        );
        verify(profileRepository, never()).findById(any());
    }

    @Test
    void getsDepartmentGroupsCoveringAllThirtyMajors() {
        var groups = profileService.getDepartments();

        assertThat(groups).hasSize(7);
        assertThat(groups.stream().flatMap(group -> group.majors().stream()).distinct().count())
                .isEqualTo(30);
        assertThat(groups.get(0).college()).isEqualTo("IT융합학부");
        assertThat(groups.get(0).majors()).contains("소프트웨어공학과");
    }

    @Test
    void getsParticipantStats() {
        when(profileRepository.countVisibleParticipants()).thenReturn(120L);
        when(activityLookup.activeSince()).thenReturn(java.time.LocalDateTime.of(2026, 9, 30, 12, 0));
        when(profileRepository.countVisibleParticipantsActiveSince(any())).thenReturn(37L);

        var stats = profileService.getStats();

        assertThat(stats.total()).isEqualTo(120L);
        assertThat(stats.activeNow()).isEqualTo(37L);
    }

    @Test
    void buildsFiltersFromDistinctValuesActuallyPresent() {
        when(profileRepository.findAllVisibleParticipants()).thenReturn(List.of(
                profile(1L, "a"),
                profileWith(2L, "b", "소프트웨어공학과", "ENFP", "영화보기,산책"),
                profileWith(3L, "c", "소프트웨어공학과", "INTJ", "산책,독서")
        ));

        var filters = profileService.getFilters(false);

        assertThat(filters.departments()).containsExactly("소프트웨어공학과");
        assertThat(filters.mbtis()).containsExactlyInAnyOrder("ENFP", "INTJ");
        assertThat(filters.hobbies()).containsExactlyInAnyOrder("요리", "영화보기", "산책", "독서");
    }

    @Test
    void splitsHobbyStringTrimmingAndDroppingEmptyTokens() {
        assertThat(ProfileService.splitHobby("요리, 영화보기 ,,산책"))
                .containsExactly("요리", "영화보기", "산책");
    }

    @Test
    void splitsSingleHobbyWithoutComma() {
        assertThat(ProfileService.splitHobby("요리")).containsExactly("요리");
    }

    @Test
    void filtersOnlyActiveWhenRequested() {
        java.time.LocalDateTime since = java.time.LocalDateTime.of(2026, 9, 30, 12, 0);
        when(activityLookup.activeSince()).thenReturn(since);
        when(profileRepository.findVisibleParticipantsActiveSince(since)).thenReturn(
                List.of(profileWith(2L, "b", "소프트웨어공학과", "ENFP", "영화보기"))
        );

        var filters = profileService.getFilters(true);

        assertThat(filters.departments()).containsExactly("소프트웨어공학과");
        verify(profileRepository, never()).findAll();
    }

    @Test
    void listsProfilesExceptCurrentUserFromTheCache() {
        List<ProfileResponse> cached = List.of();
        when(participantListCache.getAllExcept(1L)).thenReturn(cached);

        assertThat(profileService.getParticipantsExcept(1L)).isSameAs(cached);
        verify(participantListCache).getAllExcept(1L);
    }

    private Profile profile(Long userId, String nickname) {
        return Profile.create(userId, newProfile(nickname));
    }

    private Profile profileWith(Long userId, String nickname, String department, String mbti, String hobby) {
        return Profile.create(userId, new Profile.NewProfile(
                nickname, "female", 21, mbti, hobby, "A",
                department, "2학년", "안녕하세요", "다정한 사람", null
        ));
    }

    private Profile.NewProfile newProfile(String nickname) {
        return new Profile.NewProfile(
                nickname, "female", 21, "ENFP", "요리", "A",
                "소프트웨어공학과", "2학년", "안녕하세요", "다정한 사람",
                "https://example.com/photo.jpg"
        );
    }

    private CreateProfileRequest createRequest(String nickname) {
        return new CreateProfileRequest(
                nickname,
                "female",
                21,
                "ENFP",
                "요리",
                "A",
                "소프트웨어공학과",
                "2학년",
                "안녕하세요",
                "다정한 사람",
                "https://example.com/photo.jpg"
        );
    }

    private void assertErrorCode(Runnable action, ErrorCode expected) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(ApiException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(expected)
                );
    }
}
