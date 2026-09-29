package com.facecook.profile.service;

import com.facecook.common.exception.ApiException;
import com.facecook.common.exception.ErrorCode;
import com.facecook.profile.dto.CreateProfileRequest;
import com.facecook.profile.dto.DepartmentGroupResponse;
import com.facecook.profile.dto.ProfileFiltersResponse;
import com.facecook.profile.dto.ProfileResponse;
import com.facecook.profile.dto.ProfileStatsResponse;
import com.facecook.profile.dto.UpdateProfileRequest;
import com.facecook.profile.entity.Profile;
import com.facecook.profile.repository.ProfileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * 참가자 프로필 CRUD·통계·탐색 필터.
 *
 * <p>{@link #getDepartments}만 예외적으로 이 클래스의 다른 메서드와
 * 무관하다 — DB를 전혀 안 쓰고 정적 학과 카탈로그({@link
 * DepartmentCatalog})를 그대로 반환한다.</p>
 */
@Service
@RequiredArgsConstructor
public class ProfileService {

    private final ProfileRepository profileRepository;
    private final ProfilePhotoUrlPolicy photoUrlPolicy;
    private final ProfileActivityLookup activityLookup;
    private final ParticipantListCache participantListCache;

    /**
     * userId의 프로필을 새로 만든다(가입 직후 1회, 유저당 1개).
     *
     * <p>전제조건: 아직 프로필이 없음, photo가 있으면 우리 업로드 API가
     * 내준 URL, department가 있으면 정해진 30개 학과 중 하나.</p>
     *
     * <p>부작용: {@code Profile} 행을 저장한다. 동시에 같은 userId로 두
     * 번 생성 요청이 와도 DB unique 제약이 막아주고, 그 예외도
     * {@code PROFILE_ALREADY_EXISTS}로 변환해서 던진다.</p>
     *
     * <p>예외: {@code PROFILE_ALREADY_EXISTS}, {@code VALIDATION}(잘못된
     * photo/department).</p>
     *
     * @see #update(Long, UpdateProfileRequest)
     */
    @Transactional
    public ProfileResponse create(Long userId, CreateProfileRequest request) {
        if (profileRepository.existsById(userId)) {
            throw new ApiException(ErrorCode.PROFILE_ALREADY_EXISTS);
        }
        requireOwnPhotoUrlIfPresent(request.photo());
        requireValidDepartmentIfPresent(request.department());

        try {
            Profile profile = profileRepository.saveAndFlush(Profile.create(userId, toNewProfile(request)));
            participantListCache.evictAfterCommit();
            return activityLookup.toResponse(profile);
        } catch (DataIntegrityViolationException exception) {
            throw new ApiException(ErrorCode.PROFILE_ALREADY_EXISTS, exception);
        }
    }

    /**
     * userId의 프로필을 활동 중 여부와 함께 조회한다.
     *
     * <p>전제조건: 프로필 존재.</p>
     *
     * <p>부작용: 없음.</p>
     *
     * <p>예외: {@code PROFILE_NOT_FOUND}.</p>
     *
     * @see #getParticipantsExcept(Long)
     */
    @Transactional(readOnly = true)
    public ProfileResponse get(Long userId) {
        return activityLookup.toResponse(findProfile(userId));
    }

    /**
     * userId의 프로필을 부분 수정한다 — request에서 null이 아닌 필드
     * (department/grade/bio/photo)만 바뀐다.
     *
     * <p>전제조건: 프로필 존재, request에 최소 하나는 값이 있음
     * ({@link UpdateProfileRequest#hasChanges}), photo/department가
     * 있으면 {@link #create}와 같은 검증 통과.</p>
     *
     * <p>부작용: {@code Profile} 엔티티 필드를 바꾼다(영속성 컨텍스트가
     * 트랜잭션 커밋 시 변경분을 자동 반영 — 별도 save 호출 없음).</p>
     *
     * <p>예외: {@code VALIDATION}(변경사항 없음, 잘못된
     * photo/department), {@code PROFILE_NOT_FOUND}.</p>
     *
     * @see #create(Long, CreateProfileRequest)
     */
    @Transactional
    public ProfileResponse update(Long userId, UpdateProfileRequest request) {
        if (!request.hasChanges()) {
            throw new ApiException(ErrorCode.VALIDATION, "수정할 프로필 항목을 입력해주세요.");
        }
        requireOwnPhotoUrlIfPresent(request.photo());
        requireValidDepartmentIfPresent(request.department());

        Profile profile = findProfile(userId);
        profile.update(toEdit(request));
        participantListCache.evictAfterCommit();
        return activityLookup.toResponse(profile);
    }

    /**
     * userId 본인을 제외한 전체 참가자 프로필을 userId 오름차순으로
     * 반환한다(탐색 화면용).
     *
     * <p>전제조건: 없음.</p>
     *
     * <p>부작용: 없음. 페이지네이션이 없어 전체를 한 번에 반환한다. 목록은 {@link ParticipantListCache}가
     * 서버 메모리에 잠깐(기본 10초) 보관해 재사용한다 — 보관본이 있으면 DB에 가지 않고, 트랜잭션도 열지
     * 않는다. 그래서 이 메서드에는 {@code @Transactional}을 두지 않는다(#133).</p>
     *
     * <p>예외 없음.</p>
     *
     * @see #get(Long)
     */
    public List<ProfileResponse> getParticipantsExcept(Long userId) {
        return participantListCache.getAllExcept(userId);
    }

    /**
     * 단과대별로 묶인 학과 카탈로그를 반환한다(회원가입·프로필 수정
     * 화면의 학과 선택지, {@link #create}/{@link #update}가 검증할 때
     * 쓰는 것과 같은 목록).
     *
     * <p>전제조건: 없음.</p>
     *
     * <p>부작용: 없음 — DB를 전혀 안 쓴다(고정된 정적 카탈로그).</p>
     *
     * <p>예외 없음.</p>
     */
    public List<DepartmentGroupResponse> getDepartments() {
        return DepartmentCatalog.groups().stream()
                .map(group -> new DepartmentGroupResponse(group.college(), group.majors()))
                .toList();
    }

    /**
     * 전체 프로필 수와 현재 활동 중인 참가자 수를 반환한다.
     *
     * <p>전제조건: 없음.</p>
     *
     * <p>부작용: 없음.</p>
     *
     * <p>예외 없음.</p>
     */
    @Transactional(readOnly = true)
    public ProfileStatsResponse getStats() {
        long total = profileRepository.countVisibleParticipants();
        long activeNow = profileRepository.countVisibleParticipantsActiveSince(activityLookup.activeSince());
        return new ProfileStatsResponse(total, activeNow);
    }

    /**
     * 탐색 화면 필터용으로 현재 존재하는 학과·MBTI·취미 값을 중복 없이
     * 정렬해서 반환한다(activeOnly면 활동 중인 참가자만 대상).
     *
     * <p>전제조건: 없음.</p>
     *
     * <p>부작용: 없음. 페이징이 아직 없어서(2순위 별도 작업) 지금은
     * 전체를 기준으로 계산한다. 참가자가 300명 수준이라 매번 전체를
     * 훑어도 부담이 크지 않다.</p>
     *
     * <p>예외 없음.</p>
     */
    @Transactional(readOnly = true)
    public ProfileFiltersResponse getFilters(boolean activeOnly) {
        List<Profile> profiles = activeOnly
                ? profileRepository.findVisibleParticipantsActiveSince(activityLookup.activeSince())
                : profileRepository.findAllVisibleParticipants();

        TreeSet<String> departments = new TreeSet<>();
        TreeSet<String> mbtis = new TreeSet<>();
        TreeSet<String> hobbies = new TreeSet<>();

        for (Profile profile : profiles) {
            if (profile.getDepartment() != null && !profile.getDepartment().isBlank()) {
                departments.add(profile.getDepartment());
            }
            if (profile.getMbti() != null && !profile.getMbti().isBlank()) {
                mbtis.add(profile.getMbti());
            }
            hobbies.addAll(splitHobby(profile.getHobby()));
        }

        return new ProfileFiltersResponse(
                new ArrayList<>(departments),
                new ArrayList<>(mbtis),
                new ArrayList<>(hobbies)
        );
    }

    private Profile findProfile(Long userId) {
        return profileRepository.findById(userId)
                .orElseThrow(() -> new ApiException(ErrorCode.PROFILE_NOT_FOUND));
    }

    /** 쉼표로 이어진 취미 문자열을 트리밍한 뒤 빈 항목을 뺀 목록으로 만든다. */
    static List<String> splitHobby(String hobby) {
        List<String> result = new ArrayList<>();
        for (String token : hobby.split(",")) {
            String trimmed = token.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }

    /** 요청 DTO를 엔티티가 아는 도메인 입력으로 바꾼다 — 엔티티는 요청 DTO를 모른다. */
    private static Profile.NewProfile toNewProfile(CreateProfileRequest request) {
        return new Profile.NewProfile(
                request.nickname(), request.gender(), request.age(), request.mbti(), request.hobby(),
                request.bloodType(), request.department(), request.grade(), request.bio(),
                request.idealType(), request.photo()
        );
    }

    private static Profile.Edit toEdit(UpdateProfileRequest request) {
        return new Profile.Edit(request.department(), request.grade(), request.bio(), request.photo());
    }

    /** photo 필드에 우리 업로드 API가 내준 게 아닌 임의의 URL이 그대로 저장되지 않게 막는다. */
    private void requireOwnPhotoUrlIfPresent(String photo) {
        if (photo != null && !photo.isBlank() && !photoUrlPolicy.isOwnPhotoUrl(photo)) {
            throw new ApiException(ErrorCode.VALIDATION, "올바르지 않은 사진 주소입니다.");
        }
    }

    /**
     * 정해진 30개 중 하나만 허용한다 — 자유 입력을 그대로 두면 "컴공"·
     * "컴퓨터공학과"처럼 표기가 갈려서 탐색 화면의 학과 필터가 문자열
     * 비교에서 못 걸러낸다.
     */
    private void requireValidDepartmentIfPresent(String department) {
        if (department != null && !department.isBlank()
                && !DepartmentCatalog.VALID_DEPARTMENTS.contains(department)) {
            throw new ApiException(ErrorCode.VALIDATION, "올바르지 않은 학과입니다.");
        }
    }
}
