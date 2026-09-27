# face 콕 — ERD (MySQL)

정리 기준일: 2026-09-16

## 1. 설계 결정 사항

기존 Supabase(PostgreSQL) 스키마를 MySQL 기준으로 재정리하면서 바뀐 부분이다.

| 항목 | 기존 | 이번 결정 | 이유 |
| --- | --- | --- | --- |
| `user_id` 타입 | UUID | **BIGINT AUTO_INCREMENT** | Supabase Auth 연동 전제로 UUID였는데, 이제 Auth 자체를 안 씀. Spring+JPA 기본 패턴과도 더 자연스러움 |
| 미션 진행상황 | (검토) 별도 테이블 vs 합침 | **`match_info`에 합침** | STEP 1~3 고정이라 확장성보다 단순함 우선, 기존 검증된 방식 재사용 |
| 랜덤 미션 배정 | 없음 | **템플릿과 매칭별 배정을 분리** | STEP별 후보를 관리하고 매칭마다 선택된 미션을 영구 고정 |
| `report.reason` | "카테고리\n상세"로 한 컬럼에 합쳐 저장 | **`reason`/`detail` 컬럼 분리** | 새로 짜는 거라 굳이 문자열 합치기 안 해도 됨 |
| 메시지 멱등성 | 없음 | **`message.client_message_id` 추가** | ACK 미수신 시 재전송해도 중복 저장 안 되게 |
| 웹 푸시 | 없음 | **`push_subscription` 테이블 신설** | |
| 참가자 비밀번호 | 이메일 OTP 로그인만 사용 | **`users.password_hash`에 BCrypt 해시 저장** | 최초 가입 때 이메일을 인증하고 이후에는 비밀번호로 로그인 |

**추가 확인 필요한 것 (팀 논의 필요):** 관리자 계정을 `users` 테이블에 실제 row로 둘지. 예전 Supabase 코드는 관리자가 `.env` 공유 계정이라 `users`에 없었고, 그래서 `match_info.step1_completed_by`, `report.reviewed_by`가 항상 NULL이었다. 이번엔 관리자도 `users`에 `role='admin'`으로 실제 row를 두면, **누가 승인/처리했는지 제대로 기록**할 수 있다. 이렇게 가는 걸 추천하지만 로그인 설계 담당과 합의 필요.

## 2. ER 다이어그램

```mermaid
erDiagram
    users ||--o| profile : "1:1"
    users ||--o{ cook : "sender"
    users ||--o{ cook : "receiver"
    users ||--o{ match_info : "user_a/b"
    users ||--o{ message : "sender"
    users ||--o{ report : "reporter/reported/reviewer"
    users ||--o{ push_subscription : "구독"
    match_info ||--o{ cook : "성사된 콕"
    match_info ||--o{ message : "채팅"
    match_info ||--o{ match_mission_assignment : "STEP별 배정"
    mission_template ||--o{ match_mission_assignment : "선택된 템플릿"

    users {
        bigint user_id PK
        varchar email UK
        varchar password_hash
        varchar role
        varchar status
        datetime agreed_privacy_at
        datetime agreed_terms_at
        datetime last_active_at
        datetime created_at
    }
    profile {
        bigint user_id PK
        varchar nickname
        varchar gender
        int age
        varchar mbti
        varchar hobby
        varchar blood_type
        varchar department
        varchar grade
        varchar bio
        varchar photo_url
        varchar ideal_type
        datetime created_at
        datetime updated_at
    }
    match_info {
        bigint match_id PK
        bigint user_a_id FK
        bigint user_b_id FK
        datetime matched_at
        int current_step
        datetime step1_completed_at
        bigint step1_completed_by FK
        datetime step2_completed_at
        bigint step2_completed_by FK
        datetime step3_completed_at
        bigint step3_completed_by FK
    }
    mission_template {
        bigint mission_template_id PK
        int step
        varchar content
    }
    match_mission_assignment {
        bigint assignment_id PK
        bigint match_id FK
        int step
        bigint mission_template_id FK
        datetime assigned_at
    }
    cook {
        bigint cook_id PK
        bigint sender_id FK
        bigint receiver_id FK
        bigint match_id FK
        varchar status
        datetime sent_at
    }
    message {
        bigint message_id PK
        bigint match_id FK
        bigint sender_id FK
        varchar content
        char client_message_id UK
        datetime sent_at
    }
    report {
        bigint report_id PK
        bigint reporter_id FK
        bigint reported_user_id FK
        varchar reason
        varchar detail
        varchar status
        bigint reviewed_by FK
        datetime reviewed_at
        datetime created_at
    }
    push_subscription {
        bigint subscription_id PK
        bigint user_id FK
        varchar endpoint
        varchar p256dh
        varchar auth
        datetime created_at
    }
```

