package com.couponrush.chat;

import com.couponrush.chat.dto.ChatSessionResponse;
import com.couponrush.chat.dto.ChatSessionSummaryResponse;
import com.couponrush.chat.dto.ChatTurnResponse;
import com.couponrush.global.error.BusinessException;
import com.couponrush.global.error.ErrorCode;
import com.couponrush.llm.LlmRequest;
import com.couponrush.llm.LlmRequestService;
import com.couponrush.llm.LlmRequestStatus;
import com.couponrush.llm.contract.ChatInput;
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
 *                         → 예산·한도 → llm_requests PENDING + chat_turns 저장 → 커밋
 *            커밋 후:     llm-requests 발행 → 202 (실패면 요청 FAILED(PUBLISH_FAILED) + 503)
 * 조회:                   턴 + llm_requests 상태 (마감 지난 PENDING 은 그 자리에서 TIMEOUT)
 * </pre>
 * <b>누가 묻는지는 JWT 의 회원 ID 로만</b> 정한다 (요청 메시지의 requestedBy → C2 에서 mcp-server 의 X-Mcp-User-Id).
 * 질문 문장에 다른 회원 번호를 써도 그 회원으로 처리되지 않는다.
 */
@Service
public class ChatService {

    /** 대화방 조회 응답에 싣는 최근 턴 수 */
    public static final int TURNS_SHOWN = 50;
    /** 대화방 목록 개수 */
    static final int SESSIONS_SHOWN = 20;
    /**
     * 지난 대화를 고를 때 살펴보는 최근 턴 수. 답을 받은 턴만 보내므로(실패·대기는 뺌) HISTORY_MAX 보다 넉넉히 읽는다
     */
    private static final int HISTORY_SCAN = ChatInput.HISTORY_MAX * 2;

    private final ChatSessionRepository sessionRepository;
    private final ChatTurnRepository turnRepository;
    private final LlmRequestService llmRequestService;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public ChatService(ChatSessionRepository sessionRepository, ChatTurnRepository turnRepository,
                       LlmRequestService llmRequestService, TransactionTemplate transactionTemplate, Clock clock) {
        this.sessionRepository = sessionRepository;
        this.turnRepository = turnRepository;
        this.llmRequestService = llmRequestService;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
    }

    public ChatSessionResponse openSession(Long userId) {
        ChatSession session = sessionRepository.save(ChatSession.open(userId, now()));
        return new ChatSessionResponse(session.getId(), session.getCreatedAt(), session.getLastMessageAt(), List.of());
    }

    public List<ChatSessionSummaryResponse> listSessions(Long userId) {
        return sessionRepository.findSummaries(userId, Limit.of(SESSIONS_SHOWN)).stream()
                .map(ChatSessionSummaryResponse::of)
                .toList();
    }

    public ChatSessionResponse getSession(Long sessionId, Long userId) {
        ChatSession session = findOwned(sessionId, userId);
        List<ChatTurn> turns = new ArrayList<>(turnRepository.findBySessionIdOrderByIdDesc(sessionId, Limit.of(TURNS_SHOWN)));
        Collections.reverse(turns);   // 화면은 오래된 것부터
        Map<UUID, LlmRequest> requests = llmRequestService.getChats(turns.stream().map(ChatTurn::getLlmRequestId).toList());
        return new ChatSessionResponse(session.getId(), session.getCreatedAt(), session.getLastMessageAt(),
                turns.stream().map(turn -> toResponse(turn, requests)).toList());
    }

