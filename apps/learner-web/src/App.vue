<script setup lang="ts">
import { ref, onMounted, onBeforeUnmount, watch } from 'vue';
import AuthPanel from './AuthPanel.vue';
import MediaPlayer from './MediaPlayer.vue';
import { browserAuth, type Profile } from '../../../packages/api-contracts/auth-client';
import { ContentClient, type Video, type Detail, type PreviewProgress, errorMessage } from '../../../packages/api-contracts/content-client';
const api=new ContentClient(browserAuth);
const profile=ref<Profile|null>(null), items=ref<Video[]>([]), next=ref<string|null>(null), detail=ref<Detail|null>(null);
const currentId=ref<string|undefined>();
const mediaPlayer=ref<InstanceType<typeof MediaPlayer>|null>(null);
const progress=ref<PreviewProgress|null>(null),message=ref(''),busy=ref(false),loading=ref(false);
const q=ref(''),tag=ref(''),stack=ref(''),difficulty=ref('');
let queryController:AbortController|undefined, detailController:AbortController|undefined, timer:ReturnType<typeof setTimeout>|undefined;
const filters=()=>({q:q.value,tag:tag.value,stack:stack.value,difficulty:difficulty.value,limit:12});
async function search(cursor?:string){
  queryController?.abort();const controller=new AbortController();queryController=controller;loading.value=true;message.value='';
  try{const result=await api.catalog({...filters(),cursor},controller.signal);if(controller.signal.aborted)return;items.value=cursor?[...items.value,...result.items]:result.items;next.value=result.nextCursor}
  catch(e){if(!controller.signal.aborted)message.value=errorMessage(e)}
  finally{if(!controller.signal.aborted)loading.value=false}
}
watch([q,tag,stack,difficulty],()=>{queryController?.abort();next.value=null;loading.value=true;clearTimeout(timer);timer=setTimeout(()=>search(),280)});
function pathId(){return /^\/videos\/([0-9a-f-]{36})$/.exec(location.pathname)?.[1]}
async function open(id:string,route=true){
  detailController?.abort();const controller=new AbortController();detailController=controller;
  if(route)history.pushState({},'','/videos/'+id);currentId.value=id;detail.value=null;progress.value=null;message.value='';busy.value=true;
  try{const d=await api.detail(id,!!profile.value,controller.signal);if(controller.signal.aborted)return;detail.value=d;
    if(profile.value){const p=await api.progress(id);if(!controller.signal.aborted)progress.value=p}}
  catch(e){if(!controller.signal.aborted)message.value=errorMessage(e)}
  finally{if(!controller.signal.aborted)busy.value=false}
}
function back(route=true){detailController?.abort();if(route)history.pushState({},'','/');currentId.value=undefined;detail.value=null;progress.value=null;busy.value=false;message.value='';search()}
function navigate(){const id=pathId();if(id)open(id,false);else back(false)}
async function selectPhase(id:string){
  if(!detail.value||!profile.value||busy.value)return;busy.value=true;
  try{progress.value=await api.saveProgress(detail.value.video,id);message.value='학습 시작 위치가 저장되었습니다.'}
  catch(e){message.value=errorMessage(e)}finally{busy.value=false}
}
function onProfile(user:Profile|null){profile.value=user;const id=pathId();if(id)open(id,false)}
onMounted(()=>{window.addEventListener('popstate',navigate);navigate()});
onBeforeUnmount(()=>{window.removeEventListener('popstate',navigate);queryController?.abort();detailController?.abort();clearTimeout(timer)});
</script>
<template>
  <header class="topbar"><div class="brand"><span class="logo">DV</span><span>DockVue <small>Learn</small></span></div><span class="pill">Learning workspace</span></header>
  <main class="shell">
    <div class="hero"><p class="eyebrow">DOCKVUE / LEARN</p><h1>보고, 실습하고, 이해하세요.</h1><p>영상과 재현 가능한 개발 환경이 연결되는 학습 플랫폼. 학습 영상 및 콘텐츠 미리보기를 제공합니다.</p></div>
    <div class="studio-grid">
      <section class="panel">
        <div v-if="currentId" class="toolbar"><button @click="back()">← 콘텐츠 탐색</button></div>
        <template v-if="detail">
          <p class="eyebrow">{{detail.video.channel}}</p><h2>{{detail.video.title}}</h2>
          <div class="labels"><span class="pill">{{detail.video.difficulty}}</span><span class="pill">{{detail.video.visibility}}</span><span class="pill">메타데이터 미리보기</span></div>
          <MediaPlayer ref="mediaPlayer" :key="detail.video.id+':'+(profile?.id??'guest')" :video-id="detail.video.id" :authenticated="!!profile" :phases="detail.phases" />
          <p style="white-space:pre-wrap;overflow-wrap:anywhere">{{detail.video.description}}</p>
          <div class="labels"><span class="pill" v-for="s in [...new Set([...detail.video.stack,...detail.video.tags])]" :key="s">{{s}}</span></div>
          <h3 style="margin-top:22px">학습 Phase · {{detail.phases.length}}개</h3>
          <p v-if="progress?.stale" class="notice">콘텐츠가 수정되어 이전 시작 위치가 만료되었습니다. 다시 선택하세요.</p>
          <article v-for="p in detail.phases" :key="p.id" class="phase-item" :class="{'selected-phase':progress?.phaseId===p.id&&!progress?.stale}">
            <p class="eyebrow">PHASE {{p.ordinal}} · {{p.startMs}}–{{p.endMs}} ms · {{p.estimatedMinutes}}분</p><h4>{{p.title}}</h4><p>{{p.objective}}</p>
            <p v-if="p.prerequisites">선수 지식: {{p.prerequisites}}</p><p v-if="p.startState">시작 상태: {{p.startState}}</p><p v-if="p.completionCriteria">완료 기준: {{p.completionCriteria}}</p>
            <button v-if="profile" @click="selectPhase(p.id)" :disabled="busy">{{progress?.phaseId===p.id&&!progress?.stale?'시작 위치 저장됨':'학습 시작 위치 저장'}}</button>
            <p v-else class="muted">로그인하면 학습 시작 위치를 저장할 수 있습니다.</p>
            <button v-if="mediaPlayer?.playback" @click="mediaPlayer.seek(p.startMs)" :disabled="p.startMs>=mediaPlayer.playback.durationMs">이 구간 재생 위치로 이동</button>
          </article>
        </template>
        <template v-else-if="!currentId">
          <div class="section-head"><h2>콘텐츠 탐색</h2><span class="pill">Preview catalog</span></div>
          <div class="catalog-controls"><label>검색<input v-model="q" placeholder="영상 제목 또는 설명"></label><label>태그<input v-model="tag" placeholder="예: backend"></label><label>기술 스택<input v-model="stack" placeholder="예: docker"></label><label>난이도<select v-model="difficulty"><option value="">전체</option><option>BEGINNER</option><option>INTERMEDIATE</option><option>ADVANCED</option></select></label></div>
          <p v-if="loading" class="muted">검색 중…</p>
          <p v-if="!loading&&!items.length" class="notice">공개된 콘텐츠 미리보기가 없습니다.</p>
          <div class="catalog-grid">
            <button v-for="v in items" :key="v.id" class="catalog-card" @click="open(v.id)">
              <span class="card-cover">DV / {{v.stack[0]||'LAB'}} · {{v.phaseCount}} PHASES</span>
              <span class="card-body"><strong>{{v.title}}</strong><span class="muted">{{v.channel}} · {{v.difficulty}}</span><span class="labels"><span v-for="tag in v.tags" :key="tag" class="pill">{{tag}}</span></span><span class="link">학습 계획 보기</span></span>
            </button>
          </div>
          <button v-if="next" style="margin-top:14px" @click="search(next!)" :disabled="loading">더 보기</button>
        </template>
        <p role="status" class="message">{{message}}</p>
      </section>
      <aside class="panel account"><AuthPanel @profile="onProfile" /></aside>
    </div>
  </main>
</template>
<style src="../../../packages/api-contracts/theme.css"></style>