## 3. 테이블 상세

### users
| 컬럼 | 타입 | 제약 | 설명 |
| --- | --- | --- | --- |
| user_id | BIGINT | PK, AUTO_INCREMENT | |
| email | VARCHAR(255) | NOT NULL, UNIQUE | |
| password_hash | VARCHAR(255) | NULL | BCrypt 해시. 기존 OTP 전용 계정 호환을 위해 DB는 NULL 허용, 신규 가입은 애플리케이션에서 필수 저장 |
| role | VARCHAR(20) | NOT NULL, DEFAULT 'participant' | participant / admin / super |
| status | VARCHAR(20) | NOT NULL, DEFAULT 'active' | active / suspended |
| agreed_privacy_at | DATETIME | NULL | |
| agreed_terms_at | DATETIME | NULL | |
| last_active_at | DATETIME | NULL | |
| created_at | DATETIME | NOT NULL, DEFAULT CURRENT_TIMESTAMP | |

### profile
| 컬럼 | 타입 | 제약 | 설명 |
| --- | --- | --- | --- |
| user_id | BIGINT | PK, FK→users | |
| nickname | VARCHAR(50) | NOT NULL | 필수, 수정불가 |
| gender | VARCHAR(10) | NOT NULL | 필수, 수정불가 |
| age | INT | NOT NULL | 필수, 수정불가 |
| mbti | VARCHAR(4) | NOT NULL | 필수, 수정불가 |
| hobby | VARCHAR(255) | NOT NULL | 필수, 수정불가 |
| blood_type | VARCHAR(5) | NOT NULL | 필수, 수정불가 |
| department | VARCHAR(100) | NULL | 선택, 수정가능 |
| grade | VARCHAR(20) | NULL | 선택, 수정가능 |
| bio | VARCHAR(500) | NULL | 선택, 수정가능 |
| photo_url | VARCHAR(500) | NULL | 선택, 수정가능, S3 URL |
| ideal_type | VARCHAR(255) | NULL | 선택, 수정가능 |
| created_at | DATETIME | NOT NULL, DEFAULT CURRENT_TIMESTAMP | |
| updated_at | DATETIME | NOT NULL, ON UPDATE CURRENT_TIMESTAMP | |

### match_info
| 컬럼 | 타입 | 제약 | 설명 |
| --- | --- | --- | --- |
| match_id | BIGINT | PK, AUTO_INCREMENT | |
| user_a_id | BIGINT | NOT NULL, FK→users | |
| user_b_id | BIGINT | NOT NULL, FK→users | |
| matched_at | DATETIME | NOT NULL, DEFAULT CURRENT_TIMESTAMP | |
| current_step | INT | NOT NULL, DEFAULT 1 | 1~3, 4=완료 |
| step1_completed_at | DATETIME | NULL | |
| step1_completed_by | BIGINT | NULL, FK→users | 처리한 관리자 |
| step2_completed_at | DATETIME | NULL | |
| step2_completed_by | BIGINT | NULL, FK→users | |
| step3_completed_at | DATETIME | NULL | |
| step3_completed_by | BIGINT | NULL, FK→users | |

### mission_template
| 컬럼 | 타입 | 제약 | 설명 |
| --- | --- | --- | --- |
| mission_template_id | BIGINT | PK, AUTO_INCREMENT | |
| step | INT | NOT NULL, CHECK 1~3, INDEX | 템플릿이 사용될 STEP |
| content | VARCHAR(1000) | NOT NULL | 미션 내용 |

