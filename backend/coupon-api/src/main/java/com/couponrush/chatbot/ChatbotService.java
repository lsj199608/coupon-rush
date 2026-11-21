package com.couponrush.chatbot;

import com.couponrush.chatbot.dto.ChatbotSessionResponse;
import com.couponrush.chatbot.dto.ChatbotSessionSummaryResponse;
import com.couponrush.chatbot.dto.ChatbotTurnResponse;
import com.couponrush.global.error.BusinessException;
import com.couponrush.global.error.ErrorCode;
import com.couponrush.llm.LlmRequest;
import com.couponrush.llm.LlmRequestService;
import com.couponrush.llm.LlmRequestStatus;
import com.couponrush.llm.contract.ChatbotInput;
import com.couponrush.llm.contract.LlmRequestMessage;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * [챗봇 대화 — coupon-api 쪽] 대화방·턴 저장과 질문 접수. 답은 llm-service 가 만들고(Kafka), 결과는 llm_requests 에 반영된다
 * (LlmResultHandler — 문구 생성·검색과 같은 경로). 이 클래스는 LLM 을 부르지 않는다.
 * <pre>
 * 질문 접수 (한 트랜잭션): 대화방 잠금 → 소유자 확인 → 답을 기다리는 턴이 있으면 409
 *                         → 예산·한도 → llm_requests PENDING + chatbot_turns 저장 → 커밋
 *            커밋 후:     llm-requests 발행 → 202 (실패면 요청 FAILED(PUBLISH_FAILED) + 503)
 * 조회:                   턴 + llm_requests 상태 (마감 지난 PENDING 은 그 자리에서 TIMEOUT)
 * </pre>
 * <b>누가 묻는지는 JWT 의 회원 ID 로만</b> 정한다 (요청 메시지의 requestedBy → C2 에서 mcp-server 의 X-Mcp-User-Id).
 * 질문 문장에 다른 회원 번호를 써도 그 회원으로 처리되지 않는다.
 */
@Service
public class ChatbotService {

    /** 대화방 조회 응답에 싣는 최근 턴 수 */
    public static final int TURNS_SHOWN = 50;
    /** 대화방 목록 개수 */
    static final int SESSIONS_SHOWN = 20;
    /**
     * 지난 대화를 고를 때 살펴보는 최근 턴 수. 답을 받은 턴만 보내므로(실패·대기는 뺌) HISTORY_MAX 보다 넉넉히 읽는다
     */
    private static final int HISTORY_SCAN = ChatbotInput.HISTORY_MAX * 2;

    private final ChatbotSessionRepository sessionRepository;
    private final ChatbotTurnRepository turnRepository;
    private final LlmRequestService llmRequestService;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public ChatbotService(ChatbotSessionRepository sessionRepository, ChatbotTurnRepository turnRepository,
                       LlmRequestService llmRequestService, TransactionTemplate transactionTemplate, Clock clock) {
        this.sessionRepository = sessionRepository;
        this.turnRepository = turnRepository;
        this.llmRequestService = llmRequestService;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
    }

    public ChatbotSessionResponse openSession(Long userId) {
        ChatbotSession session = sessionRepository.save(ChatbotSession.open(userId, now()));
        return new ChatbotSessionResponse(session.getId(), session.getCreatedAt(), session.getLastMessageAt(), List.of());
    }

    public List<ChatbotSessionSummaryResponse> listSessions(Long userId) {
        return sessionRepository.findSummaries(userId, Limit.of(SESSIONS_SHOWN)).stream()
                .map(ChatbotSessionSummaryResponse::of)
                .toList();
    }

    public ChatbotSessionResponse getSession(Long sessionId, Long userId) {
        ChatbotSession session = findOwned(sessionId, userId);
        List<ChatbotTurn> turns = new ArrayList<>(turnRepository.findBySessionIdOrderByIdDesc(sessionId, Limit.of(TURNS_SHOWN)));
        Collections.reverse(turns);   // 화면은 오래된 것부터
        Map<UUID, LlmRequest> requests = llmRequestService.getChatbotRequests(turns.stream().map(ChatbotTurn::getLlmRequestId).toList());
        return new ChatbotSessionResponse(session.getId(), session.getCreatedAt(), session.getLastMessageAt(),
                turns.stream().map(turn -> toResponse(turn, requests)).toList());
    }

