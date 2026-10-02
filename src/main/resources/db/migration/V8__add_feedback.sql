-- 서비스 종료 화면에서 받는 익명 후기(facecook-be#155). 누가 썼는지 알 수 있는 값(user_id·IP)은 두지 않는다.
CREATE TABLE feedback (
    feedback_id  BIGINT AUTO_INCREMENT PRIMARY KEY,
    content      VARCHAR(1000) NOT NULL,
    created_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);
