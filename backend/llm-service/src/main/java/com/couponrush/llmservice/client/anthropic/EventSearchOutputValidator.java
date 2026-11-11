package com.couponrush.llmservice.client.anthropic;

import com.couponrush.llm.contract.EventSearchFilter;
import com.couponrush.llm.contract.EventSearchStatus;
import com.couponrush.llmservice.client.KstDates;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 검색 출력 검증 + 계약 형식으로 변환. 구조화된 출력은 JSON 모양만 보장하므로 개수·길이·날짜를 다시 확인한다.
 * 실패하면 problem 을 돌려주고(재생성 대상), 통과하면 그대로 결과 메시지에 실을 수 있는 {@link EventSearchFilter}.
 * <p>
 * 관대하게 고치는 것: 단어 앞뒤 공백, 빈 단어, 중복 단어·중복 상태 (의미가 같으므로).
 * 고치지 않고 거절하는 것: 단어 수·길이 초과, 날짜 형식 오류, 뒤집힌 범위 (어떻게 고쳐야 질문의 뜻이 되는지 알 수 없으므로).
 */
final class EventSearchOutputValidator {

    record Result(EventSearchFilter filter, String problem) {
        boolean valid() {
            return problem == null;
        }
    }

    private EventSearchOutputValidator() {
    }

    static Result validate(EventSearchOutput output) {
        if (output == null) {
            return invalid("출력이 없음");
        }
        Set<String> keywords = new LinkedHashSet<>();
        if (output.keywords() != null) {
            for (String keyword : output.keywords()) {
                if (keyword != null && !keyword.isBlank()) {
                    keywords.add(keyword.strip());
                }
            }
        }
        if (keywords.size() > EventSearchFilter.KEYWORD_COUNT_MAX) {
            return invalid("단어 " + keywords.size() + "개 (최대 " + EventSearchFilter.KEYWORD_COUNT_MAX + ")");
        }
        for (String keyword : keywords) {
            if (keyword.length() > EventSearchFilter.KEYWORD_MAX) {
                return invalid("단어 길이 " + keyword.length() + "자 (최대 " + EventSearchFilter.KEYWORD_MAX + ")");
            }
        }
        Set<EventSearchStatus> statuses = EnumSet.noneOf(EventSearchStatus.class);
        if (output.statuses() != null) {
            output.statuses().stream().filter(s -> s != null).forEach(statuses::add);
        }

        try {
            Instant[] start = range(output.startFromDate(), output.startToDate(), "발급 시작");
            Instant[] end = range(output.endFromDate(), output.endToDate(), "발급 종료");
            return new Result(new EventSearchFilter(List.copyOf(keywords), statuses,
                    start[0], start[1], end[0], end[1], output.availableOnly()), null);
        } catch (InvalidRange e) {
            return invalid(e.getMessage());
        }
    }

    /** 양끝 포함 날짜 [from, to] → 시각 [from 0시, to 다음 날 0시). 한쪽만 있어도 된다 */
    private static Instant[] range(String fromText, String toText, String label) {
        LocalDate from = date(fromText, label);
        LocalDate to = date(toText, label);
        if (from != null && to != null && from.isAfter(to)) {
            throw new InvalidRange(label + " 범위가 뒤집힘: " + from + " > " + to);
        }
        return new Instant[]{
                from == null ? null : KstDates.startOf(from),
                to == null ? null : KstDates.startOf(to.plusDays(1))};
    }

    private static LocalDate date(String text, String label) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(text.strip());
        } catch (DateTimeParseException e) {
            throw new InvalidRange(label + " 날짜 형식 오류: " + text);
        }
    }

    private static Result invalid(String problem) {
        return new Result(null, problem);
    }

    private static final class InvalidRange extends RuntimeException {
        InvalidRange(String message) {
            super(message);
        }
    }
}
