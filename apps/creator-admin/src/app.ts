import { Component } from '@angular/core';
import { AuthPanel } from './auth-panel';
import { ContentPanel } from './content-panel';
@Component({
 selector:'app-root',imports:[AuthPanel,ContentPanel],
 template:`
  <header class="topbar"><div class="brand"><span class="logo">DV</span><span>DockVue <small>Studio</small></span></div><span class="pill">Creator workspace</span></header>
  <main class="shell">
    <div class="hero"><p class="eyebrow">DOCKVUE / CREATOR</p><h1>실습 콘텐츠 스튜디오</h1><p>영상을 학습 Phase로 나누고, 실습 환경을 연결할 준비를 하세요.</p></div>
    <div class="studio-grid">
      <content-panel [profile]="account.profile()" [tenants]="account.tenants()" />
      <aside class="panel account"><auth-panel #account /></aside>
    </div>
  </main>
 `
})
export class App {}
