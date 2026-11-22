package com.couponrush.webchat.room;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * 지난 대화 HTTP 입구 (D6): GET /api/web-chat/rooms/{eventId}/messages?beforeId=&size=
 * <p>
 * 공개 — 방 구독이 비로그인도 되는 것과 같다 (이벤트 페이지가 공개). 에러는 coupon-api 와 같은 {"code","message"}.
 * <p>
 * 경로 /api/web-chat/** 중 rooms 는 이 서비스, tickets 는 coupon-api 다 — 프록시(Vite·배포 시 게이트웨이)가
 * /api/web-chat/rooms 를 8083 으로, 나머지 /api 를 8080 으로 보낸다(D4). 사용자 입장에서 "채팅 API"를 한 이름 아래 둔다.
 */
@RestController
public class WebChatHistoryController {

    private static final Logger log = LoggerFactory.getLogger(WebChatHistoryController.class);

    private final WebChatHistoryService historyService;

    public WebChatHistoryController(WebChatHistoryService historyService) {
        this.historyService = historyService;
    }

    @GetMapping("/api/web-chat/rooms/{eventId}/messages")
    public WebChatHistoryPage messages(@PathVariable long eventId,
                                       @RequestParam(required = false) Long beforeId,
                                       @RequestParam(required = false) Integer size) {
        return historyService.getPage(eventId, beforeId, size);
    }

    @ExceptionHandler(WebChatRejectedException.class)
    ResponseEntity<WebChatError> handleRejected(WebChatRejectedException e) {
        return ResponseEntity.badRequest().body(WebChatError.of(e.code()));
    }

    /** /rooms/abc, ?size=x 처럼 숫자가 아님. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<WebChatError> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return ResponseEntity.badRequest().body(WebChatError.of(WebChatErrorCode.WEB_CHAT_INVALID_REQUEST));
    }

    /** DB 장애 — 대화(WebSocket)는 DB 없이 계속되고, 지난 대화만 못 불러온다. */
    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<WebChatError> handleDataAccess(DataAccessException e) {
        log.error("지난 대화 조회 실패", e);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(WebChatError.of(WebChatErrorCode.WEB_CHAT_HISTORY_UNAVAILABLE));
    }
}
