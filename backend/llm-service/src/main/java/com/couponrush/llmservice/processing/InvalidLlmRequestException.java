package com.couponrush.llmservice.processing;

/** 읽을 수 없는 요청 메시지 (형식 오류, 계약 규칙 위반). 재시도해도 같으므로 바로 llm-requests.DLT */
public class InvalidLlmRequestException extends RuntimeException {

    public InvalidLlmRequestException(String message, Throwable cause) {
        super(message, cause);
    }
}
