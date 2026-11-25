# ADR 0003 — 로컬 실습과 AI 권한
상태: 제안. 사용자 PC Docker Compose + 외부 AI 도구, 서버 측 임의 실행은 초기 비목표. Go CLI는 Compose CLI를 호출하고 host 명령을 임의 조합하지 않는다. 편집 가능한 source workspace 필수. 대안: 브라우저 VM 실습(비용·격리 운영), 단순 다운로드(UX 부족). 하네스는 권한 부여가 아니며 local verify는 self-reported. 악성 제작자 격리·검증·실행 동의 필요. 고위험 실습 VM 권장.
