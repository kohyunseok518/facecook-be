package com.facecook.cook.service;

import com.facecook.auth.entity.User;
import com.facecook.common.exception.ApiException;
import com.facecook.common.exception.ErrorCode;
import com.facecook.cook.dto.CookItemResponse;
import com.facecook.cook.dto.CookListResponse;
import com.facecook.cook.dto.CookUsageResponse;
import com.facecook.cook.dto.SendCookRequest;
import com.facecook.cook.dto.SendCookResponse;
import com.facecook.cook.entity.Cook;
import com.facecook.cook.entity.CookStatus;
import com.facecook.match.entity.MatchInfo;
import com.facecook.cook.repository.CookParticipants;
import com.facecook.cook.repository.CookRepository;
import com.facecook.cook.repository.CookUserRepository;
import com.facecook.match.repository.MatchInfoRepository;
import com.facecook.profile.dto.ProfileResponse;
import com.facecook.profile.entity.Profile;
import com.facecook.profile.repository.ProfileRepository;
import com.facecook.profile.service.ProfileActivityLookup;
import com.facecook.push.service.ParticipantPushNotificationService;
import com.facecook.common.time.EventTime;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 콕(호감 표시)의 유일한 진입점이자, 매칭이 "성사"되는 유일한 지점.
 *
 * <p>콕 전송·취소·거절·조회를 담당한다. 매칭 성사는 이 클래스 안에서만
 * 일어난다({@link #send}가 상호 콕을 감지했을 때) — 다른 클래스가
 * {@code MatchInfo}를 직접 생성하지 않는다. 성사된 매칭의 조회·읽음
 * 처리는 {@link com.facecook.match.service.MatchService}가 담당한다.</p>
 *
 * <p>잠금 규약: 콕의 상태를 바꾸는 {@link #send}, {@link #cancel}, {@link #reject}는 모두 같은 두 사용자
 * 행을 작은 userId부터 먼저 잠근다. {@link #send}는 그 뒤 매칭·중복·개인 한도를 검사하고, 행사 한도가 있는 날에
 * 한해 검사를 통과한 뒤에만 {@link EventLimitLock}을 잠가 전체 건수 확인과 저장을 한다. 취소·거절은 사용자 행을
 * 잠근 뒤에야 콕을 잠금 조회로 처음 읽으므로, 같은 사용자 쌍에 대한 명령은 서로 직렬화되고 나중 명령은 먼저
 * 커밋된 최신 상태를 보고 판정한다. 이 순서를 어기면(예: 콕을 먼저 로드) 잠금 전 상태로 판정하거나 교착이 날 수 있다.</p>
 */
@Service
@RequiredArgsConstructor
public class CookService {
    public static final int DAILY_LIMIT = 10;
    /**
     * 그날 전체 참가자가 보낼 수 있는 콕 총량(개인별 하루 10개와는 별개인
     * 시스템 전체 안전판). 예상 활성 참가자 수(300/600/900) × 유저당 하루
     * 한도(10)로 산정했고, 날짜마다 새로 리셋된다(누적 아님). 행사 기간
     * 외 날짜는 이 제한을 적용하지 않는다(로컬/개발 환경 대비).
     */
    private static final Map<LocalDate, Long> EVENT_WIDE_DAILY_LIMITS = Map.of(
            LocalDate.of(2026,9,27), 100_000L,
            LocalDate.of(2026, 9, 30), 3_000L,
            LocalDate.of(2026, 10, 1), 6_000L,
            LocalDate.of(2026, 10, 2), 9_000L
    );

    private final CookRepository cookRepository;
    private final MatchInfoRepository matchInfoRepository;
    private final CookUserRepository cookUserRepository;
    private final EventLimitLock eventLimitLock;
    private final ProfileRepository profileRepository;
    private final ProfileActivityLookup activityLookup;
    private final ParticipantPushNotificationService pushNotificationService;
    private final Clock clock;

    /**
     * senderId가 request.receiverId()에게 콕을 보낸다. 상대가 이미 나에게
     * pending 콕을 보내둔 상태였다면, 이 호출 안에서 바로 매칭이 성사된다
     * (내가 따로 "수락" API를 부를 필요가 없다 — 맞콕이면 즉시 매칭).
     *
     * <p>전제조건: senderId != receiverId, 두 유저 모두 실존, 서로 매칭된 적
     * 없음, 내가 이미 거절한 상대가 아님(상대가 나에게 보낸 콕을 내가 거절한 적 없음), 아직 같은 상대에게 보낸 콕이
     * 없음(사람당 1콕), 오늘 개인
     * 한도({@value #DAILY_LIMIT}개) 이내, 행사 전체 하루 한도
     * ({@link #EVENT_WIDE_DAILY_LIMITS}) 이내.</p>
     *
     * <p>부작용: {@code Cook} 행을 저장한다. 맞콕이면 {@code MatchInfo}를
     * 새로 만들고 양쪽 콕을 matched로 바꾼 뒤 두 사람 모두에게
     * matchCreated 푸시를 보낸다(스스로 만든 매칭이라 별도 알림 이벤트
     * 없음). 맞콕이 아니면 받는 사람에게 cookReceived 푸시만 보낸다.</p>
     *
     * <p>예외: {@code SELF}(자기 자신), {@code NOT_FOUND}(상대 없음),
     * {@code ALREADY_MATCHED}, {@code ALREADY_REJECTED}(내가 이미 거절한 상대 —
     * 상대의 콕이 내 거절로 REJECTED 상태), {@code DUPLICATE}(이미 보낸 콕 있음 — DB
     * unique 제약 위반도 이 코드로 변환됨), {@code DAILY_LIMIT},
     * {@code EVENT_LIMIT}(행사 지정일에만 적용). 검사 순서: 자기 자신 → 상대 존재 → 매칭 →
     * 거절한 상대 → 중복 → 개인 한도 → 행사 전체 한도.</p>
     *
     * <p>READ COMMITTED인 이유: 행사 한도 잠금보다 앞선 검사 조회가 있어서, REPEATABLE-READ라면 그 조회 시점의
     * 스냅샷으로 전체 건수를 세게 된다. 잠금을 기다리는 동안 다른 전송이 커밋한 콕이 건수에서 빠져 한도를 넘긴다.
     * READ COMMITTED에서는 문장마다 커밋된 최신 값을 읽으므로 잠금 뒤의 건수가 앞선 전송을 모두 본다. 검사 조회는
     * 사용자 행 잠금이 같은 쌍의 명령을 직렬화하므로 격리 수준과 관계없이 같은 결과다. 다른 트랜잭션 안에서
     * 호출되면(테스트) 그 트랜잭션의 격리 수준을 따른다.</p>
     *
     * @see #cancel(Long, Long)
     * @see #completeMutualMatch(Cook, Cook, LocalDateTime)
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public SendCookResponse send(Long senderId, SendCookRequest request) {
        Long receiverId = request.receiverId();
        if (senderId.equals(receiverId)) {
            throw new ApiException(ErrorCode.SELF);
        }

        lockUsersAndValidateReceiver(senderId, receiverId);
        LocalDateTime now = EventTime.now(clock);
        Optional<Cook> reverseCook = validateSendable(senderId, receiverId, now);
        enforceEventWideDailyLimit(now.toLocalDate());
        Cook cook = savePendingCook(senderId, receiverId, now);
        completeSend(cook, reverseCook, now);
        return SendCookResponse.from(cook);
    }

    /**
     * userId가 자신이 보낸 콕(cookId)을 취소한다.
     *
     * <p>전제조건: cookId 존재, userId가 그 콕의 sender, 매칭·만료·거절 상태가 아님. 이미 CANCELLED인
     * 콕을 다시 취소하면 상태를 바꾸지 않고 성공한다. 콕 상태는 콕의 두 사용자 행을 잠근 뒤 잠금 조회로
     * 읽은 값으로 판정한다(클래스 Javadoc의 잠금 규약).</p>
     *
     * <p>부작용: 두 사용자 행과 콕 행을 잠그고, 상태를 CANCELLED로 바꾼다(저장은 트랜잭션 커밋 시
     * 더티 체킹).</p>
     *
     * <p>예외: {@code NOT_FOUND}, {@code FORBIDDEN}(본인 콕 아님), {@code ALREADY_MATCHED},
     * {@code ALREADY_EXPIRED}(레거시 EXPIRED 행), {@code ALREADY_REJECTED}. 검사 순서는
     * {@link Cook#cancel(Long)}이 정한다.</p>
     *
     * @see #reject(Long, Long)
     * @see Cook#cancel(Long)
     */
    @Transactional
    public void cancel(Long userId, Long cookId) {
        lockCook(cookId).cancel(userId);
    }

    /**
     * userId가 자신이 받은 콕(cookId)을 거절한다.
     *
     * <p>전제조건: cookId 존재, userId가 그 콕의 receiver. 이미 REJECTED인 콕을 다시 거절하면 상태를
     * 바꾸지 않고 성공한다. 이 메서드 자체는 거절 API의 활성화 여부를 확인하지 않는다 — 플래그와 요청
     * 헤더 검사는 {@link com.facecook.cook.controller.CookController}가 한다.</p>
     *
     * <p>부작용: 두 사용자 행과 콕 행을 잠그고, 대기 중인 콕이면 상태를 REJECTED로 바꾼다(저장은
     * 트랜잭션 커밋 시 더티 체킹). 푸시를 보내지 않고 보낸 사람의 오늘 사용 횟수도 돌려주지 않는다.</p>
     *
     * <p>예외: {@code NOT_FOUND}(없는 콕, 또는 이미 취소된 콕), {@code FORBIDDEN}(받은 사람이 아님),
     * {@code ALREADY_MATCHED}, {@code ALREADY_EXPIRED}(레거시 EXPIRED 행). 검사 순서는
     * {@link Cook#reject(Long)}이 정한다.</p>
     *
     * @see #cancel(Long, Long)
     * @see Cook#reject(Long)
     */
    @Transactional
    public void reject(Long userId, Long cookId) {
        lockCook(cookId).reject(userId);
    }

    /**
     * userId가 보낸/받은 콕 목록과 오늘·누적 사용량을 함께 반환한다. 취소된 콕은 양쪽 목록에서 빠지고,
     * 거절된 콕은 거절한 사람(받은 사람)의 받은 목록에서만 빠진다. 거절당한 사람의 보낸 목록에는
     * {@code rejected} 상태로 남는다.
     *
     * <p>전제조건: 없음.</p>
     *
     * <p>부작용: 없다. 조회 전용 트랜잭션이다.</p>
     *
     * <p>예외 없음.</p>
     *
     * @see #send(Long, SendCookRequest)
     */
    @Transactional(readOnly = true)
    public CookListResponse getCooks(Long userId) {
        LocalDateTime now = EventTime.now(clock);
        List<Cook> cooks = cookRepository.findAllBySenderIdOrReceiverIdOrderBySentAtDesc(userId, userId)
                .stream()
                .filter(cook -> cook.getStatus() != CookStatus.CANCELLED)
                .toList();

        Map<Long, ProfileResponse> profiles = profileResponses(
                cooks.stream().map(cook -> cook.otherUserId(userId)).collect(Collectors.toSet())
        );
        List<CookItemResponse> sent = cooks.stream()
                .filter(cook -> cook.getSenderId().equals(userId))
                .map(cook -> toCookItem(cook, userId, profiles))
                .toList();
        List<CookItemResponse> received = cooks.stream()
                .filter(cook -> cook.getReceiverId().equals(userId))
                .filter(cook -> !cook.isRejected())
                .map(cook -> toCookItem(cook, userId, profiles))
                .toList();

        DateRange today = today(now.toLocalDate());
        long todayUsed = countSent(userId, today);
        long totalUsed = cookRepository.countBySenderId(userId);
        return new CookListResponse(
                sent,
                received,
                new CookUsageResponse(todayUsed, DAILY_LIMIT, totalUsed)
        );
    }

    /**
     * 이 콕을 보낼 수 있는지 검사하고, 그 과정에서 조회한 상대의 역방향 콕을 돌려준다(맞콕 판단에 다시 쓴다).
     *
     * <p>전제조건: 호출한 트랜잭션에서 두 사용자 행을 이미 잠갔다(클래스 Javadoc의 잠금 규약). 이 잠금이 있어야
     * 아래 조회와 개수 확인이 같은 쌍의 다른 명령과 겹치지 않는다.</p>
     *
     * <p>부작용: 없다(조회 전용). 두 사람 사이 콕은 방향 구분 없이 한 번에 읽어 거절·중복 판정에 함께 쓴다.</p>
     *
     * <p>예외와 검사 순서: {@code ALREADY_MATCHED}(이미 매칭됨) → {@code ALREADY_REJECTED}(내가 이미 거절한
     * 상대 — 상대가 나에게 보낸 콕이 내 거절로 REJECTED 상태) → {@code DUPLICATE}(이미 보낸 콕) →
     * {@code DAILY_LIMIT}. 순서를 바꾸면 같은 요청이 다른 오류 코드를 받게 된다. 행사 전체 한도는 이 검사를 모두
     * 통과한 뒤 {@link #enforceEventWideDailyLimit}가 본다 — 여기서 실패할 요청이 행사 한도 잠금을 기다리지 않게 한다.</p>
     */
    private Optional<Cook> validateSendable(Long senderId, Long receiverId, LocalDateTime now) {
        if (matchInfoRepository.existsBetween(senderId, receiverId)) {
            throw new ApiException(ErrorCode.ALREADY_MATCHED);
        }
        List<Cook> cooksBetween = cookRepository.findAllBetween(senderId, receiverId);
        Optional<Cook> reverseCook = cooksBetween.stream()
                .filter(cook -> cook.getSenderId().equals(receiverId))
                .findFirst();
        if (reverseCook.filter(Cook::isRejected).isPresent()) {
            throw new ApiException(ErrorCode.ALREADY_REJECTED, "이미 거절한 상대예요.");
        }
        if (cooksBetween.stream().anyMatch(cook -> cook.getSenderId().equals(senderId))) {
            throw new ApiException(ErrorCode.DUPLICATE);
        }

        if (countSent(senderId, today(now.toLocalDate())) >= DAILY_LIMIT) {
            throw new ApiException(ErrorCode.DAILY_LIMIT);
        }
        return reverseCook;
    }

    /**
     * 대기(pending) 상태의 새 콕을 저장하고 즉시 flush한다.
     *
     * <p>부작용: {@code Cook} 행을 저장한다. flush로 (sender, receiver) unique 제약을 이 자리에서 확인한다.</p>
     *
     * <p>예외: {@code DUPLICATE} — 검사 이후 다른 트랜잭션이 같은 쌍을 먼저 저장해 unique 제약에 걸린 경우.</p>
     */
    private Cook savePendingCook(Long senderId, Long receiverId, LocalDateTime sentAt) {
        try {
            return cookRepository.saveAndFlush(Cook.pending(senderId, receiverId, sentAt));
        } catch (DataIntegrityViolationException exception) {
            throw new ApiException(ErrorCode.DUPLICATE, exception);
        }
    }

    /**
     * 저장한 콕의 후속 처리: 상대가 이미 나에게 보내 둔 대기 콕이 있으면 맞콕이므로 매칭을 확정하고, 없으면
     * 받는 사람에게 콕 도착 푸시를 요청한다.
     *
     * <p>부작용: 맞콕이면 {@link #completeMutualMatch}의 부작용(매칭 저장, 두 콕 matched, 푸시 요청 2건).
     * 아니면 콕 도착 푸시 요청 1건.</p>
     */
    private void completeSend(Cook cook, Optional<Cook> reverseCook, LocalDateTime now) {
        Optional<Cook> pendingReverseCook = reverseCook.filter(Cook::isPending);
        if (pendingReverseCook.isPresent()) {
            completeMutualMatch(cook, pendingReverseCook.get(), now);
        } else {
            pushNotificationService.cookReceived(cook.getReceiverId());
        }
    }

    /**
     * userId가 {@code range} 안에 보낸 콕 수. 개인 하루 한도 검사({@link #send})와 사용량 표시
     * ({@link #getCooks})가 같은 규칙으로 세도록 한 곳에 둔다.
     *
     * <p>전제조건: 없음.</p>
     *
     * <p>부작용: 없다(조회 전용).</p>
     *
     * <p>상태 조건을 두지 않는다 — 취소·거절·만료된 콕도 센다. 그래서 콕을 취소하거나 상대가 거절해도
     * 오늘 사용 횟수는 돌아오지 않는다(돌려주면 콕을 뿌렸다가 회수하는 식으로 하루 한도를 우회할 수 있다).</p>
     */
    private long countSent(Long userId, DateRange range) {
        return cookRepository.countBySenderIdAndSentAtGreaterThanEqualAndSentAtLessThan(
                userId,
                range.startInclusive(),
                range.endExclusive()
        );
    }

    /**
     * 행사 한도가 있는 날이면 {@code event_limit_lock}을 잠근 뒤 그날 전체 콕 수를 세어 한도를 확인한다. 한도가 없는
     * 날(로컬·개발, 행사 기간 외)에는 아무 것도 하지 않아 전송이 서로 기다리지 않는다.
     *
     * <p>전제조건: 사용자 행 잠금과 {@link #validateSendable}를 마쳤고, 트랜잭션이 READ COMMITTED다({@link #send}
     * 참고). 잠금 뒤에는 건수 확인·저장·맞콕 처리만 남아야 한다 — 잠금을 쥐는 시간이 행사일 콕 전송의 초당 처리
     * 한계다.</p>
     *
     * <p>부작용: 한도가 있는 날이면 {@code event_limit_lock} 행을 배타 잠금한다(트랜잭션이 끝날 때까지).</p>
     *
     * <p>예외: {@code EVENT_LIMIT} — 그날 전체 콕 수가 한도에 닿았다.</p>
     */
    private void enforceEventWideDailyLimit(LocalDate today) {
        Optional<Long> limit = eventWideLimitOn(today);
        if (limit.isEmpty()) {
            return;
        }
        eventLimitLock.acquire();
        DateRange range = today(today);
        long sentToday = cookRepository.countBySentAtGreaterThanEqualAndSentAtLessThan(
                range.startInclusive(),
                range.endExclusive()
        );
        if (sentToday >= limit.get()) {
            throw new ApiException(ErrorCode.EVENT_LIMIT);
        }
    }

    /**
     * 그날 적용되는 행사 전체 콕 한도. 한도가 없는 날(로컬·개발, 행사 기간 외)이면 비어 있다.
     *
     * <p>"한도가 있는 날인가"의 판정을 이 한 곳에 둔다. 잠금과 건수 확인은 {@link #enforceEventWideDailyLimit} 한
     * 곳에서 이 판정 하나로 함께 하므로, 잠금 없이 한도를 세는 상황이 생기지 않는다.</p>
     *
     * <p>부작용: 없다.</p>
     */
    private Optional<Long> eventWideLimitOn(LocalDate date) {
        return Optional.ofNullable(EVENT_WIDE_DAILY_LIMITS.get(date));
    }

    /**
     * 취소·거절 대상 콕을 잠금 규약 순서로 읽는다: 콕의 사용자 쌍을 엔티티 로드 없이 조회 → 두 사용자 행
     * 잠금 → 콕을 잠금 조회. 잠금 전에 읽은 콕 상태는 판정에 쓰지 않는다.
     *
     * <p>전제조건: 호출한 트랜잭션에서 아직 이 콕을 로드하지 않았다(로드한 엔티티는 잠금 조회가 최신
     * 값으로 갱신해 주지 않는다).</p>
     *
     * <p>예외: {@code NOT_FOUND}(콕이 없거나, 잠금을 기다리는 사이 삭제됨).</p>
     */
    private Cook lockCook(Long cookId) {
        CookParticipants participants = cookRepository.findParticipantsById(cookId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        lockUsersInOrder(participants.getSenderId(), participants.getReceiverId());
        return cookRepository.findByIdForUpdate(cookId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
    }

    private List<User> lockUsersInOrder(Long firstId, Long secondId) {
        List<Long> userIds = List.of(Math.min(firstId, secondId), Math.max(firstId, secondId));
        return cookUserRepository.findAllByIdForUpdate(userIds);
    }

    private void lockUsersAndValidateReceiver(Long senderId, Long receiverId) {
        boolean receiverExists = lockUsersInOrder(senderId, receiverId).stream()
                .anyMatch(user -> user.getId().equals(receiverId));
        if (!receiverExists) {
            throw new ApiException(ErrorCode.NOT_FOUND);
        }
    }

    /**
     * 서로 콕을 보낸 두 사람을 매칭으로 확정한다: 매칭 저장 → 양쪽 콕을 matched로 표시 → 두 사람에게
     * 매칭 성사 푸시 요청. 이 세 동작을 이 순서로 묶는 것이 "매칭 성사"이고, {@link #completeSend}가 부른다({@link #send}를 거쳐서 — 다른 클래스는 부르지 않는다).
     *
     * <p>전제조건: {@code cook}은 방금 저장한 콕이고 {@code reverseCook}은 상대가 나에게 보내 둔 대기(pending)
     * 콕이다. 호출한 트랜잭션에서 두 사용자 행을 이미 잠갔다.</p>
     *
     * <p>부작용: {@code MatchInfo}를 저장하고 즉시 flush한다(매칭 ID가 필요해서). 두 콕에 매칭 ID를 기록하며
     * 상태를 matched로 바꾼다(저장은 트랜잭션 커밋 시 더티 체킹). 두 사람에게 푸시를 요청한다.</p>
     *
     * <p>예외 없음(저장 실패는 그대로 전파되어 트랜잭션이 롤백된다).</p>
     */
    private void completeMutualMatch(Cook cook, Cook reverseCook, LocalDateTime matchedAt) {
        MatchInfo matchInfo = saveMatch(cook, matchedAt);
        markCooksMatched(cook, reverseCook, matchInfo);
        requestMatchCreatedPush(matchInfo);
    }

    /** 두 사람 사이의 매칭을 저장하고 즉시 flush해서 매칭 ID를 얻는다. */
    private MatchInfo saveMatch(Cook cook, LocalDateTime matchedAt) {
        return matchInfoRepository.saveAndFlush(
                MatchInfo.create(cook.getSenderId(), cook.getReceiverId(), matchedAt)
        );
    }

    /** 서로가 보낸 두 콕을 같은 매칭에 연결하고 matched 상태로 바꾼다. */
    private void markCooksMatched(Cook cook, Cook reverseCook, MatchInfo matchInfo) {
        cook.match(matchInfo.getId());
        reverseCook.match(matchInfo.getId());
    }

    /**
     * 매칭에 속한 두 사람 모두에게 매칭 성사 푸시를 "요청"한다. 실제 발송은 커밋 뒤에 하고, 받는 사람이 지금
     * 앱에 접속 중이면 보내지 않는다({@link ParticipantPushNotificationService}의 공통 규칙).
     */
    private void requestMatchCreatedPush(MatchInfo matchInfo) {
        pushNotificationService.matchCreated(matchInfo.getUserAId(), matchInfo.getId());
        pushNotificationService.matchCreated(matchInfo.getUserBId(), matchInfo.getId());
    }

    private CookItemResponse toCookItem(
            Cook cook,
            Long userId,
            Map<Long, ProfileResponse> profiles
    ) {
        Long otherUserId = cook.otherUserId(userId);
        return CookItemResponse.from(cook, userId, profiles.get(otherUserId));
    }

    private Map<Long, ProfileResponse> profileResponses(Collection<Long> userIds) {
        return activityLookup.toResponses(profileRepository.findAllById(userIds)).stream()
                .collect(Collectors.toMap(ProfileResponse::userId, Function.identity()));
    }


    private DateRange today(LocalDate date) {
        LocalDateTime start = date.atStartOfDay();
        return new DateRange(start, start.plusDays(1));
    }

    private record DateRange(LocalDateTime startInclusive, LocalDateTime endExclusive) {
    }
}
