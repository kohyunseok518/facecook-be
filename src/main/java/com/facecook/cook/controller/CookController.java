package com.facecook.cook.controller;

import com.facecook.common.exception.ApiException;
import com.facecook.common.exception.ErrorCode;
import com.facecook.common.session.AuthenticatedUser;
import com.facecook.common.session.CurrentUser;
import com.facecook.cook.config.CookProperties;
import com.facecook.cook.service.CookSendGate;
import com.facecook.cook.dto.CookListResponse;
import com.facecook.cook.dto.SendCookRequest;
import com.facecook.cook.dto.SendCookResponse;
import com.facecook.cook.service.CookService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 콕 API({@code /api/cooks}).
 *
 * <table>
 * <tr><th>API</th><th>서비스</th><th>쓰는 화면(FE)</th></tr>
 * <tr><td>POST /api/cooks</td><td>{@link CookService#send}</td><td>탐색·프로필 상세의 콕 버튼, 콕 화면의 맞콕</td></tr>
 * <tr><td>GET /api/cooks</td><td>{@link CookService#getCooks}</td><td>콕 화면, 배지(5초마다 조회)</td></tr>
 * <tr><td>DELETE /api/cooks/{cookId}</td><td>{@link CookService#cancel}</td><td>보낸 콕 취소</td></tr>
 * <tr><td>POST /api/cooks/{cookId}/reject</td><td>{@link CookService#reject}</td><td>받은 콕 거절(설정으로 켜야 동작)</td></tr>
 * </table>
 *
 * <p>누가 보내고 누가 취소하는지는 전부 세션의 userId로 정한다. 요청 본문에는 받는 사람만 있다.</p>
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/cooks")
public class CookController {

    /** 새 FE가 거절 요청에 붙이는 헤더. 이 헤더가 없는 요청은 이전 화면이 보낸 것으로 본다. */
    public static final String REJECT_CONTRACT_HEADER = "X-Cook-Reject-Contract";
    public static final String REJECT_CONTRACT_VERSION = "1";

    private final CookService cookService;
    private final CookProperties cookProperties;
    private final CookSendGate cookSendGate;

    /**
     * 콕을 보낸다. 서버당 동시 처리 수 제한({@link CookSendGate})을 트랜잭션 밖인 여기서 먼저 통과한다 — 서비스 안에서
     * 기다리면 DB 연결을 쥔 채 기다리게 된다. 자리가 끝내 나지 않으면 429 {@code COOK_BUSY}.
     */
    @PostMapping
    public ResponseEntity<SendCookResponse> send(
            @CurrentUser AuthenticatedUser currentUser,
            @Valid @RequestBody SendCookRequest request
    ) {
        return ResponseEntity.ok(cookSendGate.run(() -> cookService.send(currentUser.userId(), request)));
    }

    @GetMapping
    public ResponseEntity<CookListResponse> getCooks(@CurrentUser AuthenticatedUser currentUser) {
        return ResponseEntity.ok(cookService.getCooks(currentUser.userId()));
    }

    @DeleteMapping("/{cookId}")
    public ResponseEntity<Void> cancel(
            @CurrentUser AuthenticatedUser currentUser,
            @PathVariable Long cookId
    ) {
        cookService.cancel(currentUser.userId(), cookId);
        return ResponseEntity.noContent().build();
    }

    /**
     * 받은 콕을 거절한다.
     *
     * <p>거절 API가 꺼져 있거나 {@value #REJECT_CONTRACT_HEADER} 헤더가 없으면 콕 상태나 호출자와 무관하게
     * 없는 경로처럼 {@code NOT_FOUND}로 응답하고 아무것도 기록하지 않는다. 배포 전부터 열려 있던 이전 화면은
     * 서버에 거절이 기록된다는 안내 없이 같은 요청을 보내므로, 새 화면의 요청과 구분하기 위해서다.
     * 인증은 그 앞의 인터셉터가 처리한다.</p>
     */
    @PostMapping("/{cookId}/reject")
    public ResponseEntity<Void> reject(
            @CurrentUser AuthenticatedUser currentUser,
            @PathVariable Long cookId,
            @RequestHeader(name = REJECT_CONTRACT_HEADER, required = false) String rejectContract
    ) {
        if (!cookProperties.rejectEnabled() || !REJECT_CONTRACT_VERSION.equals(rejectContract)) {
            throw new ApiException(ErrorCode.NOT_FOUND);
        }
        cookService.reject(currentUser.userId(), cookId);
        return ResponseEntity.noContent().build();
    }
}
