import { useState } from 'react';
import { errorMessageOf, llmFailureMessage } from '../api/errors';
import { KEYWORDS_MAX, TONE_LABELS, type EventCopyCandidate, type EventCopyTone } from '../api/llm';
import { useEventCopy } from '../hooks/useEventCopy';
import { toEventCopyInput, validateEventCopyRequest, type EventFormValues } from '../utils/eventForm';
import { Spinner } from './Spinner';

/** 접수 단계 거절 중 다시 눌러도 오늘은 안 되는 것 */
const NO_RETRY_CODES = new Set(['LLM_DAILY_LIMIT_EXCEEDED', 'LLM_BUDGET_EXCEEDED', 'FORBIDDEN']);

interface Props {
  /** 폼의 현재 값 (수량·기간을 문구에 반영) */
  values: EventFormValues;
  /** 후보를 고르면 이름·설명 칸을 채운다 — 저장은 관리자가 [이벤트 만들기]를 눌러야 된다 */
  onApply: (candidate: EventCopyCandidate) => void;
}

/**
 * AI 문구 추천: 키워드·톤 → [문구 생성] → 2초 폴링 → 후보 3개 중 선택.
 * 결과는 제안일 뿐이다 — 폼에 채워 넣고, 관리자가 확인·수정한 뒤 직접 저장한다 (자동 저장 없음).
 */
export function EventCopyPanel({ values, onApply }: Props) {
  const [keywords, setKeywords] = useState('');
  const [tone, setTone] = useState<EventCopyTone>('FRIENDLY');
  const [inputError, setInputError] = useState<string | null>(null);
  const [applied, setApplied] = useState<string | null>(null);
  const { state, generate, busy } = useEventCopy();

  const run = (regenerate: boolean) => {
    const problem = validateEventCopyRequest(keywords, values);
    setInputError(problem);
    if (problem) return;
    setApplied(null);
    generate(toEventCopyInput(keywords, tone, values, regenerate));
  };

  return (
    <section className="copy-panel" aria-labelledby="copy-panel-title">
      <h2 id="copy-panel-title">AI 문구 추천</h2>
      <p className="muted hint">키워드와 톤을 넣으면 이름·설명 후보 3개를 만들어 드려요. 고른 뒤에도 자유롭게 고칠 수 있어요.</p>

      <div className="field">
        <label htmlFor="copy-keywords">키워드</label>
        <input
          id="copy-keywords"
          type="text"
          value={keywords}
          maxLength={KEYWORDS_MAX}
          placeholder="예: 겨울 한정 핫초코 50% 할인"
          autoComplete="off"
          onChange={(e) => setKeywords(e.target.value)}
          aria-invalid={inputError ? true : undefined}
          aria-describedby={inputError ? 'copy-keywords-error' : undefined}
        />
        {inputError && (
          <p id="copy-keywords-error" className="field-error">
            {inputError}
          </p>
        )}
      </div>
      <div className="field">
        <label htmlFor="copy-tone">톤</label>
        <select id="copy-tone" value={tone} onChange={(e) => setTone(e.target.value as EventCopyTone)}>
          {(Object.keys(TONE_LABELS) as EventCopyTone[]).map((t) => (
            <option key={t} value={t}>
              {TONE_LABELS[t]}
            </option>
          ))}
        </select>
      </div>

      <button type="button" onClick={() => run(false)} disabled={busy}>
        {busy ? '만드는 중…' : '문구 생성'}
      </button>

      <div className="copy-result" aria-live="polite">
        {busy && <Spinner label="문구를 만드는 중이에요… (보통 몇 초)" />}

        {state.phase === 'completed' && (
          <>
            {state.cached && <p className="muted hint">같은 입력으로 만든 문구를 다시 보여 드려요.</p>}
            <ol className="candidates">
              {state.candidates.map((candidate, index) => (
                <li key={index} className="candidate">
                  <p className="candidate-name">{candidate.name}</p>
                  <p className="candidate-description">{candidate.description}</p>
                  <button type="button" onClick={() => { onApply(candidate); setApplied(candidate.name); }}>
                    이 문구 쓰기
                  </button>
                </li>
              ))}
            </ol>
            <button type="button" onClick={() => run(true)} disabled={busy}>
              다시 생성
            </button>
          </>
        )}

        {state.phase === 'failed' && (
          <div className="notice notice-error" role="alert">
            <p>{llmFailureMessage(state.reason)}</p>
            {state.retryable && (
              <button type="button" onClick={() => run(true)}>
                다시 시도
              </button>
            )}
          </div>
        )}

        {state.phase === 'rejected' && (
          <div className="notice notice-error" role="alert">
            <p>{errorMessageOf(state.error)}</p>
            {!NO_RETRY_CODES.has(state.code ?? '') && (
              <button type="button" onClick={() => run(false)}>
                다시 시도
              </button>
            )}
          </div>
        )}

        {state.phase === 'timeout' && (
          <div className="notice notice-error" role="alert">
            <p>{llmFailureMessage('TIMEOUT')}</p>
            <button type="button" onClick={() => run(true)}>
              다시 시도
            </button>
          </div>
        )}

        {state.phase === 'lost' && (
          <div className="notice notice-error" role="alert">
            <p>{errorMessageOf(state.error)}</p>
            <button type="button" onClick={() => run(false)}>
              다시 시도
            </button>
          </div>
        )}

        {applied && (
          <p className="notice notice-success" role="status">
            &lsquo;{applied}&rsquo; 문구를 이름·설명 칸에 넣었어요. 확인하고 고친 뒤 [이벤트 만들기]를 눌러야 저장돼요.
          </p>
        )}
      </div>
    </section>
  );
}
