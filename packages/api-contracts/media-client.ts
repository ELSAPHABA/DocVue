import { sha256 } from '@noble/hashes/sha2.js';
import { AuthClient, ApiFailure } from './auth-client';

export interface UploadPart {partNumber:number;etag:string;checksum:string;bytes:number}
export interface UploadSession {id:string;partSize:number;status:string;parts?:UploadPart[];jobId?:string}
export interface MediaState {status:string;jobId?:string|null;errorCode?:string|null;durationMs?:number|null;mediaId?:string|null}
export interface Playback {url:string;durationMs:number;mediaId:string}
export interface PlaybackProgress {positionMs:number;mediaId:string}
export interface UploadUpdate {id?:string;phase:'hashing'|'uploading'|'completing';bytes:number;total:number}
export const MAX_UPLOAD_BYTES=5*1024**3;
export const MAX_UPLOAD_PARALLEL=4;
const HASH_CHUNK=4*1024**2;
const hex=(data:Uint8Array)=>Array.from(data,b=>b.toString(16).padStart(2,'0')).join('');
const base64=(data:Uint8Array)=>btoa(String.fromCharCode(...data));
const checkAbort=(signal?:AbortSignal)=>signal?.throwIfAborted();

export function validateFile(file:Pick<File,'name'|'size'>){
  if(file.name.length>255||!/^.+\.(mp4|mov|webm)$/i.test(file.name)||/[\u0000-\u001f\u007f-\u009f]/.test(file.name))throw new Error('MP4, MOV, WebM 파일만 업로드할 수 있습니다. 파일 이름도 확인하세요.');
  if(file.size<=0||file.size>MAX_UPLOAD_BYTES)throw new Error('영상 파일은 0보다 크고 5 GiB 이하여야 합니다.');
}
export async function fileSha256(file:Blob,signal?:AbortSignal,onProgress?:(bytes:number)=>void){
  const hash=sha256.create();
  for(let offset=0;offset<file.size;offset+=HASH_CHUNK){
    checkAbort(signal);hash.update(new Uint8Array(await file.slice(offset,offset+HASH_CHUNK).arrayBuffer()));
    checkAbort(signal);onProgress?.(Math.min(file.size,offset+HASH_CHUNK));
  }
  checkAbort(signal);return hex(hash.digest());
}
export async function partChecksum(blob:Blob){return base64(new Uint8Array(await crypto.subtle.digest('SHA-256',await blob.arrayBuffer())))}
export function boundedPosition(positionMs:number,durationMs:number){return Math.max(0,Math.min(Math.max(0,Math.floor(durationMs)),Number.isFinite(positionMs)?Math.floor(positionMs):0))}

export class MediaClient {
  constructor(private auth:AuthClient){}
  private path(tenant:string,video:string){return '/tenants/'+encodeURIComponent(tenant)+'/videos/'+encodeURIComponent(video)}
  private uploadPath(tenant:string,video:string,id?:string){return this.path(tenant,video)+'/uploads'+(id?'/'+encodeURIComponent(id):'')}
  private playbackPath(video:string){return '/videos/'+encodeURIComponent(video)}
  create(tenant:string,video:string,body:{filename:string;bytes:number;sha256:string},key:string){return this.auth.request<UploadSession>(this.uploadPath(tenant,video),'POST',body,{'Idempotency-Key':key})}
  session(tenant:string,video:string,id:string){return this.auth.request<UploadSession>(this.uploadPath(tenant,video,id))}
  sign(tenant:string,video:string,id:string,partNumber:number,checksum:string){return this.auth.request<{url:string}>(this.uploadPath(tenant,video,id)+'/parts','POST',{partNumber,checksum})}
  complete(tenant:string,video:string,id:string){return this.auth.request<{jobId:string}>(this.uploadPath(tenant,video,id)+'/complete','POST')}
  cancel(tenant:string,video:string,id:string){return this.auth.request<void>(this.uploadPath(tenant,video,id),'DELETE')}
  state(tenant:string,video:string){return this.auth.request<MediaState>(this.path(tenant,video)+'/media')}
  retry(tenant:string,video:string){return this.auth.request<{jobId:string}>(this.path(tenant,video)+'/media/retry','POST')}
  playback(video:string,authenticated:boolean,signal?:AbortSignal){return this.auth.publicRequest<Playback>(this.playbackPath(video)+'/playback',authenticated,signal)}
  progress(video:string){return this.auth.request<PlaybackProgress>(this.playbackPath(video)+'/playback-progress')}
  saveProgress(video:string,body:PlaybackProgress){return this.auth.request<PlaybackProgress>(this.playbackPath(video)+'/playback-progress','PUT',body)}
}

