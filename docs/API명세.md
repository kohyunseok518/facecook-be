# face 콕 — API 명세

정리 기준일: 2026-09-16

- 인증: 세션 쿠키(HttpOnly) 기반. `[참가자]` = 로그인한 참가자 세션 필요, `[관리자]` = 관리자 세션 필요, `[슈퍼]` = 총학생회 슈퍼 세션 필요, `[공개]` = 인증 불필요
- 응답 포맷: JSON, 실패 시 `{ "code": "ERROR_CODE", "message": "..." }`
- 시각: 응답의 모든 시각 필드(`...At`)는 **한국 시간(KST) 기준, 오프셋 없는 문자열**이다(예: `"2026-09-16T13:10:00"`). 클라이언트는 이 값을 KST로 해석해야 한다. 서버는 시각을 `EventTime`으로 한국 시간으로 만들어 저장하고, DB가 기본값으로 채우는 컬럼도 연결 시간대(`+09:00`)로 한국 시간이 된다(#77).
- 정확한 필드 타입은 ERD 확정 후 갱신

## 1. 로그인 / 회원가입

| Method | Path | 설명 | 인증 |
| --- | --- | --- | --- |
| POST | `/api/auth/request-code` | 이메일 인증코드 발급 (`purpose: signup\|login`) | 공개 |
| POST | `/api/auth/verify-signup` | 인증코드 확인 + 비밀번호 설정 + 회원가입 (`email, code, password, agreedTerms[]`) | 공개 |
| POST | `/api/auth/login` | 이메일(또는 슈퍼계정 아이디)·비밀번호 로그인 | 공개 |
| POST | `/api/auth/verify-login` | 인증코드 확인 + 로그인 (기존 OTP 호환 경로) | 공개 |
| POST | `/api/auth/admin-login` | 관리자 로그인 (`adminId, password`) | 공개 |
| GET | `/api/auth/me` | 현재 세션 정보 조회 | 참가자/관리자/슈퍼 |
| POST | `/api/auth/logout` | 로그아웃 | 참가자/관리자/슈퍼 |

### 이메일 인증 요청/응답

`POST /api/auth/request-code`

```json
{
  "email": "user@example.com",
  "purpose": "signup"
}
```

- `purpose`: `signup` 또는 `login`
- 성공 응답:

```json
{
  "expiresInSeconds": 300,
  "resendAfterSeconds": 30
}
```

### 회원가입 인증 요청/응답

`POST /api/auth/verify-signup`

```json
{
  "email": "user@example.com",
  "code": "123456",
  "password": "password123",
  "agreedTerms": ["service", "privacy"]
}
```

- `password`: 8자 이상. 서버에는 BCrypt 해시만 저장한다.

`agreedTerms` 값:

| ID | 필수 여부 | 설명 |
| --- | --- | --- |
| `service` | 필수 | 서비스 이용약관 |
| `privacy` | 필수 | 개인정보 수집·이용 동의 |
| `photo` | 선택 | 프로필 사진 업로드 동의 |

### 비밀번호 로그인 요청/응답

`POST /api/auth/login`

```json
{
  "email": "user@example.com",
  "password": "password123"
}
```

- 이메일이 없거나 비밀번호가 틀리거나 기존 계정에 비밀번호가 설정되지 않은 경우 모두
  `INVALID_CREDENTIALS`를 반환한다.
- 정지 계정은 `SUSPENDED`를 반환한다.
- `email` 필드는 참가자/관리자 이메일뿐 아니라 슈퍼계정 아이디도 받는다.

### 기존 OTP 로그인 요청/응답

`POST /api/auth/verify-login`

```json
{
  "email": "user@example.com",
  "code": "123456"
}
```

`verify-signup`, `login`, `verify-login` 성공 응답:

```json
{
  "userId": 1,
  "email": "user@example.com",
  "role": "participant"
}
```

성공 응답에는 기존과 동일한 HttpOnly 세션 쿠키가 함께 발급된다.

## 2. 프로필

| Method | Path | 설명 | 인증 |
| --- | --- | --- | --- |
| POST | `/api/profile/photo/upload-url` | S3 업로드용 presigned URL 발급 (`contentType`) → `{ uploadUrl, photoUrl }` | 참가자 |
| GET | `/api/profile` | 내 프로필 조회 | 참가자 |
| POST | `/api/profile` | 필수+선택 프로필 최초 등록 (`nickname, gender, age(19 이상), mbti, hobby, bloodType, department?, grade?, bio?, idealType?, photo?`) | 참가자 |
| PATCH | `/api/profile` | 선택 항목만 수정 (`department?, grade?, bio?, photo?`) — 필수 필드는 요청 자체에 안 받음 | 참가자 |
| GET | `/api/profiles` | 참가자 목록 (본인 제외, 활동 상태인 참가자만 — 관리자·슈퍼·정지 계정 제외) | 참가자 |
| GET | `/api/profiles/filters?active=` | 실제 참가자가 가진 학과·MBTI·취미 값(정렬됨). `active=true`면 활동 중인 참가자만 대상. 관리자·슈퍼·정지 계정의 프로필은 빠진다 | 참가자 |
| GET | `/api/profiles/{userId}` | 특정 참가자 프로필 상세 | 참가자 |
| GET | `/api/stats` | 참가자용 `{ total, activeNow }` — 프로필이 있는 참가자 수, 그중 최근 15분 내 활동한 수. 관리자·슈퍼·정지 계정은 세지 않는다 | 참가자 |
| GET | `/api/departments` | 학과 정본을 학부별로 묶어서 반환 (`[{ college, majors[] }]`) | 참가자 |

### 프로필 응답의 활동 정보

`GET /api/profile`, `GET /api/profiles`, `GET /api/profiles/{userId}`의 각 항목에
`lastActiveAt`(ISO 8601 문자열, 활동 기록 없으면 `null`)과 `isActive`(boolean)가
포함된다. "활동 중" 기준은 서버가 관리하는 최근 15분 롤링 윈도우다
(`app.profile.activity.active-window-minutes`, 기본 15) — 클라이언트가 별도로
계산하지 않는다.

`GET /api/profiles`(탐색 목록)는 서버가 10초 동안 보관한 목록에서 요청자만 빼고 돌려준다
(`app.profile.participant-list-cache-seconds`, 기본 10). 그래서 새 참가자·프로필 수정·`isActive`가
다른 사용자 목록에 최대 10초 늦게 반영될 수 있다. 응답 형태는 같다.

`users.last_active_at`은 인증된 요청마다 갱신되지만, 같은 사용자에 대해
30초 안에 이미 갱신됐으면 다시 쓰지 않는다(디바운스) — 폴링이 잦은 화면이
많아 매 요청마다 쓰기를 발생시키지 않기 위함이다.

### 최소 나이(`age`) 검증

`POST /api/profile`의 `age`는 19 이상만 허용한다. 19 미만이거나 누락되면
`VALIDATION` 400을 반환한다. FE도 가입 화면에서 19세 미만을 막지만, API를
직접 호출하는 경로까지 막으려면 서버 검증이 필요해서 추가했다. 이미 저장된
프로필은 소급 검증하지 않는다(생성 시점에만 적용). `PATCH /api/profile`에는
애초에 나이 필드가 없다.

### 학과(`department`) 값 검증

`POST /api/profile`, `PATCH /api/profile`의 `department`는 `GET /api/departments`가
반환하는 학부 7개·학과 30개 목록 중 하나만 허용한다. 목록에 없는 값이면
`VALIDATION` 400을 반환한다. 자유 입력이던 시절 가입자에게 남은 값은 그대로
유지되며(기존 데이터는 소급 검증하지 않음), 새로 저장되는 값만 검증 대상이다.

facecook-fe는 이 목록을 하드코딩하지 않고 `GET /api/departments`로 받아
`DepartmentPicker`를 그린다 — BE의 `DepartmentCatalog`가 유일한 정본이다.

## 3. 콕찔러보기 / 매칭

| Method | Path | 설명 | 인증 |
| --- | --- | --- | --- |
| POST | `/api/cooks` | 콕 보내기 (`receiverId`) → 응답에 매칭 성사 여부 포함 | 참가자 |
| GET | `/api/cooks` | 보낸/받은 콕 목록 + 오늘/전체 사용량 통계 | 참가자 |
| DELETE | `/api/cooks/{cookId}` | 내가 보낸 콕 취소 (204) | 참가자 |
| POST | `/api/cooks/{cookId}/reject` | 내가 받은 콕 거절 (204, 본문 없음) | 참가자 |
| GET | `/api/matches` | 내 매칭 목록 (상대 프로필 + 최근 메시지 미리보기) | 참가자 |
| GET | `/api/matches/{matchId}` | 매칭 상세 | 참가자 |

**콕 보내기 실패 코드**: `SELF`(자기자신), `NOT_FOUND`(대상없음), `ALREADY_MATCHED`, `ALREADY_REJECTED`(내가 이미 거절한 상대), `DUPLICATE`, `DAILY_LIMIT`, `EVENT_LIMIT`, `COOK_BUSY`(429, 콕 전송이 몰려 서버당 동시 처리 수가 찬 상태로 0.5초를 기다려도 자리가 나지 않음. DB에 가지 않아 하루 사용량은 줄지 않으니 잠시 후 다시 보내면 된다)

검사 순서는 자기자신 → 상대 존재 → 이미 매칭 → 이미 거절한 상대 → 중복 → 개인 한도 → 행사 전체 한도이다.
상대가 나에게 보낸 콕이 취소됐거나 만료된 상태라면 맞콕으로 취급하지 않고 새 콕(pending)으로 저장된다.

### 콕 취소 (`DELETE /api/cooks/{cookId}`)

- 보낸 사람만 할 수 있다. 실패 코드: `NOT_FOUND`, `FORBIDDEN`(내가 보낸 콕이 아님), `ALREADY_MATCHED`, `ALREADY_EXPIRED`(레거시 만료 콕), `ALREADY_REJECTED`(상대가 거절한 콕 — 취소로 거절 상태를 덮어쓸 수 없다).
- 이미 취소한 콕을 다시 취소해도 204이다. 취소해도 오늘 사용 횟수는 돌아오지 않는다.

### 콕 거절 (`POST /api/cooks/{cookId}/reject`)

- 받은 사람만 할 수 있고, 성공하면 204(본문 없음)이다. 푸시를 보내지 않고 보낸 사람의 사용 횟수도 돌려주지 않는다.
- 요청 헤더 `X-Cook-Reject-Contract: 1`이 필요하다. 이 헤더가 없거나 서버의 거절 기능이 꺼져 있으면(환경변수 `COOK_REJECT_ENABLED`, 기본 꺼짐) 콕 상태·호출자와 무관하게 `NOT_FOUND`로 응답하고 아무것도 기록하지 않는다. 헤더 없는 요청은 서버 거절이 기록된다는 것을 모르는 이전 화면의 요청으로 본다.
- 켜져 있고 헤더가 있을 때의 검사 순서: 없는 콕 `NOT_FOUND` → 받은 사람이 아니면 `FORBIDDEN` → 상태. 상태별 응답: 대기 중이면 거절 처리(204), 이미 거절했으면 204, 매칭됐으면 `ALREADY_MATCHED`, 이미 취소됐으면 `NOT_FOUND`, 레거시 만료 콕이면 `ALREADY_EXPIRED`.
- 거절된 콕은 거절한 사람의 받은 콕 목록에서 빠지고, 거절당한 사람의 보낸 콕 목록에는 `status: "rejected"`로 남는다. 거절당한 사람은 그 콕을 취소하거나 같은 상대에게 다시 보낼 수 없다(`ALREADY_REJECTED`, `DUPLICATE`). 거절한 사람이 그 상대에게 콕을 보내려 해도 `ALREADY_REJECTED`이며 매칭되지 않는다.
- 되돌리기(거절 취소)는 없다.

### 콕 목록의 `status` 값

`pending`, `matched`, `rejected`(보낸 목록에만 나타남), 그리고 운영 DB에 남아 있는 레거시 `expired`. 취소된 콕(`cancelled`)은 양쪽 목록에서 빠진다.

## 4. 채팅

### REST

| Method | Path | 설명 | 인증 |
| --- | --- | --- | --- |
| GET | `/api/matches/{matchId}/messages?before={messageId}&limit=50` | 메시지 히스토리 조회 (페이지네이션) | 참가자(해당 매칭 당사자만) |

- 응답은 `messageId` 내림차순 배열이며, 다음 페이지는 마지막 항목의
  `messageId`를 `before`로 보낸다. `limit` 기본값은 50, 허용 범위는 1~100이다.
- 메시지 항목: `{ messageId, matchId, senderId, content, clientMessageId, sentAt }`

### WebSocket (STOMP)

| 구분 | 목적지 | 설명 |
| --- | --- | --- |
| CONNECT | `/ws` | 세션 쿠키로 인증, 연결 시 Redis 접속자 명단에 등록 |
| SUBSCRIBE | `/topic/chat/{matchId}` | 해당 채팅방 메시지 실시간 수신 — **구독 시점에 이 matchId 당사자인지 서버가 검증** |
| SEND | `/app/chat/{matchId}/send` | 메시지 전송, body: `{ content, clientMessageId }` |
| SUBSCRIBE | `/user/queue/chat-acks` | DB 저장이 끝난 SEND 결과 수신. 재전송이면 기존 메시지를 동일한 형식으로 반환 |
| — | — | 운영시간(09:00~18:00) 외 전송 시 `CLOSED` 에러 반환 |
| DISCONNECT | — | 연결 종료 시 Redis 접속자 명단에서 제거 |

구독은 위 두 목적지와 `/topic/mission/{matchId}`(미션 진행, 아래 미션 항목) 세 곳만
허용한다. 그 밖의 목적지를 구독하면 `FORBIDDEN` ERROR frame으로 거절한다.

`/topic/chat/{matchId}`와 `/user/queue/chat-acks`의 메시지 형식은 REST 메시지
항목과 같다. STOMP 처리 실패는 ERROR frame의 JSON body
`{ "code": "ERROR_CODE", "message": "..." }`로 반환한다.

**하트비트(필수)**: 서버는 CONNECTED 프레임에 `heart-beat:10000,10000`을 보낸다. 클라이언트는
CONNECT에 하트비트(10초 이하)를 설정해야 하며, 일정 시간(약 30초) 동안 아무 프레임도 오지 않으면 서버가
연결을 끊는다 — 네트워크 전환 등으로 조용히 끊긴 연결을 정리하기 위해서다. facecook-fe는
`chatSocket.ts`·`missionSocket.ts`에서 이미 10초로 보낸다.

**접속자 명단**: 푸시 알림(콕·매칭·채팅)은 대상자가 WebSocket에 연결돼 있으면 보내지 않는다. 명단의
기록은 연결을 가진 서버가 30초마다 연장하고, 연장이 멈추면(서버 강제 종료 등) 90초 뒤 만료된다(#81).

## 5. 미션

| Method | Path | 설명 | 인증 |
| --- | --- | --- | --- |
| GET | `/api/matches/{matchId}/mission` | 미션 진행상황 조회 (`currentStep`, `currentMission`, STEP별 완료시각) | 참가자(해당 매칭 당사자만) |
| GET | `/api/admin/missions` | 전체 매칭의 미션 진행 현황 목록(STEP별 배정 미션 포함) | 관리자 |
| POST | `/api/admin/missions/{matchId}/complete` | `expectedStep` STEP 완료 처리 → 다음 STEP 공개 (body: `{ expectedStep }`) | 관리자 |

참가자 미션 진행 응답(`GET /api/matches/{matchId}/mission` 및 미션 WebSocket 이벤트):

```json
{
  "matchId": 42,
  "currentStep": 2,
  "currentMission": "둘이 거울셀카 찍어오기",
  "step1CompletedAt": "2026-09-16T13:10:00",
  "step2CompletedAt": null,
  "step3CompletedAt": null
}
```

- `currentMission`은 현재 STEP에 고정 배정된 미션 내용이다. 모든 STEP을 완료했거나
  기능 배포 전에 이미 지나간 STEP이라 배정 데이터가 없으면 `null`이다.
- `SUBSCRIBE /topic/mission/{matchId}`로 같은 형식의 진행 변경 이벤트를 받는다.
  구독 시에도 해당 매칭 참가자인지 검증한다.

관리자 응답(`GET /api/admin/missions`의 배열 항목 및 완료 처리 응답):

```json
{
  "matchId": 42,
  "userAId": 10,
  "userBId": 11,
  "matchedAt": "2026-09-16T12:00:00",
  "currentStep": 2,
  "step1Mission": "학과 부스 참여하고 스탬프 하나 같이 받아오기",
  "step2Mission": "둘이 거울셀카 찍어오기",
  "step3Mission": "축제 음식 한가지 함께 먹기",
  "step1CompletedAt": "2026-09-16T13:10:00",
  "step1CompletedBy": 1,
  "step2CompletedAt": null,
  "step2CompletedBy": null,
  "step3CompletedAt": null,
  "step3CompletedBy": null
}
```

- `step1Mission`·`step2Mission`·`step3Mission`은 각 STEP에 고정 배정된 내용이다.
  기능 배포 전에 이미 완료되어 실제 배정 기록이 없는 STEP은 `null`일 수 있다.
- 관리자 목록은 한 매칭의 배정 데이터 처리에 실패해도 나머지 정상 매칭을 반환하며,
  문제가 있는 매칭은 목록에서 제외하고 서버 로그에 남긴다.
- `GET /api/admin/missions?includeExcluded=true`로 요청하면 위 배열 대신
  `{ "items": [...], "excluded": [...] }` 객체를 돌려준다. `excluded`는
  목록에서 제외된 매칭이다(`{ "matchId": 10, "reason": "NO_TEMPLATE" }`).
  `reason`은 `NO_TEMPLATE`(배정할 미션 묶음·템플릿을 찾지 못함) 또는
  `UNKNOWN`(그 밖의 예상 외 오류) 중 하나다. 파라미터 없이 요청하면(구 FE
  호환) 지금처럼 배열만 돌려준다.
- `POST /api/admin/missions/{matchId}/complete`의 `expectedStep`은 관리자가
  화면에서 확인한 STEP 번호(1~3)다. 서버의 현재 STEP과 다르면(이미 다른
  관리자가 처리했거나, 재시도로 중복 요청됐거나, 전체 완료된 경우 포함)
  `409 MISSION_STEP_MISMATCH`를 반환하고 아무것도 기록하지 않는다.
  `expectedStep`을 누락하거나 범위(1~3) 밖이면 `400 VALIDATION`이다.

## 6. 신고

| Method | Path | 설명 | 인증 |
| --- | --- | --- | --- |
| POST | `/api/reports` | 신고 접수 (`reportedUserId, reason, detail?`) | 참가자 |
| GET | `/api/admin/reports` | 신고 목록 조회 | 관리자 |
| GET | `/api/admin/reports/{reportId}` | 신고 상세 | 관리자 |
| POST | `/api/admin/reports/{reportId}/resolve` | 신고 처리 (`suspend: boolean`) | 관리자 |
| GET | `/api/admin/reports/{reportId}/chat` | 신고 관련 채팅 열람 | 관리자 |

## 7. 관리자 통계

| Method | Path | 설명 | 인증 |
| --- | --- | --- | --- |
| GET | `/api/admin/stats` | 가입자수/활성사용자/콕사용량/매칭수/미션완료수/대기신고수 | 관리자 |

응답:

```json
{
  "totalUsers": 214,
  "activeToday": 200,
  "totalCooks": 487,
  "totalMatches": 63,
  "missionCleared": 21,
  "pendingReports": 2
}
```

- `activeToday`는 기본적으로 `Asia/Seoul` 기준 당일 `last_active_at`이 기록된
  계정 수다. `last_active_at`은 인증된 요청마다 30초 간격으로 갱신된다(2절 참고).
  관리자·슈퍼 계정도 당일 요청을 보냈으면 포함된다.
- `ADMIN_STATS_ACTIVE_USER_CRITERION=STATUS`로 설정하면 `users.status = ACTIVE`인
  계정 수(정지되지 않은 전체 계정)를 집계한다.
- 참가자용 `GET /api/stats`의 `activeNow`(최근 15분, 프로필이 있는 활동 상태 참가자)와는
  세는 대상이 달라서 두 수치가 다르게 나오는 게 정상이다.

## 8. 슈퍼 계정

부스 관리자와 별개의 총학생회 계정이다. 기존 관리자 API는 쓰지 못하고,
아래 조회만 가능하다.

| Method | Path | 설명 | 인증 |
| --- | --- | --- | --- |
| GET | `/api/super/users` | 전체 사용자 목록 | 슈퍼 |
| GET | `/api/super/chats` | 전체 채팅방 목록 | 슈퍼 |
| GET | `/api/super/chats/{matchId}/messages` | 해당 채팅방 메시지 열람 (`before?`, `limit?`) | 슈퍼 |

사용자 목록 항목:

```json
{
  "userId": 1,
  "email": "user@example.com",
  "role": "participant",
  "status": "active",
  "createdAt": "2026-09-16T12:00:00",
  "lastActiveAt": "2026-09-16T13:10:00",
  "nickname": "지호",
  "gender": "남성",
  "age": 24,
  "mbti": "ENFP",
  "hobby": "카페",
  "bloodType": "A형",
  "department": "컴퓨터공학과",
  "grade": "3학년",
  "bio": "안녕",
  "idealType": "다정한 사람",
  "photo": null
}
```

- 프로필이 없는 계정(관리자·슈퍼 등)은 닉네임·성별 같은 프로필 필드가 `null`이다.

채팅방 목록 항목:

```json
{
  "matchId": 42,
  "matchedAt": "2026-09-16T12:00:00",
  "userA": { "userId": 10, "email": "a@example.com", "nickname": "지호", "gender": "남성", "photo": null },
  "userB": { "userId": 11, "email": "b@example.com", "nickname": "수아", "gender": "여성", "photo": null },
  "lastMessage": "안녕",
  "lastMessageAt": "2026-09-16T13:10:00"
}
```

메시지 응답은 `GET /api/matches/{matchId}/messages`와 같다.

## 9. 알림 (웹 푸시)

| Method | Path | 설명 | 인증 |
| --- | --- | --- | --- |
| GET | `/api/push/vapid-public-key` | 브라우저 구독 생성에 사용할 VAPID 공개키 조회 | 참가자 |
| POST | `/api/push/subscribe` | 브라우저 푸시 구독 정보 등록 (`endpoint, keys`) | 참가자 |
| DELETE | `/api/push/subscribe` | 현재 사용자의 모든 기기 구독 해제 | 참가자 |

`GET /api/push/vapid-public-key` 응답:

```json
{
  "publicKey": "base64url-encoded-vapid-public-key"
}
```

`POST /api/push/subscribe` 요청:

```json
{
  "endpoint": "https://push.example/subscription",
  "keys": {
    "p256dh": "browser-public-key",
    "auth": "browser-auth-secret"
  }
}
```

- 동일한 `(user_id, endpoint)`를 재등록하면 `keys`를 갱신한다.
- 등록과 해제 성공 응답은 모두 `204 No Content`이다.

발송 전용 엔드포인트는 없음 — 콕/매칭/메시지 이벤트 발생 시 서버가 내부적으로 판단해 자동 발송(기능명세 8번 참고).

## 10. 공통 에러 코드

| 코드 | 상황 |
| --- | --- |
| `UNAUTHORIZED` | 로그인 필요 |
| `FORBIDDEN` | 권한 없음 (예: 남의 매칭/신고 접근 시도) |
| `SUSPENDED` | 정지된 계정 |
| `VALIDATION` | 요청값 오류 (파라미터 타입 불일치·필수 파라미터 누락 포함) |
| `NOT_FOUND` | 대상 없음 |
| `INVALID_CREDENTIALS` | 참가자 이메일 또는 비밀번호 불일치(비밀번호 미설정 기존 계정 포함) |

지원하지 않는 HTTP 메서드는 `405`(`Allow` 헤더 포함), 지원하지 않는 Content-Type은
`415`(`Accept` 헤더 포함)로 본문 없이 응답한다.

## 11. 이번 문서 범위 밖

- 정확한 필드 타입·nullable 여부는 ERD 확정 후 갱신
- 레포 분리 후 실제 base URL(프론트→백엔드 호출 주소) 확정 필요
