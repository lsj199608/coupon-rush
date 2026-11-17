// @vitest-environment node
import { describe, expect, it } from 'vitest';

/**
 * 폴더 간 import 방향 (아래 층은 위 층을 모른다):
 *   pages → components → hooks → api        utils·auth 는 api 까지만
 * 회귀 방지: utils/searchFilter 가 components/StatusBadge 에서 상태 문구를 가져오는 역방향 의존이 있었다 (→ utils/eventStatus 로 옮김).
 * 백엔드의 ArchitectureTest(ArchUnit)와 같은 목적 — 규칙을 사람이 아니라 테스트가 지킨다.
 */
const FORBIDDEN: Record<string, string[]> = {
  api: ['hooks', 'components', 'pages', 'auth', 'utils'],
  utils: ['hooks', 'components', 'pages', 'auth'],
  hooks: ['components', 'pages'],
  auth: ['components', 'pages'],
  components: ['pages'],
};

// 테스트 파일은 제외 (테스트는 여러 층을 함께 렌더링한다)
const sources = import.meta.glob<string>(['./**/*.{ts,tsx}', '!./**/*.test.{ts,tsx}', '!./test/**'], {
  query: '?raw',
  import: 'default',
  eager: true,
});

/** "./utils/searchFilter.ts" 가 import 하는 src 아래 최상위 폴더들 */
function importedFolders(file: string, source: string): string[] {
  const base = new URL(file.replace(/^\.\//, ''), 'file:///src/');
  return [...source.matchAll(/from\s+['"](\.{1,2}\/[^'"]+)['"]/g)].map(([, path]) => {
    const resolved = new URL(path, base).pathname.replace(/^\/src\//, '');
    return resolved.includes('/') ? resolved.split('/')[0] : '(root)';
  });
}

describe('폴더 간 import 방향', () => {
  it('소스 파일을 읽었다 (glob 이 비면 규칙이 아무것도 검사하지 않는다)', () => {
    expect(Object.keys(sources).length).toBeGreaterThan(20);
  });

  it.each(Object.entries(FORBIDDEN))('%s 는 %j 를 import 하지 않는다', (folder, forbidden) => {
    const violations = Object.entries(sources)
      .filter(([file]) => file.startsWith(`./${folder}/`))
      .flatMap(([file, source]) =>
        importedFolders(file, source)
          .filter((target) => forbidden.includes(target))
          .map((target) => `${file} → ${target}`),
      );
    expect(violations).toEqual([]);
  });
});
