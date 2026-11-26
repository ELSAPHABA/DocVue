import { Component, DestroyRef, effect, inject, input, signal } from '@angular/core';
import { FormsModule, FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { Subject, from, of, switchMap, catchError } from 'rxjs';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { browserAuth, Profile, Tenant } from '../../../packages/api-contracts/auth-client';
import { ContentClient, Detail, Metadata, PhaseInput, Video, emptyPhase, timelineError, errorMessage, Difficulty, Visibility } from '../../../packages/api-contracts/content-client';

@Component({
 selector:'content-panel', imports:[FormsModule,ReactiveFormsModule],
 templateUrl:'./content-panel.html'
})
export class ContentPanel {
 profile=input<Profile|null>(null);tenants=input<Tenant[]>([]);
 private client=new ContentClient(browserAuth);
 tenant=signal(''); items=signal<Video[]>([]); next=signal<string|null>(null);
 detail=signal<Detail|null>(null); phases=signal<PhaseInput[]>([]);
 message=signal(''); busy=signal(false); loading=signal(false);
 editor=signal(false); confirmArchive=false;
 private query=new Subject<{tenant:string;cursor?:string;generation:number}>();
 private generation=0; private createKey='';private createBody='';
 private destroy=inject(DestroyRef);
 metadata=new FormGroup({
  title:new FormControl('',{nonNullable:true,validators:[Validators.required,Validators.maxLength(120)]}),
  description:new FormControl('',{nonNullable:true,validators:[Validators.maxLength(4000)]}),
  visibility:new FormControl<Visibility>('PRIVATE',{nonNullable:true}),
  difficulty:new FormControl<Difficulty>('BEGINNER',{nonNullable:true}),
  durationMs:new FormControl<number|null>(null,[Validators.min(1),Validators.max(14400000)]),
  tags:new FormControl('',{nonNullable:true}),stack:new FormControl('',{nonNullable:true})
 });
 constructor(){
  this.query.pipe(switchMap(q=>q.tenant?from(this.client.list(q.tenant,q.cursor)).pipe(
    catchError(e=>of({error:errorMessage(e),items:[],nextCursor:null})),
    switchMap(result=>of({q,result}))
  ):of({q,result:{items:[],nextCursor:null}})),takeUntilDestroyed(this.destroy)).subscribe(({q,result})=>{
    if(q.generation!==this.generation)return;
    this.items.set(q.cursor?[...this.items(),...result.items]:result.items);this.next.set(result.nextCursor);this.loading.set(false);
    if('error' in result)this.message.set(String(result.error));
  });
  effect(()=>{
    const user=this.profile();const list=this.tenants();
    if(!user?.creatorApproved||!user.emailVerified||!list.some(t=>t.id===this.tenant()&&t.role!=='LEARNER')){
      if(this.tenant())this.selectTenant('');
    }
  });
 }
 selectTenant(id:string){this.tenant.set(id);this.detail.set(null);this.editor.set(false);this.items.set([]);this.message.set('');this.load()}
 load(cursor?:string){this.loading.set(true);this.query.next({tenant:this.tenant(),cursor,generation:++this.generation})}
 begin(){this.detail.set(null);this.phases.set([]);this.editor.set(true);this.confirmArchive=false;this.createKey='';this.metadata.reset({title:'',description:'',visibility:'PRIVATE',difficulty:'BEGINNER',durationMs:null,tags:'',stack:''});this.message.set('')}
 private apply(d:Detail,keepMetadata=false,keepPhases=false){
  this.detail.set(d);this.editor.set(true);this.confirmArchive=false;
  const v=d.video;
  if(!keepMetadata)this.metadata.reset({title:v.title,description:v.description,visibility:v.visibility,difficulty:v.difficulty,durationMs:v.durationMs,tags:v.tags.join(', '),stack:v.stack.join(', ')});
  if(!keepPhases)this.phases.set(d.phases.map(({id,startMs,endMs,title,objective,prerequisites,estimatedMinutes,startState,completionCriteria})=>({id,startMs,endMs,title,objective,prerequisites,estimatedMinutes,startState,completionCriteria})));
 }
 private async work(fn:()=>Promise<void>){if(this.busy())return;this.busy.set(true);this.message.set('');try{await fn();this.message.set('저장되었습니다.')}catch(e){this.message.set(errorMessage(e))}finally{this.busy.set(false)}}
 open(v:Video){return this.work(async()=>this.apply(await this.client.get(v.tenantId,v.id)))}
 reload(){const d=this.detail();if(d)return this.open(d.video)}
 save(){return this.work(async()=>{
   if(this.metadata.invalid)throw new Error('필수 입력과 길이 제한을 확인하세요.');
   const raw=this.metadata.getRawValue();const split=(v:string)=>v.split(',').map(x=>x.trim()).filter(Boolean);
   const m:Metadata={...raw,tags:split(raw.tags),stack:split(raw.stack)};
   if(m.durationMs!==null&&!Number.isInteger(m.durationMs))throw new Error('영상 길이는 정수 밀리초입니다.');
   const d=this.detail();
   if(d)this.apply(await this.client.update(d.video,m),false,true);
   else {const body=JSON.stringify(m);if(!this.createKey||this.createBody!==body){this.createKey=crypto.randomUUID();this.createBody=body}this.apply(await this.client.create(this.tenant(),m,this.createKey));this.createKey=''}
   this.load();
 })}
 add(){const p=this.phases();if(p.length<20)this.phases.set([...p,emptyPhase(p.at(-1)?.endMs??0)])}
 remove(i:number){this.phases.set(this.phases().filter((_,j)=>j!==i))}
 move(i:number,direction:number){const p=[...this.phases()];const j=i+direction;if(j<0||j>=p.length)return;[p[i],p[j]]=[p[j],p[i]];this.phases.set(p)}
 saveTimeline(){return this.work(async()=>{
   const d=this.detail();if(!d)return;
   const err=timelineError(this.phases(),d.video.durationMs);if(err)throw new Error(err);
   this.apply(await this.client.timeline(d.video,this.phases()),true,false);this.load();
 })}
 togglePreview(){return this.work(async()=>{const d=this.detail();if(d){this.apply(await this.client.preview(d.video,!d.video.previewEnabled),true,true);this.load()}})}
 archive(){return this.work(async()=>{const d=this.detail();if(d&&this.confirmArchive){await this.client.archive(d.video);this.detail.set(null);this.editor.set(false);this.load()}})}
}
