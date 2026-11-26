<script setup lang="ts">
import { onMounted } from 'vue';
import { useStatus } from './status';
import AuthPanel from './AuthPanel.vue';
const status = useStatus();
onMounted(() => status.refresh());
</script>
<template>
  <main>
    <p class="eyebrow">DOCKVUE / LEARNER</p>
    <h1>학습자 개발 환경</h1>
    <p>Phase 02 · 인증 및 채널 참여</p>
    <button :disabled="status.loading" @click="status.refresh()">Core API 연결 확인</button>
    <p role="status">{{ status.message }}</p>
    <p>영상 탐색 및 Phase 실습은 후속 개발 단계에서 제공됩니다.</p>
    <AuthPanel />
  </main>
</template>
<style>
body{margin:0;background:#f5f7fa;color:#17243a;font:16px system-ui,sans-serif}
main{max-width:720px;margin:64px auto;padding:32px;background:white;border:1px solid #dbe2eb;border-radius:12px}
button{padding:10px 16px;cursor:pointer}.eyebrow{letter-spacing:.15em;font-size:12px;color:#53647a}
</style>
