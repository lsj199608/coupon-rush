package com.couponrush.llm.contract;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Objects;

/**
 * 생성된 문구 후보 하나. 관리자가 골라서 폼에 넣고, 확인·수정한 뒤 저장해야 실제 이벤트가 된다 (자동 저장 없음).
 * <p>
 * 길이 상한은 coupons 테이블 컬럼과 같다 (name VARCHAR(100), description VARCHAR(500)). 그대로 저장해도 넘치지 않게
 * llm-service 가 모델 출력을 이 상한으로 검증하고, 이 레코드 생성자도 한 번 더 막는다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EventCopyCandidate(String name, String description) {

    public static final int NAME_MAX = 100;
    public static final int DESCRIPTION_MAX = 500;

    public EventCopyCandidate {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(description, "description");
        if (name.isBlank() || name.length() > NAME_MAX) {
            throw new IllegalArgumentException("name 은 1~" + NAME_MAX + "자여야 합니다");
        }
        if (description.isBlank() || description.length() > DESCRIPTION_MAX) {
            throw new IllegalArgumentException("description 은 1~" + DESCRIPTION_MAX + "자여야 합니다");
        }
    }
}
