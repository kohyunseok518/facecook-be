package com.facecook.common.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * API가 돌려줄 수 있는 오류 목록. 각 값이 HTTP 상태와 기본 메시지를 함께 가진다.
 * 응답 JSON의 {@code code}에는 이 enum의 이름(예: {@code "DAILY_LIMIT"})이
 * 그대로 들어가고, FE는 메시지 문구가 아니라 이 이름으로 분기한다.
 *
 * <p>enum을 쓴 이유: 가능한 오류가 정해진 집합이라 오타로 없는 코드를 만들
 * 수 없고, 상태 코드와 메시지가 한 줄에 붙어 있어 둘이 따로 놀지 않는다.
 * {@code @RequiredArgsConstructor}(Lombok)가 {@code (status, message)} 생성자를,
 * {@code @Getter}가 {@code getStatus()}·{@code getMessage()}를 만들어 준다.</p>
 *
 * <p>새 코드를 추가하면 FE 처리와 {@code docs/API명세.md} 에러 코드 표도 같이 본다.</p>
 */
@Getter
@RequiredArgsConstructor
public enum ErrorCode {
    VALIDATION(HttpStatus.BAD_REQUEST, "요청값이 올바르지 않습니다."),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다."),
    ALREADY_REGISTERED(HttpStatus.CONFLICT, "이미 가입된 이메일입니다."),
    NOT_FOUND(HttpStatus.NOT_FOUND, "대상을 찾을 수 없습니다."),
    SUSPENDED(HttpStatus.FORBIDDEN, "정지된 계정입니다."),
    TERMS_REQUIRED(HttpStatus.BAD_REQUEST, "필수 약관에 동의해주세요."),
    CODE_INVALID(HttpStatus.BAD_REQUEST, "인증코드가 올바르지 않습니다."),
    CODE_EXPIRED(HttpStatus.BAD_REQUEST, "인증코드가 만료되었습니다. 다시 요청해주세요."),
    RESEND_TOO_SOON(HttpStatus.TOO_MANY_REQUESTS, "인증코드는 30초 후 다시 요청할 수 있습니다."),
    EMAIL_SEND_FAILED(HttpStatus.BAD_GATEWAY, "인증메일을 보내지 못했습니다. 잠시 후 다시 시도해주세요."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 오류가 발생했습니다."),
    PROFILE_ALREADY_EXISTS(HttpStatus.CONFLICT, "이미 프로필이 등록되어 있습니다."),
    PROFILE_NOT_FOUND(HttpStatus.NOT_FOUND, "프로필을 찾을 수 없습니다."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "접근 권한이 없습니다."),
    SELF(HttpStatus.BAD_REQUEST, "자기 자신에게 콕을 보낼 수 없습니다."),
    ALREADY_MATCHED(HttpStatus.CONFLICT, "이미 매칭된 상대입니다."),
    ALREADY_EXPIRED(HttpStatus.CONFLICT, "이미 만료된 콕입니다."),
    ALREADY_REJECTED(HttpStatus.CONFLICT, "이미 거절된 콕이에요."),
    DUPLICATE(HttpStatus.CONFLICT, "이미 콕을 보낸 상대입니다."),
    DAILY_LIMIT(HttpStatus.TOO_MANY_REQUESTS, "오늘 보낼 수 있는 콕을 모두 사용했습니다."),
    EVENT_LIMIT(HttpStatus.TOO_MANY_REQUESTS, "행사 전체 콕 제한에 도달했습니다."),
    COOK_BUSY(HttpStatus.TOO_MANY_REQUESTS, "지금 콕이 몰리고 있어요. 잠시 후 다시 눌러 주세요."),
    CLOSED(HttpStatus.FORBIDDEN, "채팅 운영시간이 아닙니다."),
    REPORT_NOT_FOUND(HttpStatus.NOT_FOUND, "신고를 찾을 수 없습니다."),
    REPORT_ALREADY_REVIEWED(HttpStatus.CONFLICT, "이미 처리된 신고입니다."),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 올바르지 않습니다."),
    MISSION_STEP_MISMATCH(HttpStatus.CONFLICT, "확인한 STEP이 이미 처리됐습니다."),
    // 401인 이유는 ServiceEndInterceptor 참고 — 이미 열려 있는 옛 화면이 401을 받아야 페이지를 새로 연다.
    SERVICE_ENDED(HttpStatus.UNAUTHORIZED, "서비스가 종료되었어요. 새로고침해 주세요."),
    FEEDBACK_BUSY(HttpStatus.TOO_MANY_REQUESTS, "후기가 몰리고 있어요. 잠시 후 다시 보내 주세요.");

    private final HttpStatus status;
    private final String message;
}
