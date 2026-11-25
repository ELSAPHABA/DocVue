# ADR 0002 — 영상별 OCI Repository와 불변 릴리스
상태: 승인 (Phase 00 설계 기준선, 2026-11-26; 구현·실측 검증은 후속 Phase). S3 archive PoC → Distribution 3 S3 backend; 영상당 학습자 repository 하나. snapshot은 digest, workspace, Compose, seed, harness, validation. 대안: ECR(스토리지 직접 S3 관리 불가), phase별 repo(권한은 세밀하나 제품 모델 변경), docker commit(volume 미포함). Registry scope는 repo 단위이므로 staging/정답 분리, 모든 공개 phase 동일 권한. release DB pointer CAS는 registry와 분산 원자성이 없다. 엄격한 embargo 필요 시 별도 repo ADR. GC reachability 테스트 필수.

D00-01/06/07 확정: Phase 05 archive MVP → Phase 06 Registry; amd64 필수, arm64 검증 후 추가; 동일 영상의 발행된 Phase/Release 동일 접근 권한.