    public ChatbotTurnResponse getTurn(Long sessionId, Long turnId, Long userId) {
        findOwned(sessionId, userId);
        ChatbotTurn turn = turnRepository.findByIdAndSessionId(turnId, sessionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHATBOT_TURN_NOT_FOUND));
        return toResponse(turn, llmRequestService.getChatbotRequests(List.of(turn.getLlmRequestId())));
    }

    /**
     * 질문 접수. 202 로 PENDING 턴을 돌려주고, 화면은 GET .../turns/{turnId} 를 폴링한다.
     *
     * @throws BusinessException 404(남의 방·없는 방), 409(답을 기다리는 질문이 있음), 429(한도·예산), 503(발행 실패)
     */
    public ChatbotTurnResponse ask(Long sessionId, Long userId, String message) {
        Accepted accepted = transactionTemplate.execute(status -> accept(sessionId, userId, message));
        try {
            llmRequestService.publish(accepted.request());
        } catch (BusinessException e) {
            // 요청은 이미 FAILED(PUBLISH_FAILED) — 턴은 남아서 대화 기록에 "보내지 못함"으로 보인다
            throw new BusinessException(ErrorCode.CHATBOT_UNAVAILABLE);
        }
        return getTurn(sessionId, accepted.turnId(), userId);
    }

    private record Accepted(Long turnId, LlmRequestMessage request) {
    }

    private Accepted accept(Long sessionId, Long userId, String message) {
        ChatbotSession session = sessionRepository.findByIdForUpdate(sessionId)
                .filter(found -> found.isOwnedBy(userId))
                .orElseThrow(() -> new BusinessException(ErrorCode.CHATBOT_SESSION_NOT_FOUND));

        List<ChatbotTurn> recent = turnRepository.findBySessionIdOrderByIdDesc(sessionId, Limit.of(HISTORY_SCAN));
        Map<UUID, LlmRequest> requests = llmRequestService.getChatbotRequests(recent.stream().map(ChatbotTurn::getLlmRequestId).toList());
        if (!recent.isEmpty() && requests.get(recent.get(0).getLlmRequestId()).getStatus() == LlmRequestStatus.PENDING) {
            throw new BusinessException(ErrorCode.CHATBOT_TURN_IN_PROGRESS);
        }

        ChatbotInput input = new ChatbotInput(message, historyOf(recent, requests));
        LlmRequestMessage request;
        try {
            request = llmRequestService.prepareChatbot(userId, input);
        } catch (BusinessException e) {
            throw new BusinessException(chatbotCodeOf(e.getErrorCode()));
        }

        Instant now = request.requestedAt();
        ChatbotTurn turn = turnRepository.save(ChatbotTurn.of(sessionId, message, request.requestId(), now));
        session.touch(now);
        return new Accepted(turn.getId(), request);
    }

    /** 답을 받은 최근 턴만, 오래된 것부터 최대 HISTORY_MAX 개. recent 는 최근 것부터 */
    private List<com.couponrush.llm.contract.ChatbotTurn> historyOf(List<ChatbotTurn> recent, Map<UUID, LlmRequest> requests) {
        List<com.couponrush.llm.contract.ChatbotTurn> history = new ArrayList<>();
        for (ChatbotTurn turn : recent) {
            LlmRequest request = requests.get(turn.getLlmRequestId());
            if (request != null && request.getStatus() == LlmRequestStatus.COMPLETED) {
                history.add(new com.couponrush.llm.contract.ChatbotTurn(
                        turn.getQuestion(), llmRequestService.chatbotReplyOf(request).text()));
                if (history.size() == ChatbotInput.HISTORY_MAX) {
                    break;
                }
            }
        }
        Collections.reverse(history);
        return history;
    }

    /** LLM 요청 공통 거절 → 챗봇 화면용 코드 (메시지가 "문구 생성"이 아니라 챗봇 기준이 되게) */
    private static ErrorCode chatbotCodeOf(ErrorCode code) {
        return switch (code) {
            case LLM_RATE_LIMITED -> ErrorCode.CHATBOT_RATE_LIMITED;
            case LLM_DAILY_LIMIT_EXCEEDED -> ErrorCode.CHATBOT_DAILY_LIMIT_EXCEEDED;
            case LLM_BUDGET_EXCEEDED -> ErrorCode.CHATBOT_BUDGET_EXCEEDED;
            default -> ErrorCode.CHATBOT_UNAVAILABLE;
        };
    }

    private ChatbotTurnResponse toResponse(ChatbotTurn turn, Map<UUID, LlmRequest> requests) {
        LlmRequest request = requests.get(turn.getLlmRequestId());
        if (request == null) {
            // FK 가 있어 생기지 않는다
            throw new IllegalStateException("턴의 LLM 요청이 없습니다: turnId=" + turn.getId());
        }
        String answer = request.getStatus() == LlmRequestStatus.COMPLETED ? llmRequestService.chatbotReplyOf(request).text() : null;
        return ChatbotTurnResponse.of(turn, request, answer);
    }

    private ChatbotSession findOwned(Long sessionId, Long userId) {
        return sessionRepository.findById(sessionId)
                .filter(session -> session.isOwnedBy(userId))
                .orElseThrow(() -> new BusinessException(ErrorCode.CHATBOT_SESSION_NOT_FOUND));
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
