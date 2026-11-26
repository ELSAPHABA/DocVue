import { Component, effect, input, output, signal } from '@angular/core';
import { browserAuth, ApiFailure } from '../../../packages/api-contracts/auth-client';
import { errorMessage } from '../../../packages/api-contracts/content-client';
import { MediaClient, MediaState, UploadUpdate, uploadFile, savedUpload, forgetUpload, uploadStorageKey } from '../../../packages/api-contracts/media-client';

@Component({selector:'media-panel',template:`
  <section class="editor" aria-label="영상 미디어 관리">
    <div class="section-head"><h3>영상 업로드 및 인코딩</h3><span class="pill" data-testid="media-status">{{state()?.status||'미등록'}}</span></div>
    @if (state()?.durationMs) {<p>영상 길이: {{state()?.durationMs}} ms</p>}
    @if (state()?.errorCode) {<p class="notice">인코딩 오류: {{state()?.errorCode}}</p>}
    @if (stateError()) {<p class="notice">미디어 상태 조회 실패: {{stateError()}}</p>}
    <label>영상 파일 (MP4, MOV, WebM, 최대 5 GiB)<input data-testid="media-file" type="file" accept=".mp4,.mov,.webm" [disabled]="busy()" (change)="choose($event)"></label>
    @if (resume()) {<p class="notice">이전 업로드를 재개하려면 동일한 파일을 선택하세요.</p>}
    @if (update(); as u) {
      <label>{{u.phase==='hashing'?'파일 검증':u.phase==='completing'?'업로드 완료 처리':'영상 전송'}} · {{percent()}}%<progress [value]="u.bytes" [max]="u.total" style="width:100%"></progress></label>
    }
    <div class="toolbar">
      <button class="primary" data-testid="media-upload" (click)="start()" [disabled]="busy()||!file()">{{resume()?'업로드 재개':'업로드'}}</button>
      <button (click)="pause()" [disabled]="!busy()||!controller||update()?.phase==='completing'">일시 중지</button>
      <button class="danger" (click)="cancel()" [disabled]="(!resume()&&!controller)||cancelling()||(busy()&&update()?.phase==='completing')">업로드 취소</button>
      @if (state()?.status==='FAILED'&&state()?.jobId) {<button data-testid="media-retry" (click)="retry()" [disabled]="busy()">인코딩 재시도</button>}
    </div>
    <p role="status" class="message">{{message()}}</p>
  </section>`})
export class MediaPanel {
  owner=input.required<string>();tenant=input.required<string>();video=input.required<string>();
  durationChanged=output<number>();
  private api=new MediaClient(browserAuth);
  state=signal<MediaState|null>(null);file=signal<File|null>(null);update=signal<UploadUpdate|null>(null);
  busy=signal(false);cancelling=signal(false);resume=signal(false);message=signal('');stateError=signal('');
  controller?:AbortController;
  private running?:Promise<void>;private generation=0;
  private key(){return uploadStorageKey(this.owner(),this.tenant(),this.video())}
  constructor(){effect(onCleanup=>{
    const key=this.key(), tenant=this.tenant(),video=this.video();const generation=++this.generation;
    this.file.set(null);this.update.set(null);this.state.set(null);this.message.set('');this.stateError.set('');this.busy.set(false);this.cancelling.set(false);this.resume.set(!!savedUpload(key));
    let timer:ReturnType<typeof setTimeout>|undefined;
    const refresh=async()=>{try{
      const state=await this.api.state(tenant,video);if(generation!==this.generation)return;
      const old=this.state();this.state.set(state);this.stateError.set('');
      if(state.status==='SUCCEEDED'&&state.durationMs&&old?.durationMs!==state.durationMs)this.durationChanged.emit(state.durationMs);
    }catch(e){if(generation===this.generation)this.stateError.set(errorMessage(e))}
      finally{if(generation===this.generation)timer=setTimeout(refresh,3000)}
    };void refresh();
    onCleanup(()=>{++this.generation;clearTimeout(timer);this.controller?.abort();this.controller=undefined});
  })}
  choose(event:Event){this.file.set((event.target as HTMLInputElement).files?.[0]??null);this.message.set('')}
  percent(){const u=this.update();return u?Math.floor(100*u.bytes/u.total):0}
  pause(){this.controller?.abort()}
  async start(){
    const file=this.file();if(!file||this.busy()||this.cancelling())return;
    const generation=this.generation,key=this.key(),controller=new AbortController();this.controller=controller;this.busy.set(true);this.message.set('');
    const running=(async()=>{try{
      const result=await uploadFile(this.api,this.owner(),this.tenant(),this.video(),file,controller.signal,u=>{if(generation===this.generation){this.update.set(u);this.resume.set(!!savedUpload(key))}});
      if(generation===this.generation){this.message.set('업로드 완료. 인코딩을 기다리는 중입니다.');this.state.set({status:'QUEUED',jobId:result.jobId});this.file.set(null)}
    }catch(e){if(generation===this.generation)this.message.set(controller.signal.aborted?'업로드가 일시 중지되었습니다.':errorMessage(e))}
    finally{if(generation===this.generation){this.controller=undefined;this.busy.set(false);this.resume.set(!!savedUpload(key))}}})();
    this.running=running;await running;if(this.running===running)this.running=undefined;
  }
  async cancel(){
    if(this.cancelling())return;const generation=this.generation,key=this.key(),tenant=this.tenant(),video=this.video();this.cancelling.set(true);
    this.controller?.abort();await this.running;
    try{const record=savedUpload(key);if(record?.id){try{const session=await this.api.session(tenant,video,record.id);if(!['FAILED','ABORTED'].includes(session.status)&&!session.jobId)await this.api.cancel(tenant,video,record.id)}catch(e){if(!(e instanceof ApiFailure)||e.status!==404)throw e}}forgetUpload(key);if(generation===this.generation){this.update.set(null);this.resume.set(false);this.message.set('업로드를 취소했습니다.')}}
    catch(e){if(generation===this.generation)this.message.set(errorMessage(e))}
    finally{if(generation===this.generation)this.cancelling.set(false)}
  }
  async retry(){if(this.busy())return;const generation=this.generation;this.busy.set(true);try{const r=await this.api.retry(this.tenant(),this.video());if(generation===this.generation){this.state.set({status:'QUEUED',jobId:r.jobId});this.message.set('인코딩 재시도를 요청했습니다.')}}catch(e){if(generation===this.generation)this.message.set(errorMessage(e))}finally{if(generation===this.generation)this.busy.set(false)}}
}
