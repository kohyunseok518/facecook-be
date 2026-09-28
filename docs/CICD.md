# face 콕 — CI/CD

정리 기준일: 2026-09-21

## 1. 프론트 (facecook-fe)

- **Vercel이 `main` 브랜치를 자동 배포**한다. PR을 `main`에 머지하면 그걸로
  배포가 끝난다 — 별도 파이프라인 설정이 필요 없다
- PR을 올리면 Vercel이 프리뷰 배포(preview URL)도 자동으로 만들어준다 —
  리뷰할 때 그 URL로 실제 화면을 확인할 수 있다

## 2. 백엔드 (facecook-be)

GitHub Actions가 PR과 `main` 배포를 처리한다. 워크플로는 `.github/workflows/`에 있다.

```text
PR 생성 → test.yml: ./gradlew clean check (JUnit + MySQL 통합 테스트)
main 머지 → deploy.yml: test → 이미지 빌드·ECR 푸시 → 서버 순차 롤링 배포
```

- 빌드 도구: Gradle. 배포 단위는 Docker 컨테이너(arm64 이미지)이고, EC2 2대에 SSM으로 배포한다
- 배포는 한 번에 하나만 실행한다(`concurrency: deploy-production`). 진행 중인 배포는 취소하지 않고 끝까지
  기다리며, 대기 중인 실행은 가장 최근 것 하나만 남는다
- **이미지는 태그가 아니라 digest로 지정해 배포한다.** 빌드 job이 푸시한 이미지의 digest를 두 서버에 그대로
  전달하므로 두 서버는 같은 이미지를 받는다. `:latest` 태그는 계속 푸시하지만 배포는 쓰지 않는다
- 서버 하나를 교체할 때마다 서버 안에서 다음을 확인하고, 하나라도 어긋나면 다음 서버로 넘어가지 않는다:
  실행 중인 컨테이너의 이미지가 방금 배포한 digest인지, 프로세스가 떠 있는지, `/actuator/health`가 UP을
  응답하는지(최대 약 3분). 그 뒤에 ALB 대상 그룹이 healthy가 될 때까지 기다린다. ALB의 healthy 값은 이전
  컨테이너가 남긴 판정일 수 있어서 그것만으로 판단하지 않는다
- **헬스체크 경로는 둘로 나뉜다(#145).** 배포 스크립트는 `/actuator/health`(DB·Redis 연결까지 반영)로 새 서버가
  DB에 붙었는지 확인한다. ALB 대상 그룹은 `/actuator/health/liveness`(앱 자체 상태만 반영)를 본다. DB나 Redis가
  잠깐 멈췄을 때 ALB가 두 서버를 모두 비정상으로 판정해 자동 확장 그룹이 서버를 전부 교체하는 것을 막기 위해서다
- 배포 로그의 `배포 이미지: <repo>@sha256:...`와 `배포 digest: sha256:...` 줄이 이번 배포의 이미지다. 저장소 주소는
  GitHub secret이라 로그에서 `***`로 가려지지만 digest는 그대로 보인다

### 환경변수만 바꿔 컨테이너를 다시 만들 때 (예: `COOK_REJECT_ENABLED` 활성화)

`docker restart`로는 환경변수가 갱신되지 않는다. env 파일을 다시 만들고 컨테이너를 재생성해야 하며, 이때도
**현재 배포된 것과 같은 digest**로 만든다(`:latest`를 새로 받지 않는다). 현재 digest는 서버에서
`docker inspect -f '{{.Config.Image}}' facecook-app`으로, 또는 마지막 배포 로그의 `배포 digest` 줄로 확인한다.
활성화·비활성화 작업은 일반 배포와 동시에 하지 않는다 — 진행 중인 배포 실행과 SSM 명령이 끝났는지 확인하고,
작업이 끝날 때까지 새 배포를 시작하지 않는다.

## 3. DB 마이그레이션

- Flyway로 스키마 버전을 관리한다 ([온보딩.md](./온보딩.md) 5번)
- 새 스키마 변경은 항상 새 버전 파일을 추가하는 방식이고, 기존 마이그레이션
  파일은 수정하지 않는다
- 배포 파이프라인에 마이그레이션 자동 적용을 넣을지, 수동으로 확인 후
  적용할지는 **미정**

## 4. 이 문서 범위 밖

- 배포 실패 시 자동 롤백 여부
