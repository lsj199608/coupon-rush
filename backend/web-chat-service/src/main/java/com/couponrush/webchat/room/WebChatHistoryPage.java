package com.couponrush.webchat.room;

import java.util.List;

/**
 * 지난 대화 한 페이지.
 *
 * @param messages     오래된 것부터 (화면에 위에서 아래로 그대로 그리면 된다)
 * @param nextBeforeId 더 오래된 메시지가 있으면 다음 요청의 beforeId, 없으면 null
 */
public record WebChatHistoryPage(List<WebChatHistoryMessage> messages, Long nextBeforeId) {
}
