package com.couponrush.llmservice.client.anthropic;

import com.couponrush.llm.contract.EventCopyCandidate;
import com.couponrush.llm.contract.LlmResultMessage;

import java.util.List;
import java.util.Optional;

/**
 * 모델 출력 검증. 구조화된 출력이 JSON 모양은 보장하지만 개수·길이까지 지킨다는 보장은 없다 → 계약 규칙으로 다시 확인한다.
 * 통과하면 그대로 coupons 에 저장할 수 있는 길이다 (name ≤ 100, description ≤ 500).
 */
final class EventCopyOutputValidator {

    /** 검증 결과: 통과하면 candidates, 아니면 problem */
    record Result(List<EventCopyCandidate> candidates, String problem) {
        boolean valid() {
            return problem == null;
        }
    }

    private EventCopyOutputValidator() {
    }

    static Result validate(EventCopyOutput output) {
        if (output == null || output.candidates() == null) {
            return invalid("후보 목록이 없음");
        }
        if (output.candidates().size() != LlmResultMessage.CANDIDATE_COUNT) {
            return invalid("후보 수가 " + output.candidates().size() + "개 (" + LlmResultMessage.CANDIDATE_COUNT + "개여야 함)");
        }
        List<EventCopyCandidate> candidates = new java.util.ArrayList<>();
        for (EventCopyOutput.Candidate c : output.candidates()) {
            Optional<String> problem = problemOf(c);
            if (problem.isPresent()) {
                return invalid(problem.get());
            }
            candidates.add(new EventCopyCandidate(c.name().strip(), c.description().strip()));
        }
        return new Result(List.copyOf(candidates), null);
    }

    private static Optional<String> problemOf(EventCopyOutput.Candidate c) {
        if (c == null || c.name() == null || c.description() == null) {
            return Optional.of("빈 후보");
        }
        String name = c.name().strip();
        String description = c.description().strip();
        if (name.isEmpty() || name.length() > EventCopyCandidate.NAME_MAX) {
            return Optional.of("이름 길이 " + name.length() + "자 (1~" + EventCopyCandidate.NAME_MAX + ")");
        }
        if (description.isEmpty() || description.length() > EventCopyCandidate.DESCRIPTION_MAX) {
            return Optional.of("설명 길이 " + description.length() + "자 (1~" + EventCopyCandidate.DESCRIPTION_MAX + ")");
        }
        return Optional.empty();
    }

    private static Result invalid(String problem) {
        return new Result(List.of(), problem);
    }
}