### match_mission_assignment
| 컬럼 | 타입 | 제약 | 설명 |
| --- | --- | --- | --- |
| assignment_id | BIGINT | PK, AUTO_INCREMENT | |
| match_id | BIGINT | NOT NULL, FK→match_info, ON DELETE CASCADE | 배정 대상 매칭 |
| step | INT | NOT NULL, CHECK 1~3 | 배정 STEP |
| mission_template_id | BIGINT | NOT NULL, FK→mission_template, INDEX | 선택된 템플릿 |
| assigned_at | DATETIME | NOT NULL, DEFAULT CURRENT_TIMESTAMP | 배정 시각 |
| | | UNIQUE(match_id, step) | 매칭의 STEP마다 하나만 고정 배정 |

### cook
| 컬럼 | 타입 | 제약 | 설명 |
| --- | --- | --- | --- |
| cook_id | BIGINT | PK, AUTO_INCREMENT | |
| sender_id | BIGINT | NOT NULL, FK→users | |
| receiver_id | BIGINT | NOT NULL, FK→users | |
| match_id | BIGINT | NULL, FK→match_info | 매칭 성사 시에만 값 있음 |
| status | VARCHAR(20) | NOT NULL, DEFAULT 'pending' | pending / matched / expired |
| sent_at | DATETIME | NOT NULL, DEFAULT CURRENT_TIMESTAMP | |
| | | UNIQUE(sender_id, receiver_id) | 같은 상대 재발송 불가 |
| | | INDEX(sent_at) | 행사일 그날 전체 콕 수 세기(V7) |

### message
| 컬럼 | 타입 | 제약 | 설명 |
| --- | --- | --- | --- |
| message_id | BIGINT | PK, AUTO_INCREMENT | |
| match_id | BIGINT | NOT NULL, FK→match_info | |
| sender_id | BIGINT | NOT NULL, FK→users | |
| content | VARCHAR(1000) | NOT NULL | |
| client_message_id | CHAR(36) | NOT NULL, UNIQUE | 클라이언트가 생성한 UUID, 재전송 중복 방지 |
| sent_at | DATETIME | NOT NULL, DEFAULT CURRENT_TIMESTAMP | INDEX(match_id, sent_at) |

### report
| 컬럼 | 타입 | 제약 | 설명 |
| --- | --- | --- | --- |
| report_id | BIGINT | PK, AUTO_INCREMENT | |
| reporter_id | BIGINT | NOT NULL, FK→users | |
| reported_user_id | BIGINT | NOT NULL, FK→users | |
| reason | VARCHAR(500) | NOT NULL | 신고 사유(카테고리) |
| detail | VARCHAR(1000) | NULL | 상세 사유 |
| status | VARCHAR(20) | NOT NULL, DEFAULT 'pending' | pending / reviewed |
| reviewed_by | BIGINT | NULL, FK→users | 처리한 관리자 |
| reviewed_at | DATETIME | NULL | |
| created_at | DATETIME | NOT NULL, DEFAULT CURRENT_TIMESTAMP | |

### push_subscription (신규)
| 컬럼 | 타입 | 제약 | 설명 |
| --- | --- | --- | --- |
| subscription_id | BIGINT | PK, AUTO_INCREMENT | |
| user_id | BIGINT | NOT NULL, FK→users | |
| endpoint | VARCHAR(500) | NOT NULL | 브라우저 푸시 서비스 주소 |
| p256dh | VARCHAR(255) | NOT NULL | 암호화 키 |
| auth | VARCHAR(255) | NOT NULL | 암호화 키 |
| created_at | DATETIME | NOT NULL, DEFAULT CURRENT_TIMESTAMP | |
| | | UNIQUE(user_id, endpoint) | 한 사용자가 여러 기기 구독 가능 |

## 4. Flyway 초기 마이그레이션 (V1__init.sql)

