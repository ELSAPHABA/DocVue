import { beforeEach, describe, expect, it, vi } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';
import { useStatus } from './status';
describe('status store', () => {
  beforeEach(() => {setActivePinia(createPinia()); vi.restoreAllMocks();});
  it('reports API failure and clears loading', async () => {
    vi.spyOn(globalThis,'fetch').mockRejectedValueOnce(new Error('offline'));
    const store = useStatus();
    await store.refresh();
    expect(store.message).toBe('Core API 연결 실패');
    expect(store.loading).toBe(false);
  });
});
