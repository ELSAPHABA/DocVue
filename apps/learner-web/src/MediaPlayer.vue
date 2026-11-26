<script setup lang="ts">
import { ref, nextTick, onMounted, onBeforeUnmount } from 'vue';
import type HlsInstance from 'hls.js';
import { browserAuth, ApiFailure } from '../../../packages/api-contracts/auth-client';
import { errorMessage, type Phase } from '../../../packages/api-contracts/content-client';
import { boundedPosition, MediaClient, type Playback } from '../../../packages/api-contracts/media-client';

const props=defineProps<{videoId:string;authenticated:boolean;phases:Phase[]}>();
const client=new MediaClient(browserAuth), player=ref<HTMLVideoElement|null>(null);
const playback=ref<Playback|null>(null), loading=ref(true), message=ref(''), position=ref(0), speed=ref(1), quality=ref(-1);
const levels=ref<{id:number;height:number;bitrate:number}[]>([]);
let hls:HlsInstance|undefined, renew:ReturnType<typeof setInterval>|undefined, disposed=false, lastSave=0, pendingSeek:number|undefined;
let write:Promise<void>=Promise.resolve();const controller=new AbortController();
let restored=false, lastQueued=-1;
const sourceUrl=(url:string)=>{if(!url.startsWith('/')||url.startsWith('//')||url.includes('\\')||new URL(url,location.origin).origin!==location.origin)throw new Error('재생 주소가 올바르지 않습니다.');return url};
function persist(force=false){
  const p=playback.value, v=player.value;
  if(disposed||!props.authenticated||!p||!v||!restored)return;
  const ms=boundedPosition(v.currentTime*1000,p.durationMs), now=Date.now();
  if((!force&&now-lastSave<15000)||lastQueued===ms)return;
  lastSave=now;lastQueued=ms;
  // Serialize writes so an earlier request cannot overwrite a later pause/seek.
  write=write.then(async()=>{if(disposed)return;try{await client.saveProgress(props.videoId,{mediaId:p.mediaId,positionMs:ms})}catch{if(lastQueued===ms)lastQueued=-1;if(!disposed)message.value='재생 위치를 저장하지 못했습니다.'}});
}
function seek(ms:number){const p=playback.value,v=player.value;if(!p||!v)return;const next=boundedPosition(ms,p.durationMs);if(v.readyState<1)pendingSeek=next;else{v.currentTime=next/1000;position.value=next}}
defineExpose({seek,playback});
function loaded(){if(pendingSeek!==undefined){const n=pendingSeek;pendingSeek=undefined;seek(n)}restored=true;lastSave=Date.now()}
function onTime(){position.value=Math.floor((player.value?.currentTime??0)*1000);persist()}
function setSpeed(){if(player.value)player.value.playbackRate=speed.value}
function setQuality(){if(hls)hls.currentLevel=quality.value}
function failed(){message.value='영상 재생 중 오류가 발생했습니다. 페이지를 다시 열어 주세요.'}
function format(ms:number){const s=Math.floor(ms/1000);return Math.floor(s/60)+':'+String(s%60).padStart(2,'0')}
async function renewCookie(){
  try{const next=await client.playback(props.videoId,props.authenticated,controller.signal);if(disposed)return;
    if(next.mediaId!==playback.value?.mediaId){message.value='영상이 변경되었습니다. 새 영상을 보려면 상세 페이지를 다시 열어 주세요.';return}
  }catch(e){if(!disposed)message.value=errorMessage(e)}
}
async function load(){
  try{
    const p=await client.playback(props.videoId,props.authenticated,controller.signal);if(disposed)return;
    const url=sourceUrl(p.url);playback.value=p;
    if(props.authenticated){try{const saved=await client.progress(props.videoId);if(!disposed&&pendingSeek===undefined&&saved?.mediaId===p.mediaId)pendingSeek=boundedPosition(saved.positionMs,p.durationMs)}catch{/* Missing or unavailable progress does not prevent playback. */}}
    if(disposed)return;await nextTick();const v=player.value;if(disposed||!v)return;
    const {default:Hls}=await import('hls.js');if(disposed)return;
    if(Hls.isSupported()){
      hls=new Hls();hls.on(Hls.Events.MANIFEST_PARSED,()=>{levels.value=(hls?.levels??[]).map((level,id)=>({id,height:level.height,bitrate:level.bitrate}))});
      hls.on(Hls.Events.ERROR,(_event,data)=>{if(data.fatal)failed()});hls.loadSource(url);hls.attachMedia(v);
    }else if(v.canPlayType('application/vnd.apple.mpegurl'))v.src=url;
    else{message.value='이 브라우저에서는 HLS 영상 재생을 지원하지 않습니다.';return}
    renew=setInterval(()=>void renewCookie(),8*60*1000);
  }catch(e){if(!disposed&&(!(e instanceof ApiFailure)||![403,404,409].includes(e.status)))message.value=errorMessage(e)}
  finally{if(!disposed)loading.value=false}
}
onMounted(()=>void load());
onBeforeUnmount(()=>{disposed=true;controller.abort();clearInterval(renew);hls?.destroy();const v=player.value;if(v){v.pause();v.removeAttribute('src');v.load()}});
</script>

<template>
  <section class="preview-box" aria-label="영상 플레이어">
    <p v-if="loading" class="muted">영상 확인 중…</p>
    <template v-if="playback">
      <video ref="player" data-testid="media-player" controls playsinline preload="metadata" style="width:100%;max-height:480px;background:#111" @loadedmetadata="loaded" @timeupdate="onTime" @pause="persist(true)" @ended="persist(true)" @error="failed"></video>
      <div class="toolbar">
        <label>재생 속도<select v-model.number="speed" @change="setSpeed"><option v-for="s in [0.5,0.75,1,1.25,1.5,2]" :key="s" :value="s">{{s}}×</option></select></label>
        <label v-if="levels.length">화질<select v-model.number="quality" @change="setQuality"><option :value="-1">자동</option><option v-for="level in levels" :key="level.id" :value="level.id">{{level.height}}p ({{Math.round(level.bitrate/1000)}} kbps)</option></select></label>
        <span class="muted">{{format(position)}} / {{format(playback.durationMs)}}</span>
      </div>
      <div v-if="phases.length" class="timeline" aria-label="영상 챕터">
        <button v-for="phase in phases" :key="phase.id" :disabled="phase.startMs>=playback.durationMs" @click="seek(phase.startMs)">{{format(phase.startMs)}} · {{phase.title}}</button>
      </div>
    </template>
    <template v-else-if="!loading"><strong>영상 재생 준비 중</strong><p>아직 재생 가능한 영상이 없습니다. 학습 콘텐츠 미리보기를 이용할 수 있습니다.</p></template>
    <p v-if="message" role="status" class="message">{{message}}</p>
  </section>
</template>