```sql
CREATE TABLE users (
    user_id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    email             VARCHAR(255) NOT NULL UNIQUE,
    role              VARCHAR(20)  NOT NULL DEFAULT 'participant',
    status            VARCHAR(20)  NOT NULL DEFAULT 'active',
    agreed_privacy_at DATETIME     NULL,
    agreed_terms_at   DATETIME     NULL,
    last_active_at    DATETIME     NULL,
    created_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE profile (
    user_id      BIGINT PRIMARY KEY,
    nickname     VARCHAR(50)  NOT NULL,
    gender       VARCHAR(10)  NOT NULL,
    age          INT          NOT NULL,
    mbti         VARCHAR(4)   NOT NULL,
    hobby        VARCHAR(255) NOT NULL,
    blood_type   VARCHAR(5)   NOT NULL,
    department   VARCHAR(100) NULL,
    grade        VARCHAR(20)  NULL,
    bio          VARCHAR(500) NULL,
    photo_url    VARCHAR(500) NULL,
    ideal_type   VARCHAR(255) NULL,
    created_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_profile_user FOREIGN KEY (user_id) REFERENCES users (user_id)
);

CREATE TABLE match_info (
    match_id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_a_id           BIGINT NOT NULL,
    user_b_id           BIGINT NOT NULL,
    matched_at          DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    current_step        INT NOT NULL DEFAULT 1,
    step1_completed_at  DATETIME NULL,
    step1_completed_by  BIGINT NULL,
    step2_completed_at  DATETIME NULL,
    step2_completed_by  BIGINT NULL,
    step3_completed_at  DATETIME NULL,
    step3_completed_by  BIGINT NULL,
    CONSTRAINT fk_match_user_a      FOREIGN KEY (user_a_id)           REFERENCES users (user_id),
    CONSTRAINT fk_match_user_b      FOREIGN KEY (user_b_id)           REFERENCES users (user_id),
    CONSTRAINT fk_match_step1_admin FOREIGN KEY (step1_completed_by)  REFERENCES users (user_id),
    CONSTRAINT fk_match_step2_admin FOREIGN KEY (step2_completed_by)  REFERENCES users (user_id),
    CONSTRAINT fk_match_step3_admin FOREIGN KEY (step3_completed_by)  REFERENCES users (user_id)
);

CREATE TABLE cook (
    cook_id     BIGINT AUTO_INCREMENT PRIMARY KEY,
    sender_id   BIGINT NOT NULL,
    receiver_id BIGINT NOT NULL,
    match_id    BIGINT NULL,
    status      VARCHAR(20) NOT NULL DEFAULT 'pending',
    sent_at     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_cook_sender_receiver UNIQUE (sender_id, receiver_id),
    CONSTRAINT fk_cook_sender   FOREIGN KEY (sender_id)   REFERENCES users (user_id),
    CONSTRAINT fk_cook_receiver FOREIGN KEY (receiver_id) REFERENCES users (user_id),
    CONSTRAINT fk_cook_match    FOREIGN KEY (match_id)    REFERENCES match_info (match_id)
);

CREATE TABLE message (
    message_id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    match_id           BIGINT NOT NULL,
    sender_id          BIGINT NOT NULL,
    content            VARCHAR(1000) NOT NULL,
    client_message_id  CHAR(36) NOT NULL,
    sent_at            DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_message_client_id UNIQUE (client_message_id),
    CONSTRAINT fk_message_match  FOREIGN KEY (match_id)  REFERENCES match_info (match_id),
    CONSTRAINT fk_message_sender FOREIGN KEY (sender_id) REFERENCES users (user_id)
);
CREATE INDEX idx_message_match_sent ON message (match_id, sent_at);

CREATE TABLE report (
    report_id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    reporter_id        BIGINT NOT NULL,
    reported_user_id   BIGINT NOT NULL,
    reason             VARCHAR(500) NOT NULL,
    detail             VARCHAR(1000) NULL,
    status             VARCHAR(20) NOT NULL DEFAULT 'pending',
    reviewed_by        BIGINT NULL,
    reviewed_at        DATETIME NULL,
    created_at         DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_report_reporter FOREIGN KEY (reporter_id)      REFERENCES users (user_id),
    CONSTRAINT fk_report_reported FOREIGN KEY (reported_user_id) REFERENCES users (user_id),
    CONSTRAINT fk_report_reviewer FOREIGN KEY (reviewed_by)      REFERENCES users (user_id)
);

CREATE TABLE push_subscription (
    subscription_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id         BIGINT NOT NULL,
    endpoint        VARCHAR(500) NOT NULL,
    p256dh          VARCHAR(255) NOT NULL,
    auth            VARCHAR(255) NOT NULL,
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_push_user_endpoint UNIQUE (user_id, endpoint),
    CONSTRAINT fk_push_user FOREIGN KEY (user_id) REFERENCES users (user_id)
);
```

## 5. 이후 마이그레이션

