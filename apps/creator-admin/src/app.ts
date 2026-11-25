import { Component, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { FormControl, ReactiveFormsModule, Validators } from '@angular/forms';
import { catchError, map, of, startWith, switchMap, Subject } from 'rxjs';
import { AsyncPipe } from '@angular/common';
@Component({
  selector: 'app-root',
  imports: [ReactiveFormsModule, AsyncPipe],
  template: `
    <main>
      <p class="eyebrow">DOCKVUE / CREATOR</p>
      <h1>제작자 개발 환경</h1>
      <p>Phase 01 · 초기 연결 상태 확인</p>
      <button (click)="refresh.next()">Core API 연결 확인</button>
      <p role="status">{{ status$ | async }}</p>
      <label>영상 제목 (폼 검증 예제)
        <input [formControl]="title" placeholder="실습 영상 제목">
      </label>
      <p>{{ title.valid ? '입력 검증 통과' : '제목 1~120자 필요' }}</p>
      <p>저장 및 콘텐츠 관리 기능은 Phase 03에서 구현합니다.</p>
    </main>`
})
export class App {
  private readonly http = inject(HttpClient);
  readonly title = new FormControl('', {nonNullable:true, validators:[Validators.required, Validators.maxLength(120)]});
  readonly refresh = new Subject<void>();
  readonly status$ = this.refresh.pipe(startWith(undefined), switchMap(() =>
    this.http.get<{service:string}>('/api/v1/status').pipe(
      map(r => '연결됨: ' + r.service), catchError(() => of('Core API 연결 실패')))));
}
