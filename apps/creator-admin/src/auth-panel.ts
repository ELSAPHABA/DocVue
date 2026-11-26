import { Component, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { browserAuth, Profile, Tenant, Member, Role } from '../../../packages/api-contracts/auth-client';

@Component({
  selector:'auth-panel',
  imports:[FormsModule],
  template:`
  <section>
    <h2>계정 및 채널 관리</h2>
    <p role="status">{{ message() }}</p>
    @if (!profile()) {
      <label>이메일<input type="email" [(ngModel)]="email" autocomplete="email"></label>
      <label>비밀번호<input type="password" [(ngModel)]="password" autocomplete="current-password"></label>
      <button [disabled]="busy()" (click)="run('login')">로그인</button>
      <button [disabled]="busy()" (click)="run('register')">가입</button>
      <button [disabled]="busy()" (click)="run('resume')">세션 복원</button>
      <button [disabled]="busy()" (click)="run('resend')">인증 메일 재전송</button>
    } @else {
      <p>{{ profile()?.email }} · 이메일 {{ profile()?.emailVerified ? '인증 완료' : '미인증' }} · 제작자 {{ profile()?.creatorApproved ? '승인됨' : '승인 대기' }}</p>
      @if (!profile()?.emailVerified) {<button [disabled]="busy()" (click)="run('resend')">인증 메일 재전송</button>}
      <button [disabled]="busy()" (click)="run('logout')">로그아웃</button>
      <button [disabled]="busy()" (click)="run('reload')">채널 새로고침</button>
      <label>채널 이름<input [(ngModel)]="name"></label>
      <label>채널 슬러그<input [(ngModel)]="slug" placeholder="my-lab"></label>
      <button [disabled]="busy()" (click)="run('create')">채널 생성</button>
      <label>초대 코드<input [(ngModel)]="invitation" autocomplete="off"></label>
      <button [disabled]="busy()" (click)="run('accept')">초대 수락</button>
      @for (t of tenants(); track t.id) {
        <p>{{ t.name }} ({{ t.role }})
          @if (t.role==='TENANT_ADMIN') {<button [disabled]="busy()" (click)="open(t)">구성원 관리</button>}
        </p>
      }
      @if (selected()) {
        <h3>{{ selected()?.name }} 구성원</h3>
        <label>초대 이메일<input type="email" [(ngModel)]="inviteEmail"></label>
        <label>권한<select [(ngModel)]="role"><option>LEARNER</option><option>CREATOR</option><option>TENANT_ADMIN</option></select></label>
        <button [disabled]="busy()" (click)="run('invite')">초대 메일 발송</button>
        @for (m of members(); track m.userId) {
          <p>{{ m.userId }} · {{ m.role }} · {{ m.status }}
            <button [disabled]="busy()" (click)="change(m,'ACTIVE')">선택 권한 적용 / 활성화</button>
            <button [disabled]="busy()" (click)="change(m,'REVOKED')">권한 회수</button>
          </p>
        }
      }
    }
    @if (profile() && !profile()?.emailVerified) {
      <label>이메일 인증 코드<input [(ngModel)]="code" autocomplete="off"></label>
      <button [disabled]="busy()" (click)="run('verify')">이메일 인증</button>
    }
  </section>`
})
export class AuthPanel {
  private client=browserAuth;
  profile=signal<Profile|null>(null); tenants=signal<Tenant[]>([]); members=signal<Member[]>([]);
  selected=signal<Tenant|null>(null); busy=signal(false); message=signal('');
  email='';password='';code='';invitation='';name='';slug='';inviteEmail='';role:Role='LEARNER';
  private createKey=''; private createBody='';
  private async reload(){this.profile.set(await this.client.me());this.tenants.set(await this.client.tenants());}
  private async work(fn:()=>Promise<void>){if(this.busy())return;this.busy.set(true);try{await fn();this.message.set('완료')}catch(e){this.message.set(e instanceof Error?e.message:'요청 실패')}finally{this.busy.set(false)}}
  run(action:string){return this.work(async()=>{
    switch(action){
      case 'register':await this.client.register(this.email,this.password);this.password='';break;
      case 'resend':await this.client.resend(this.profile()?.email || this.email);break;
      case 'verify':await this.client.verify(this.code);this.code='';if(this.profile())await this.reload();break;
      case 'login':try{await this.client.login(this.email,this.password)}finally{this.password=''}await this.reload();break;
      case 'resume':await this.client.refresh();await this.reload();break;
      case 'logout':try{await this.client.logout()}finally{this.profile.set(null);this.tenants.set([]);this.selected.set(null);this.members.set([])}break;
      case 'reload':await this.reload();break;
      case 'create':{const body=JSON.stringify([this.name,this.slug]);if(body!==this.createBody||!this.createKey){this.createKey=crypto.randomUUID();this.createBody=body}await this.client.create(this.name,this.slug,this.createKey);this.createKey='';await this.reload();break}
      case 'accept':await this.client.accept(this.invitation);this.invitation='';await this.reload();break;
      case 'invite':if(this.selected()){await this.client.invite(this.selected()!.id,this.inviteEmail,this.role);this.inviteEmail=''}break;
    }
  })}
  open(t:Tenant){return this.work(async()=>{this.members.set(await this.client.members(t.id));this.selected.set(t)})}
  change(m:Member,status:Member['status']){return this.work(async()=>{const t=this.selected();if(!t)return;await this.client.change(t.id,m,status==='REVOKED'?m.role:this.role,status);this.members.set(await this.client.members(t.id))})}
}