`V2__add_password_hash_to_users.sql`은 기존 테스트 계정과의 호환성을 위해
nullable인 `users.password_hash VARCHAR(255)`를 추가한다. 신규 회원가입은
애플리케이션 레벨에서 8자 이상 비밀번호와 BCrypt 해시 저장을 강제한다.

`V4__add_random_missions.sql`은 랜덤 미션 템플릿과 매칭별 고정 배정을 추가한다.
테이블 정의는 다음과 같다(같은 마이그레이션의 STEP별 초기 템플릿 데이터는 생략).

```sql
CREATE TABLE mission_template (
    mission_template_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    step                INT NOT NULL,
    content             VARCHAR(1000) NOT NULL,
    CONSTRAINT chk_mission_template_step CHECK (step BETWEEN 1 AND 3)
);
CREATE INDEX idx_mission_template_step ON mission_template (step);

CREATE TABLE match_mission_assignment (
    assignment_id       BIGINT AUTO_INCREMENT PRIMARY KEY,
    match_id            BIGINT NOT NULL,
    step                INT NOT NULL,
    mission_template_id BIGINT NOT NULL,
    assigned_at         DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_match_mission_step UNIQUE (match_id, step),
    CONSTRAINT chk_match_mission_step CHECK (step BETWEEN 1 AND 3),
    CONSTRAINT fk_assignment_match FOREIGN KEY (match_id) REFERENCES match_info (match_id) ON DELETE CASCADE,
    CONSTRAINT fk_assignment_template FOREIGN KEY (mission_template_id) REFERENCES mission_template (mission_template_id)
);
CREATE INDEX idx_assignment_template ON match_mission_assignment (mission_template_id);
```

`V5__add_event_limit_lock.sql`은 행사 전체 하루 콕 한도의 동시성 잠금용 테이블을 추가한다. 데이터는 없고
행 하나(`lock_id = 1`)만 있다. 한도가 있는 날의 콕 전송이 이 행을 `FOR UPDATE`로 잠가서, 서로 다른 사용자
쌍의 전송도 한 번에 하나씩만 "전체 건수 확인 → 저장"을 하게 한다. 한도 값이나 건수는 저장하지 않는다.

```sql
CREATE TABLE event_limit_lock (
    lock_id INT PRIMARY KEY
);
INSERT INTO event_limit_lock (lock_id) VALUES (1);
```

`V6__replace_mission_templates_with_bundles.sql`은 미션 템플릿을 묶음(bundle) 구조로 교체한다. 기존
STEP1 82개·STEP2 49개·STEP3 19개가 독립 풀이던 것을, 행 하나가 STEP1~3 한 세트인 82묶음(246행)으로 바꾼다.
고유 문구 수는 STEP1 82개, STEP2 48개, STEP3 19개다(STEP2·STEP3는 같은 활동 문구가 여러 묶음에 재사용된다).
기존 `match_mission_assignment`(마이그레이션 전 데이터)와 `mission_template`을 모두 지우고 새로 채운다
(행사 전 데이터 초기화와 맞물려 있어 호환을 고려하지 않는다).

```sql
ALTER TABLE mission_template
    ADD COLUMN bundle_id BIGINT NOT NULL AFTER mission_template_id;
ALTER TABLE mission_template
    ADD CONSTRAINT uq_mission_template_bundle_step UNIQUE (bundle_id, step);
```

`MissionAssignmentWriter`는 매칭 하나에 묶음 하나를 통째로 배정한다: 이미 배정된 STEP이 있으면 그 묶음을,
없으면 묶음을 무작위로 골라 STEP1~3을 같은 묶음에서 채운다.

`V7__add_cook_sent_at_index.sql`은 `cook.sent_at`에 인덱스를 추가한다. 행사일 콕 전송은 `event_limit_lock`을
쥔 채로 그날 전체 콕 수를 세는데, 인덱스가 없어 표 전체를 읽었다. 잠금을 쥐는 시간이 콕 전송의 초당 처리
한계라서 그날 범위만 읽게 한다.

```sql
CREATE INDEX idx_cook_sent_at ON cook (sent_at);
```

## 6. 이번 문서 범위 밖

- 관리자 계정을 `users`에 실제로 둘지 여부 — 로그인 설계 담당과 별도 합의 필요
- 인덱스 튜닝(부하테스트 이후 필요시 추가)
