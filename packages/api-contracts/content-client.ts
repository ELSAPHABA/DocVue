import { AuthClient, ApiFailure } from './auth-client';
export type Visibility='PUBLIC'|'UNLISTED'|'PRIVATE';
export type Difficulty='BEGINNER'|'INTERMEDIATE'|'ADVANCED';
export interface Metadata {title:string;description:string;visibility:Visibility;difficulty:Difficulty;durationMs:number|null;tags:string[];stack:string[]}
export interface Video extends Metadata {id:string;tenantId:string;channel:string;status:string;previewEnabled:boolean;version:number;updatedAt:string;phaseCount:number}
export interface PhaseInput {id?:string;startMs:number;endMs:number;title:string;objective:string;prerequisites:string;estimatedMinutes:number;startState:string;completionCriteria:string}
export interface Phase extends PhaseInput {id:string;ordinal:number;version:number}
export interface Detail {video:Video;phases:Phase[]}
export interface Page {items:Video[];nextCursor:string|null}
export interface PreviewProgress {phaseId:string|null;videoVersion:number;stale:boolean}
export const etag=(version:number)=>({'If-Match':'"'+version+'"'});
export const emptyPhase=(startMs=0):PhaseInput=>({startMs,endMs:startMs+60000,title:'',objective:'',prerequisites:'',estimatedMinutes:10,startState:'',completionCriteria:''});
export function timelineError(phases:PhaseInput[],duration:number|null):string {
  if(phases.length>20)return '최대 20개 Phase까지 등록할 수 있습니다.';
  let end=0;
  for(const p of phases){
    if(!p.title.trim()||!Number.isInteger(p.startMs)||!Number.isInteger(p.endMs)||p.startMs<end||p.endMs<=p.startMs||p.endMs>(duration??14400000)||!Number.isInteger(p.estimatedMinutes)||p.estimatedMinutes<1||p.estimatedMinutes>1440)
      return 'Phase 제목, 시간 순서·중첩 및 예상 소요 시간을 확인하세요.';
    end=p.endMs;
  }
  return '';
}
export function errorMessage(error:unknown):string {
  if(error instanceof ApiFailure){
    if(error.status===412)return '다른 편집에서 변경되었습니다. 현재 내용을 보존한 채 최신 버전을 다시 불러오세요.';
    if(error.status===403)return '제작자 승인 또는 접근 권한이 필요합니다.';
    if(error.status===404)return '콘텐츠를 찾을 수 없거나 접근할 수 없습니다.';
    if(error.status===429)return '요청이 너무 많습니다. 잠시 후 다시 시도하세요.';
    if(error.status===409)return '현재 상태와 충돌합니다. 내용을 확인하세요.';
    return '요청 실패: '+error.code;
  }
  return error instanceof Error?error.message:'요청 실패';
}
export function query(params:Record<string,string|number|undefined|null>){const q=new URLSearchParams();for(const [k,v] of Object.entries(params))if(v!==undefined&&v!==null&&v!=='')q.set(k,String(v));return q.size?'?'+q:''}
export class ContentClient {
  constructor(private auth:AuthClient){}
  private path(tenant:string,id:string){return '/tenants/'+tenant+'/videos/'+id}
  list(tenant:string,cursor?:string){return this.auth.request<Page>('/tenants/'+tenant+'/videos'+query({cursor,limit:20}))}
  get(tenant:string,id:string){return this.auth.request<Detail>(this.path(tenant,id))}
  create(tenantId:string,metadata:Metadata,key:string){return this.auth.request<Detail>('/videos','POST',{tenantId,metadata},{'Idempotency-Key':key})}
  update(v:Video,metadata:Metadata){return this.auth.request<Detail>(this.path(v.tenantId,v.id),'PUT',metadata,etag(v.version))}
  timeline(v:Video,phases:PhaseInput[]){return this.auth.request<Detail>(this.path(v.tenantId,v.id)+'/timeline','PUT',{phases},etag(v.version))}
  preview(v:Video,enabled:boolean){return this.auth.request<Detail>(this.path(v.tenantId,v.id)+'/preview','PUT',{enabled},etag(v.version))}
  archive(v:Video){return this.auth.request<void>(this.path(v.tenantId,v.id),'DELETE',undefined,etag(v.version))}
  catalog(filters:Record<string,string|number|undefined|null>,signal?:AbortSignal){return this.auth.publicRequest<Page>('/videos'+query(filters),false,signal)}
  detail(id:string,authenticated:boolean,signal?:AbortSignal){return this.auth.publicRequest<Detail>('/videos/'+id,authenticated,signal)}
  progress(id:string){return this.auth.request<PreviewProgress>('/videos/'+id+'/preview-progress')}
  saveProgress(v:Video,phaseId:string){return this.auth.request<PreviewProgress>('/videos/'+v.id+'/preview-progress','PUT',{phaseId,videoVersion:v.version},etag(v.version))}
}
