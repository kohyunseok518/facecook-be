<div align="center">

<img src="img/overview.png" alt="콕찔러보기 — 마음이 가면, 콕. 홈·탐색·채팅 화면 세 장과 함께 3일간 실사용자 445명, 채팅 메시지 6,156건이라고 적혀 있다">

<img src="img/logo.png" width="56" alt="콕찔러보기 앱 아이콘">

**[콕찔러보기 둘러보기](https://facecook.devseok.xyz)**

![status](https://img.shields.io/badge/status-운영_종료-9aa0a6?style=flat-square)
![users](https://img.shields.io/badge/실사용자-445명-e8695a?style=flat-square)
![messages](https://img.shields.io/badge/채팅_메시지-6,156건-5b4de8?style=flat-square)
![PWA](https://img.shields.io/badge/PWA-installable-5a0fc8?style=flat-square&logo=pwa&logoColor=white)

</div>

> [!NOTE]
> 행사가 끝나 지금은 종료 인사와 후기 화면만 열려 있습니다. 아래 화면은 같은 코드를 로컬에 띄우고, 실제 운영과 같은 규모(참가자 445명·콕 1,885건·매칭 463건)의 가상 참가자를 넣어 찍었습니다.

---

## 제52회 용마대동제 소개팅 부스 · 2026.09.30 ~ 10.02 실제 운영

## 📱 프로젝트 소개
> - 축제 소개팅 부스는 줄을 서서 종이에 정보를 적고, 운영진이 손으로 짝을 맞춥니다.
> - 마음에 드는 사람이 있어도 먼저 말을 걸기는 어렵습니다.

콕찔러보기는 **QR 하나로 들어와 서로를 둘러보고, 마음이 가는 사람에게 콕을 보내는 서비스입니다.** <br>
받은 콕에 콕으로 답하면(맞콕) 매칭되고, 그 순간 둘만의 채팅방이 열립니다. <br>
매칭된 두 사람은 대화하며 부스가 준비한 3단계 랜덤 미션을 함께 풀고, <br>
운영진이 부스에서 미션 완료를 확인해 선물을 줍니다.

## 📸 흐름

참가자는 가입에서 후기까지 여섯 화면을 거치게 됩니다.

|                                              `01` 가입                                               |                                            `02` 탐색                                             |                                     `03` 콕                                      |
| :--------------------------------------------------------------------------------------------------: | :----------------------------------------------------------------------------------------------: | :------------------------------------------------------------------------------: |
| <img src="img/screen-onboarding.png" width="200" alt="하고 싶은 활동 세 개 이상과 이상형을 적는 가입 화면"> | <img src="img/screen-explore.png" width="200" alt="참가자 목록에서 나이·학과·MBTI와 공통 취미를 보는 탐색 화면"> | <img src="img/screen-kok.png" width="200" alt="나를 콕한 사람 목록과 맞콕하기 버튼"> |
|                     이메일 인증 뒤<br>MBTI·활동·이상형으로 프로필을 만들어요                     |                       성별·나이·MBTI·학과로 걸러<br>마음이 가는 사람을 찾아요                       |                하루 10번 콕을 보내고<br>받은 콕에 맞콕해요                 |

|                                    `04` 매칭                                    |                                         `05` 채팅·미션                                         |                                    `06` 종료·후기                                    |
| :-----------------------------------------------------------------------------: | :--------------------------------------------------------------------------------------------: | :----------------------------------------------------------------------------------: |
| <img src="img/screen-match.png" width="200" alt="서로 콕을 보내 매칭이 성사된 화면"> | <img src="img/screen-chat.png" width="200" alt="상단에 현재 랜덤 미션이 보이는 실시간 채팅 화면"> | <img src="img/screen-ended.png" width="200" alt="서비스 종료 인사와 익명 후기 입력 화면"> |
|                    서로 콕하면 매칭되고<br>채팅방이 열려요                     |                       실시간으로 대화하며<br>3단계 랜덤 미션을 풀어요                       |                  행사가 끝나면 모든 화면이<br>후기 화면으로 바뀌어요                  |

운영진은 관리자 화면에서 현황을 보고, 신고와 미션 완료를 처리합니다.

|                                              미션                                              |                                                 운영진 통계                                                 |
| :--------------------------------------------------------------------------------------------: | :---------------------------------------------------------------------------------------------------------: |
| <img src="img/screen-mission.png" width="200" alt="3단계 랜덤 미션의 현재 단계와 잠긴 다음 단계"> | <img src="img/screen-admin.png" width="200" alt="총 가입자 445, 오늘 활성 327, 보낸 콕 1885, 성사 매칭 463, 미션 완주 5"> |
|                          부스에서 확인받으면<br>다음 단계가 열려요                          |                         가입·콕·매칭·미션·신고를<br>한 화면에서 봐요                         |

## 📊 3일 동안 실제로 운영했습니다

> [!NOTE]
> 축제 사흘 동안 **445명이 가입해 콕 1,885번, 매칭 463번, 채팅 메시지 6,156건**을 주고받았습니다.

가입은 2일차에 가장 많았고, 대화는 3일차에 가장 많았습니다. 앞서 매칭된 사람들이 대화를 이어 갔습니다. <br>
요청 44만 건을 처리하는 동안 배포 시각을 뺀 서버 오류는 **0.002%**, 1분 단위 응답 p95 중앙값은 **28ms**였습니다.

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="img/daily-usage-dark.png">
  <img alt="날짜별 가입자 136·231·78명, 콕 298·844·743건, 매칭 61·198·204건, 채팅 메시지 1,313·1,769·3,074건 막대 그래프" src="img/daily-usage-light.png">
</picture>

| 가입 참가자 | 콕 | 매칭(채팅방) | 채팅 메시지 | 종료 후 익명 후기 |
| :---: | :---: | :---: | :---: | :---: |
| **445명** | **1,885건** | **463개** | **6,156건** | **37건** |

> "재밌었어요 또 해주세요" · "계속 오픈했으면 좋겠습니다" — 종료 화면에 남은 후기

## ⛓️ 구조

화면은 Vercel, API와 실시간 채팅은 AWS에서 돌아갑니다. <br>
서버 두 대가 **Redis Pub/Sub로 채팅을 중계해서** 두 사람이 서로 다른 서버에 붙어 있어도 대화가 이어집니다.

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="img/architecture-dark.svg">
  <img alt="휴대폰은 Vercel에서 화면을 받고, API와 WebSocket은 ALB를 거쳐 자동 확장 그룹의 EC2 두 대로 간다. EC2는 RDS MySQL과 ElastiCache Redis에 연결된다" src="img/architecture-light.svg">
</picture>

<br>

<details>

<summary><b>배포 파이프라인: 펼쳐서 확인하기</b></summary>

<br>

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="img/deploy-dark.svg">
  <img alt="main에 병합하면 테스트, arm64 이미지 빌드 후 서버를 한 대씩 교체한다. 각 서버는 기동과 healthy를 확인한 뒤에만 다음 서버로 넘어간다" src="img/deploy-light.svg">
</picture>

행사 3일 동안 서비스를 내리지 않고 5번 배포했습니다.

</details>

## 📂 저장소

화면과 서버를 별개의 저장소로 나눕니다.

| 저장소 | 내용 |
| --- | --- |
| [**facecook-be**](https://github.com/seoil-power-rangers/facecook-be) | Spring Boot 백엔드. REST API, STOMP 채팅, 웹 푸시, 배포 파이프라인 |
| [**facecook-fe**](https://github.com/seoil-power-rangers/facecook-fe) | Next.js 프론트엔드(PWA). 참가자·운영진 화면 |

## 🛠️ 기술 스택

| 영역 | 기술 |
| --- | --- |
| Frontend | ![Next.js](https://img.shields.io/badge/Next.js_16-000000?style=flat-square&logo=nextdotjs&logoColor=white) ![React](https://img.shields.io/badge/React_19-61DAFB?style=flat-square&logo=react&logoColor=black) ![TypeScript](https://img.shields.io/badge/TypeScript_5-3178C6?style=flat-square&logo=typescript&logoColor=white) ![Tailwind CSS](https://img.shields.io/badge/Tailwind_CSS_4-06B6D4?style=flat-square&logo=tailwindcss&logoColor=white) ![STOMP](https://img.shields.io/badge/STOMP.js-010101?style=flat-square) |
| Backend | ![Java](https://img.shields.io/badge/Java_21-007396?style=flat-square&logo=openjdk&logoColor=white) ![Spring Boot](https://img.shields.io/badge/Spring_Boot_3.4-6DB33F?style=flat-square&logo=springboot&logoColor=white) ![Spring Data JPA](https://img.shields.io/badge/Spring_Data_JPA-6DB33F?style=flat-square&logo=spring&logoColor=white) ![WebSocket](https://img.shields.io/badge/WebSocket_STOMP-010101?style=flat-square) ![Flyway](https://img.shields.io/badge/Flyway-CC0200?style=flat-square&logo=flyway&logoColor=white) |
| Data | ![MySQL](https://img.shields.io/badge/MySQL_8.4-4479A1?style=flat-square&logo=mysql&logoColor=white) ![Redis](https://img.shields.io/badge/Redis-DC382D?style=flat-square&logo=redis&logoColor=white) |
| 배포 · 운영 | ![AWS](https://img.shields.io/badge/AWS_EC2·ALB·RDS·ElastiCache-FF9900?style=flat-square&logo=amazonwebservices&logoColor=white) ![Docker](https://img.shields.io/badge/Docker-2496ED?style=flat-square&logo=docker&logoColor=white) ![GitHub Actions](https://img.shields.io/badge/GitHub_Actions-2088FF?style=flat-square&logo=githubactions&logoColor=white) ![Vercel](https://img.shields.io/badge/Vercel-000000?style=flat-square&logo=vercel&logoColor=white) ![CloudWatch](https://img.shields.io/badge/CloudWatch-FF4F8B?style=flat-square&logo=amazoncloudwatch&logoColor=white) |
| 테스트 · 분석 | ![JUnit5](https://img.shields.io/badge/JUnit5-25A162?style=flat-square&logo=junit5&logoColor=white) ![Testcontainers](https://img.shields.io/badge/Testcontainers-2496ED?style=flat-square) ![k6](https://img.shields.io/badge/k6-7D64FF?style=flat-square&logo=k6&logoColor=white) ![PostHog](https://img.shields.io/badge/PostHog-F54E00?style=flat-square&logo=posthog&logoColor=white) |

## 👥 팀

| (이름) | (이름) | (이름) | (이름) |
| :---: | :---: | :---: | :---: |
| [@kohyunseok518](https://github.com/kohyunseok518) | [@J2MIN4452](https://github.com/J2MIN4452) | [@Heejeong22](https://github.com/Heejeong22) | [@jongwon-810](https://github.com/jongwon-810) |
| ✨ Team Leader · BE Lead<br>백엔드 설계, AWS 배포·운영 | FE Lead<br>콕·채팅 화면, 요청 구조 | BE · FE<br>랜덤 미션, 관리자 화면 연동 | FE<br>가입·로그인·관리자 화면 |

---

<div align="center">

**콕찔러보기** — 마음이 가면, **콕** 💌

</div>
