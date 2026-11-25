# ADR 0003 — 로컬 실습과 AI 권한
상태: 승인 (Phase 00 설계 기준선, 2026-11-26; 구현·실측 검증은 후속 Phase). 사용자 PC Docker Compose + 외부 AI 도구, 서버 측 임의 실행은 초기 비목표. Go CLI는 Compose CLI를 호출하고 host 명령을 임의 조합하지 않는다. 편집 가능한 source workspace 필수. 대안: 브라우저 VM 실습(비용·격리 운영), 단순 다운로드(UX 부족). 하네스는 권한 부여가 아니며 local verify는 self-reported. 악성 제작자 격리·검증·실행 동의 필요. 고위험 실습 VM 권장.

D00-04/05/08 확정: 외부 AI 도구 사용, 자체 LLM API 불필요, Go CLI, 학습자 로컬 실습. 제작자 격리 빌드/검증 Worker는 별도 허용.