interface SavedUpload {id?:string;key:string;sha256:string;bytes:number;filename:string}
export function uploadStorageKey(owner:string,tenant:string,video:string){return 'dockvue-upload:'+JSON.stringify([owner,tenant,video])}
export function savedUpload(key:string):SavedUpload|undefined{
  try{const value=JSON.parse(localStorage.getItem(key)||'null');return value&&typeof value.key==='string'&&typeof value.sha256==='string'&&typeof value.bytes==='number'&&typeof value.filename==='string'?value:undefined}catch{return undefined}
}
function persist(key:string,value?:SavedUpload){try{if(value)localStorage.setItem(key,JSON.stringify(value));else localStorage.removeItem(key)}catch{/* Storage disabled: current transfer still works. */}}
export function forgetUpload(key:string){persist(key)}
export async function uploadFile(client:MediaClient,owner:string,tenant:string,video:string,file:File,signal:AbortSignal,onUpdate:(update:UploadUpdate)=>void){
  validateFile(file);
  const key=uploadStorageKey(owner,tenant,video), report=(phase:UploadUpdate['phase'],bytes:number,id?:string)=>onUpdate({phase,bytes,total:file.size,id});
  report('hashing',0);
  const digest=await fileSha256(file,signal,n=>report('hashing',n));
  let record=savedUpload(key);
  if(record&&(record.sha256!==digest||record.bytes!==file.size||record.filename!==file.name))throw new Error('기존 업로드를 재개하려면 동일한 파일을 선택하거나 기존 업로드를 취소하세요.');
  record??={key:crypto.randomUUID(),sha256:digest,bytes:file.size,filename:file.name};
  persist(key,record);
  let session:UploadSession|undefined;
  if(record.id){
    try{session=await client.session(tenant,video,record.id)}catch(e){if(!(e instanceof ApiFailure)||e.status!==404)throw e;record={...record,id:undefined,key:crypto.randomUUID()};persist(key,record)}
    if(session&&['FAILED','ABORTED'].includes(session.status)){session=undefined;record={...record,id:undefined,key:crypto.randomUUID()};persist(key,record)}
  }
  checkAbort(signal);
  if(!session){session=await client.create(tenant,video,{filename:file.name,bytes:file.size,sha256:digest},record.key);record={...record,id:session.id};persist(key,record)}
  const id=session.id;
  if(session.jobId){persist(key);return {jobId:session.jobId}}
  if(session.status==='COMPLETING'){checkAbort(signal);report('completing',file.size,id);const result=await client.complete(tenant,video,id);persist(key);return result}
  if(session.status!=='OPEN')throw new Error('업로드 세션 상태: '+session.status+'. 기존 업로드를 취소한 뒤 다시 시도하세요.');
  if(!Number.isSafeInteger(session.partSize)||session.partSize<=0||session.partSize>64*1024**2)throw new Error('서버의 업로드 파트 크기가 유효하지 않습니다.');
  checkAbort(signal);
  const count=Math.ceil(file.size/session.partSize), size=session.partSize;
  const known=new Map((session.parts??[]).map(p=>[p.partNumber,p]));
  let next=1, completed=0;
  // Check the local part checksum before counting any resumed server part.
  const length=(n:number)=>Math.min(size,file.size-(n-1)*size);
  for(const [n,p] of known)if(!Number.isInteger(n)||n<1||n>count||p.bytes!==length(n)||!p.checksum||!p.etag)known.delete(n);
  report('uploading',completed,id);
  const local=new AbortController();const abort=()=>local.abort(signal.reason);signal.addEventListener('abort',abort,{once:true});
  if(signal.aborted)abort();
  let failure:unknown;
  async function worker(){
    try{while(next<=count){
      checkAbort(local.signal);const n=next++;
      const blob=file.slice((n-1)*size,n*size), checksum=await partChecksum(blob);
      if(known.has(n)){if(known.get(n)!.checksum!==checksum)throw new Error('재개할 파트 '+n+'의 체크섬이 다릅니다. 기존 업로드를 취소하세요.');checkAbort(local.signal);completed+=blob.size;report('uploading',completed,id);continue}
      checkAbort(local.signal);const signed=await client.sign(tenant,video,id,n,checksum);checkAbort(local.signal);
      const response=await fetch(signed.url,{method:'PUT',body:blob,headers:{'x-amz-checksum-sha256':checksum},credentials:'omit',signal:local.signal});
      if(!response.ok)throw new Error('업로드 파트 '+n+' 실패 ('+response.status+')');
      completed+=blob.size;report('uploading',completed,id);
    }}catch(e){failure??=e;local.abort(e)}
  }
  try{await Promise.all(Array.from({length:Math.min(MAX_UPLOAD_PARALLEL,count)},()=>worker()));if(failure)throw failure;checkAbort(signal);report('completing',file.size,id);const result=await client.complete(tenant,video,id);persist(key);return result}
  finally{signal.removeEventListener('abort',abort)}
}
