package com.couponrush.webchat.room;

import com.couponrush.webchat.config.WebChatProperties;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 지난 대화 조회 (D6). 최신 페이지부터, 위로 올리면 beforeId 로 그 이전 페이지.
 * <p>
 * 커서(id)를 쓰는 이유: OFFSET 페이지는 그사이 새 메시지가 오면 같은 메시지가 두 번 보이거나 빠지고, 뒤로 갈수록 느리다.
 * 저장은 방에 보낸 뒤 비동기라, 방금 온 메시지가 아직 없을 수 있다 — 화면은 먼저 구독하고 기록을 읽은 뒤 messageId 로 겹침을 없앤다(D4).
 */
@Service
public class WebChatHistoryService {

    private final WebChatMessageRepository repository;
    private final WebChatProperties.History properties;

    public WebChatHistoryService(WebChatMessageRepository repository, WebChatProperties properties) {
        this.repository = repository;
        this.properties = properties.history();
    }

    /**
     * @param beforeId 이 id 보다 오래된 메시지만 (없으면 최신부터)
     * @param size     없으면 기본 page-size
     * @throws WebChatRejectedException WEB_CHAT_INVALID_REQUEST — 이벤트 ID·beforeId 가 1 미만, size 가 1 ~ max-page-size 밖
     */
    public WebChatHistoryPage getPage(long eventId, Long beforeId, Integer size) {
        int limit = size == null ? properties.pageSize() : size;
        if (eventId < 1 || (beforeId != null && beforeId < 1) || limit < 1 || limit > properties.maxPageSize()) {
            throw new WebChatRejectedException(WebChatErrorCode.WEB_CHAT_INVALID_REQUEST);
        }
        // 하나 더 읽어 "더 있는지"를 안다 (COUNT 쿼리 없이)
        List<WebChatHistoryMessage> rows = repository.findLatest(eventId, beforeId, limit + 1);
        boolean hasMore = rows.size() > limit;
        List<WebChatHistoryMessage> page = new ArrayList<>(hasMore ? rows.subList(0, limit) : rows);
        Collections.reverse(page); // 최신부터 읽었으니 오래된 것부터로
        Long nextBeforeId = hasMore ? page.get(0).id() : null;
        return new WebChatHistoryPage(page, nextBeforeId);
    }
}
