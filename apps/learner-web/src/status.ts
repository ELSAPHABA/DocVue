import { defineStore } from 'pinia';
export const useStatus = defineStore('status', {
  state: () => ({ message: '연결 확인 전', loading: false }),
  actions: {
    async refresh() {
      if (this.loading) return;
      this.loading = true;
      try {
        const response = await fetch('/api/v1/status', {signal: AbortSignal.timeout(5000)});
        if (!response.ok) throw new Error('status request failed');
        const data = await response.json() as {service:string};
        this.message = '연결됨: ' + data.service;
      } catch { this.message = 'Core API 연결 실패'; }
      finally { this.loading = false; }
    }
  }
});
