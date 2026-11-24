import { useLayoutEffect, useRef, useState, type FormEvent } from 'react';
import { Link, useLocation } from 'react-router-dom';
import { contentLength, WEB_CHAT_CONTENT_MAX, type WebChatMessage } from '../api/webChat';
import { errorMessageOf, webChatRejectionMessage } from '../api/errors';
import { useWebChat, type WebChatStatus } from '../hooks/useWebChat';
import { ROUTES } from '../routes';
import { formatTime } from '../utils/format';
import { Spinner } from './Spinner';

/** 맨 아래에서 이 거리(px) 안이면 "아래를 보고 있다" — 새 메시지가 오면 따라 내려간다 */
const STICK_TO_BOTTOM_PX = 48;

const STATUS_LABELS: Record<WebChatStatus, string> = {
  connecting: '연결 중…',
  connected: '연결됨',
  reconnecting: '연결이 끊겼어요. 다시 연결하는 중…',
};

function MessageItem({ message, mine }: { message: WebChatMessage; mine: boolean }) {
  return (
    <li className={mine ? 'web-chat-message mine' : 'web-chat-message'}>
      <div className="web-chat-meta">
        <span className="web-chat-sender">{mine ? '나' : message.senderNickname}</span>
        <time dateTime={message.sentAt}>{formatTime(message.sentAt)}</time>
      </div>
      {/* 글자로만 — 무엇이 와도 HTML 로 해석하지 않는다 */}
      <p className="web-chat-content">{message.content}</p>
    </li>
  );
}

/**
 * 이벤트 상세 화면의 채팅방 (7단계 D4, 사람 ↔ 사람). 누구나 읽고, 로그인한 사람만 보낸다.
 * 위쪽 [이전 대화 보기]로 더 오래된 메시지를 불러온다 (보던 위치는 그대로).
 */
export function WebChatPanel({ eventId, userId }: { eventId: number; userId: number | null }) {
  const chat = useWebChat(eventId, userId);
  const location = useLocation();
  const [draft, setDraft] = useState('');
  const listRef = useRef<HTMLOListElement>(null);
  /** 아래를 보고 있는가 — 그렇다면 새 메시지가 올 때 따라 내려간다 (위로 올려 읽는 중이면 움직이지 않음) */
  const atBottom = useRef(true);
  /** 이전 대화를 위에 붙이기 직전의 "맨 아래까지 거리" — 붙인 뒤 같은 메시지가 같은 자리에 보이게 */
  const keepFromBottom = useRef<number | null>(null);

  const count = chat.messages.length;
  const lastId = chat.messages[count - 1]?.messageId;
  const firstId = chat.messages[0]?.messageId;

  // 그린 직후·화면에 나오기 전에 맞춘다 (useLayoutEffect — 깜빡이지 않게)
  useLayoutEffect(() => {
    const list = listRef.current;
    if (!list) return;
    if (keepFromBottom.current !== null) {
      list.scrollTop = list.scrollHeight - keepFromBottom.current;
      keepFromBottom.current = null;
    } else if (atBottom.current) {
      list.scrollTop = list.scrollHeight;
    }
  }, [lastId, firstId]);

  const onScroll = () => {
    const list = listRef.current;
    if (list) atBottom.current = list.scrollHeight - list.scrollTop - list.clientHeight <= STICK_TO_BOTTOM_PX;
  };

  const loadOlder = () => {
    const list = listRef.current;
    if (list) keepFromBottom.current = list.scrollHeight - list.scrollTop;
    void chat.loadOlder();
  };

  const length = contentLength(draft);
  const tooLong = length > WEB_CHAT_CONTENT_MAX;
  const sendable = chat.canSend && length > 0 && !tooLong;

  const submit = (e?: FormEvent) => {
    e?.preventDefault();
    if (!sendable) return;
    if (chat.send(draft)) {
      setDraft('');
      atBottom.current = true;   // 내가 보낸 메시지는 위로 올려 보던 중이어도 보여 준다
    }
  };

  return (
    <section className="web-chat" aria-label="이벤트 채팅">
      <header className="web-chat-head">
        <h2>이벤트 채팅</h2>
        <span className={`web-chat-status web-chat-status-${chat.status}`} role="status">
          {STATUS_LABELS[chat.status]}
        </span>
      </header>

      <ol className="web-chat-list" ref={listRef} onScroll={onScroll} aria-live="polite">
        {chat.hasOlder && (
          <li className="web-chat-older">
            <button type="button" onClick={loadOlder} disabled={chat.loadingOlder}>
              {chat.loadingOlder ? '불러오는 중…' : '이전 대화 보기'}
            </button>
          </li>
        )}
        {chat.historyLoading && (
          <li className="web-chat-note">
            <Spinner label="지난 대화를 불러오는 중…" />
          </li>
        )}
        {chat.historyError !== null && (
          <li className="web-chat-note error" role="alert">
            {errorMessageOf(chat.historyError)}{' '}
            <button type="button" onClick={() => void chat.reloadHistory()}>
              다시 시도
            </button>
          </li>
        )}
        {!chat.historyLoading && chat.historyError === null && count === 0 && chat.status === 'connected' && (
          <li className="web-chat-note muted">아직 대화가 없어요. 첫 메시지를 남겨 보세요.</li>
        )}
        {chat.messages.map((message) => (
          <MessageItem key={message.messageId} message={message} mine={userId !== null && message.senderId === userId} />
        ))}
      </ol>

      {chat.rejection !== null && (
        <p className="web-chat-note error" role="alert">
          {webChatRejectionMessage(chat.rejection.code)}
        </p>
      )}

      {userId === null ? (
        <p className="web-chat-login">
          <Link to={ROUTES.login} state={{ from: location.pathname }}>
            로그인
          </Link>
          하면 대화에 참여할 수 있어요.
        </p>
      ) : (
        <form className="web-chat-form" onSubmit={submit}>
          <textarea
            value={draft}
            onChange={(e) => setDraft(e.target.value)}
            onKeyDown={(e) => {
              // Enter = 보내기, Shift+Enter = 줄바꿈 (한글 조합 중 Enter 는 무시)
              if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) {
                e.preventDefault();
                submit();
              }
            }}
            rows={1}
            placeholder={chat.canSend ? '메시지 보내기' : '연결되면 보낼 수 있어요'}
            aria-label="채팅 메시지"
            aria-invalid={tooLong}
          />
          <button type="submit" className="primary" disabled={!sendable}>
            보내기
          </button>
          {length > WEB_CHAT_CONTENT_MAX - 50 && (
            <span className={tooLong ? 'web-chat-count error' : 'web-chat-count muted'}>
              {length} / {WEB_CHAT_CONTENT_MAX}
            </span>
          )}
        </form>
      )}
    </section>
  );
}
