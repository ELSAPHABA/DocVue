import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';
export default defineConfig({
  plugins: [vue()],
  server: { proxy: { '/api/v1/status': `http://127.0.0.1:${process.env.CORE_HOST_PORT || 8080}` } },
});
