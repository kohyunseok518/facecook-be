package com.facecook.feedback.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 후기 제출 요청 본문. 공백만 있는 글은 받지 않는다. 글자 수 제한은 {@code feedback.content} 컬럼 길이와 같다.
 */
public record CreateFeedbackRequest(
        @NotBlank(message = "후기를 입력해 주세요.")
        @Size(max = 1000, message = "후기는 1000자 이하로 입력해 주세요.") String content
) {
}
