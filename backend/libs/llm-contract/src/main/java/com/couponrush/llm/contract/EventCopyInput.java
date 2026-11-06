package com.couponrush.llm.contract;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.Objects;

/**
 * 이벤트 문구 생성 입력. 관리자가 폼에 넣은 값 그대로 (아직 저장되지 않은 이벤트일 수 있으므로 쿠폰 ID 는 없다).
 * <p>
 * 이 값은 프롬프트에 <b>데이터로만</b> 들어간다 (구분자 안). 지시문으로 해석되지 않게 하는 것은 llm-service 의 책임.
 *
 * @param keywords      관리자가 쓴 핵심어·설명 (1~{@value #KEYWORDS_MAX}자)
 * @param tone          문구 톤
 * @param totalQuantity 총 수량 (문구에 "선착순 300명" 같은 표현을 넣기 위해)
 * @param issueStartAt  발급 시작 (없으면 null — 폼을 다 채우기 전에도 생성할 수 있게)
 * @param issueEndAt    발급 종료 (없으면 null)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EventCopyInput(
        String keywords,
        EventCopyTone tone,
        int totalQuantity,
        Instant issueStartAt,
        Instant issueEndAt
) {

    /** 입력 길이 상한 — 프롬프트(=비용) 크기를 묶는다. coupon-api 요청 검증도 이 값을 쓴다 */
    public static final int KEYWORDS_MAX = 200;

    public EventCopyInput {
        Objects.requireNonNull(keywords, "keywords");
        Objects.requireNonNull(tone, "tone");
        if (keywords.isBlank() || keywords.length() > KEYWORDS_MAX) {
            throw new IllegalArgumentException("keywords 는 1~" + KEYWORDS_MAX + "자여야 합니다");
        }
        if (totalQuantity < 1) {
            throw new IllegalArgumentException("totalQuantity 는 1 이상이어야 합니다");
        }
    }
}