    public ChatTurnResponse getTurn(Long sessionId, Long turnId, Long userId) {
        findOwned(sessionId, userId);
        ChatTurn turn = turnRepository.findByIdAndSessionId(turnId, sessionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHAT_TURN_NOT_FOUND));
        return toResponse(turn, llmRequestService.getChats(List.of(turn.getLlmRequestId())));
    }

    /**
     * 질문 접수. 202 로 PENDING 턴을 돌려주고, 화면은 GET .../turns/{turnId} 를 폴링한다.
     *
     * @throws BusinessException 404(남의 방·없는 방), 409(답을 기다리는 질문이 있음), 429(한도·예산), 503(발행 실패)
     */
    public ChatTurnResponse ask(Long sessionId, Long userId, String message) {
        Accepted accepted = transactionTemplate.execute(status -> accept(sessionId, userId, message));
        try {
            llmRequestService.publish(accepted.request());
        } catch (BusinessException e) {
            // 요청은 이미 FAILED(PUBLISH_FAILED) — 턴은 남아서 대화 기록에 "보내지 못함"으로 보인다
            throw new BusinessException(ErrorCode.CHAT_UNAVAILABLE);
        }
        return getTurn(sessionId, accepted.turnId(), userId);
    }

    private record Accepted(Long turnId, LlmRequestMessage request) {
    }

    private Accepted accept(Long sessionId, Long userId, String message) {
        ChatSession session = sessionRepository.findByIdForUpdate(sessionId)
                .filter(found -> found.isOwnedBy(userId))
                .orElseThrow(() -> new BusinessException(ErrorCode.CHAT_SESSION_NOT_FOUND));

        List<ChatTurn> recent = turnRepository.findBySessionIdOrderByIdDesc(sessionId, Limit.of(HISTORY_SCAN));
        Map<UUID, LlmRequest> requests = llmRequestService.getChats(recent.stream().map(ChatTurn::getLlmRequestId).toList());
        if (!recent.isEmpty() && requests.get(recent.get(0).getLlmRequestId()).getStatus() == LlmRequestStatus.PENDING) {
            throw new BusinessException(ErrorCode.CHAT_TURN_IN_PROGRESS);
        }

        ChatInput input = new ChatInput(message, historyOf(recent, requests));
        LlmRequestMessage request;
        try {
            request = llmRequestService.prepareChat(userId, input);
        } catch (BusinessException e) {
            throw new BusinessException(chatCodeOf(e.getErrorCode()));
        }

        Instant now = request.requestedAt();
        ChatTurn turn = turnRepository.save(ChatTurn.of(sessionId, message, request.requestId(), now));
        session.touch(now);
        return new Accepted(turn.getId(), request);
    }

    /** 답을 받은 최근 턴만, 오래된 것부터 최대 HISTORY_MAX 개. recent 는 최근 것부터 */
    private List<com.couponrush.llm.contract.ChatTurn> historyOf(List<ChatTurn> recent, Map<UUID, LlmRequest> requests) {
        List<com.couponrush.llm.contract.ChatTurn> history = new ArrayList<>();
        for (ChatTurn turn : recent) {
            LlmRequest request = requests.get(turn.getLlmRequestId());
            if (request != null && request.getStatus() == LlmRequestStatus.COMPLETED) {
                history.add(new com.couponrush.llm.contract.ChatTurn(
                        turn.getQuestion(), llmRequestService.chatReplyOf(request).text()));
                if (history.size() == ChatInput.HISTORY_MAX) {
                    break;
                }
            }
        }
        Collections.reverse(history);
        return history;
    }

    /** LLM 요청 공통 거절 → 챗봇 화면용 코드 (메시지가 "문구 생성"이 아니라 챗봇 기준이 되게) */
    private static ErrorCode chatCodeOf(ErrorCode code) {
        return switch (code) {
            case LLM_RATE_LIMITED -> ErrorCode.CHAT_RATE_LIMITED;
            case LLM_DAILY_LIMIT_EXCEEDED -> ErrorCode.CHAT_DAILY_LIMIT_EXCEEDED;
            case LLM_BUDGET_EXCEEDED -> ErrorCode.CHAT_BUDGET_EXCEEDED;
            default -> ErrorCode.CHAT_UNAVAILABLE;
        };
    }

    private ChatTurnResponse toResponse(ChatTurn turn, Map<UUID, LlmRequest> requests) {
        LlmRequest request = requests.get(turn.getLlmRequestId());
        if (request == null) {
            // FK 가 있어 생기지 않는다
            throw new IllegalStateException("턴의 LLM 요청이 없습니다: turnId=" + turn.getId());
        }
        String answer = request.getStatus() == LlmRequestStatus.COMPLETED ? llmRequestService.chatReplyOf(request).text() : null;
        return ChatTurnResponse.of(turn, request, answer);
    }

    private ChatSession findOwned(Long sessionId, Long userId) {
        return sessionRepository.findById(sessionId)
                .filter(session -> session.isOwnedBy(userId))
                .orElseThrow(() -> new BusinessException(ErrorCode.CHAT_SESSION_NOT_FOUND));
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
