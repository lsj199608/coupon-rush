import '@testing-library/jest-dom/vitest';
import { afterEach } from 'vitest';
import { cleanup } from '@testing-library/react';
import { tokenStore } from '../api/tokenStore';
import { resetRefreshStateForTest } from '../api/client';

afterEach(() => {
  cleanup();
  tokenStore.clear();
  resetRefreshStateForTest();
  sessionStorage.clear();
});
