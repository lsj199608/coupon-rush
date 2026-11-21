import { useEffect, useRef, useState, type FormEvent, type ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { CHATBOT_MESSAGE_MAX, type ChatbotTurn } from '../api/chatbot';
import { chatbotFailureMessage, errorMessageOf } from '../api/errors';
import { useChatbot } from '../hooks/useChatbot';
import { Spinner } from './Spinner';

const URL_PATTERN = /https?:\/\/[^\s)]+/g;

/**
 * 답 안의 주소를 링크로. HTML 로 넣지 않고(dangerouslySetInnerHTML 금지) 글자 조각과 링크 요소로만 만든다 —
 * 답에 무엇이 섞여 와도 스크립트가 되지 않는다. 우리 사이트 주소면 화면 이동(Link), 아니면 새 탭.
 */
export function linkify(text: string, onNavigate?: () => void): ReactNode[] {
  const parts: ReactNode[] = [];
  let last = 0;
  for (const match of text.matchAll(URL_PATTERN)) {
    const url = match[0].replace(/[.,]+$/, '');
    const start = match.index ?? 0;
    parts.push(text.slice(last, start));
    let internal: string | null = null;
    try {
      const parsed = new URL(url);
      if (parsed.origin === window.location.origin) internal = parsed.pathname + parsed.search;
    } catch {
      // 주소 모양이 아니면 그냥 글자로
    }
    parts.push(
      internal !== null ? (
        <Link key={start} to={internal} onClick={onNavigate}>
          {url}
        </Link>
      ) : (
        <a key={start} href={url} target="_blank" rel="noopener noreferrer">
          {url}
        </a>
      ),
    );
    last = start + url.length;
  }
  parts.push(text.slice(last));
  return parts;
}

function Bubble({ turn, onRetry, retryDisabled, onNavigate }: {
  turn: ChatbotTurn;
  onRetry: (question: string) => void;
  retryDisabled: boolean;
  onNavigate: () => void;
}) {
  return (
    <li className="chatbot-turn">
      <p className="chatbot-bubble chatbot-question">{turn.question}</p>
      {turn.status === 'PENDING' && (
        <div className="chatbot-bubble chatbot-answer chatbot-pending">
          <Spinner label="답을 쓰고 있어요…" />
        </div>
      )}
      {turn.status === 'COMPLETED' && turn.answer && (
        <p className="chatbot-bubble chatbot-answer">{linkify(turn.answer, onNavigate)}</p>
      )}
      {turn.status === 'FAILED' && (
        <div className="chatbot-bubble chatbot-answer chatbot-failed" role="alert">
          <p>{chatbotFailureMessage(turn.failureReason)}</p>
          {turn.retryable && (
            <button type="button" onClick={() => onRetry(turn.question)} disabled={retryDisabled}>
              다시 보내기
            </button>
          )}
        </div>
      )}
    </li>
  );
}

/**
 * 챗봇 창 (7단계 C4, 로그인한 사람만). 이벤트 찾기·자세히 보기·내 쿠폰을 물을 수 있고, 발급은 하지 않는다(이벤트 링크로 안내).
 * 데스크톱은 오른쪽 아래 창, 모바일은 전체 화면.
 */
export function ChatbotPanel({ userId, onClose }: { userId: number; onClose: () => void }) {
  const chatbot = useChatbot(userId, true);
  const [draft, setDraft] = useState('');
  const listRef = useRef<HTMLOListElement>(null);
  const inputRef = useRef<HTMLTextAreaElement>(null);

  // 새 턴·답이 오면 맨 아래로
  const lastTurn = chatbot.turns[chatbot.turns.length - 1];
  useEffect(() => {
    const list = listRef.current;
    if (list) list.scrollTop = list.scrollHeight;
  }, [chatbot.turns.length, lastTurn?.status]);

  useEffect(() => {
    inputRef.current?.focus();
  }, []);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && onClose();
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);

  const blocked = chatbot.sending || chatbot.waiting;

  const submit = (e?: FormEvent) => {
    e?.preventDefault();
    if (chatbot.send(draft)) setDraft('');
  };

  return (
    <section className="chatbot-panel" role="dialog" aria-label="AI 챗봇">
      <header className="chatbot-head">
        <strong>AI 챗봇</strong>
        <div className="chatbot-head-actions">
          <button type="button" onClick={chatbot.startNewSession} disabled={blocked}>
            새 대화
          </button>
          <button type="button" onClick={onClose} aria-label="챗봇 닫기">
            ✕
          </button>
        </div>
      </header>

      <ol className="chatbot-list" ref={listRef}>
        {chatbot.loading && (
          <li className="muted chatbot-note">
            <Spinner label="대화를 불러오는 중…" />
          </li>
        )}
        {chatbot.loadError !== null && (
          <li className="chatbot-note error" role="alert">
            {errorMessageOf(chatbot.loadError)}{' '}
            <button type="button" onClick={() => chatbot.reload()}>
              다시 시도
            </button>
          </li>
        )}
        {!chatbot.loading && chatbot.loadError === null && chatbot.turns.length === 0 && (
          <li className="muted chatbot-note">
            이벤트를 찾거나("이번 주에 끝나는 커피 쿠폰"), 받은 쿠폰을 물어보세요("내 쿠폰 보여줘").
            쿠폰 발급은 이벤트 페이지에서 직접 해 주세요.
          </li>
        )}
        {chatbot.turns.map((turn) => (
          <Bubble key={turn.turnId} turn={turn} onRetry={(q) => chatbot.send(q)} retryDisabled={blocked} onNavigate={onClose} />
        ))}
      </ol>

      {chatbot.sendError !== null && (
        <p className="chatbot-note error" role="alert">
          {errorMessageOf(chatbot.sendError)}
        </p>
      )}

      <form className="chatbot-form" onSubmit={submit}>
        <textarea
          ref={inputRef}
          value={draft}
          onChange={(e) => setDraft(e.target.value)}
          onKeyDown={(e) => {
            // Enter = 보내기, Shift+Enter = 줄바꿈 (한글 조합 중 Enter 는 무시)
            if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) {
              e.preventDefault();
              submit();
            }
          }}
          maxLength={CHATBOT_MESSAGE_MAX}
          rows={2}
          placeholder="무엇이든 물어보세요"
          aria-label="질문"
        />
        <button type="submit" className="primary" disabled={blocked || draft.trim() === ''}>
          {chatbot.sending ? '보내는 중' : chatbot.waiting ? '답 기다리는 중' : '보내기'}
        </button>
      </form>
    </section>
  );
}
