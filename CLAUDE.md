# F45 성수 포토부스 — Claude Code 인수인계

## 한 줄 요약
F45 성수 매장용 셀프 포토부스 웹앱. 삼성 갤럭시탭(세로) + OBSBOT Meet 2(USB, 천장 코너) 또는 법인 아이폰(삼각대)에서 실행.
회원 선택 → 랜덤 미션 4컷 촬영 → 프레임 → 스티커 꾸미기 → 합성·업로드·문자 발송 → 결과.

## 배포
- 이 레포 = GitHub Pages. `main`에 push하면 1~2분 뒤 https://f45seongsu.github.io/booth/ 반영.
- 수정 후에는 항상 commit + push까지 할 것. (GitHub 웹 편집기는 파일이 커서 안 됨)
- 태블릿 최초 1회: `https://f45seongsu.github.io/booth/?key=<CRM토큰>` → localStorage에 저장됨.

## 파일 구조
- `index.html` — 앱 전체(단일 파일, ~20MB). 이미지(마스코트·스티커·미션 그림·페이즈 로고·성수 로고)가 base64로 내장.
- `p/index.html` — 문자 링크로 열리는 사진/영상 다운로드 페이지 (`?i=<id>&m=<mp4|webm>`). 카톡 인앱 브라우저면 외부 브라우저로 자동 전환.
- `phase/phase.json` + 이미지 — 4주마다 바뀌는 운동 페이즈 로고. json만 바꾸면 반영(없으면 index.html 내장본 사용).
- `bgm/{wait,shoot,print,end}.mp3` — (선택) 있으면 해당 구간 내장 BGM 대신 재생.
- `supabase/` — 참고용 SQL·Edge Function 사본 (실제 배포는 Supabase 대시보드).

## index.html 구조 (중요)
- GPT가 만든 V10 디자인 위에 **기능 블록을 `</script>` 직전에 계속 덧붙여 기존 함수를 재정의**하는 방식으로 쌓여 있음. 같은 이름 함수가 여러 번 나오면 **마지막 정의가 실제 동작**.
- 로드 시점에 참조되는 전역은 `let/const` 대신 `var` 사용(TDZ 오류 방지 — 과거에 여러 번 터졌음).
- 기존 CSS에 `!important`가 많음 → 덮을 때 `#sticker #canvasBox ...`처럼 선택자 구체성 높이기.
  - `.shot span`은 선택 원 스타일이 걸려 있음 → 라벨은 div로.
  - `#sticker .dragSticker.dragging`에 transform 고정 규칙 있음 → 제스처 중엔 `gesturing` 클래스 사용.
- 화면 id: welcome, who, shoot, pick(현재 미사용), frame, sticker, making, result, end, error. 전환은 `go(id)`.

## 주요 설정값 (검색해서 수정)
- `var SHOT_COUNT=4` 컷 수 / `const COUNTDOWN_SEC=3` 카운트다운
- `const SMS_LOCK_TO='...'` **테스트 잠금: 값이 있으면 모든 문자가 그 번호로만 감. 실운영 시 `''`**
- `?nosms` 문자 생략, `?demo=1` 카메라 없이 데모, `?cam=2` 카메라 강제 선택, `?nobgm` 음악 끔
- `MISSION_LIST` 52개 미션(11~20번 team=true → 2명 이상일 때만), `MISSION_ART` 미션별 벽돌이 그림
- `VOICE_PACKS` 카운트다운 음성(삼!이!일!치즈!/김치! 위주 + 가끔 외국어)
- `TRACKS` / `SCREEN_TRACK` 구간별 BGM(코드 생성, 저작권 없음): wait / shoot(벽돌이의 모험) / print / end
- `FRAMES` 16종 프레임, `PRINT_LAYOUT` 1200×1800(4x6) 2x2
- `STICKER_LIB` 스티커(cat: equipment/deco/words/team/bear), 탭 순서 EQUIP·DECO·WORDS·코치·벽돌이
- `PHASE_LOGOS` 페이즈 로고, `BRAND_LOGO` 성수 로고(navy/white)

## 핵심 파이프라인
- `renderComposite(ctx,W,H,withStickers)` — 프레임 미리보기·스티커 편집 화면·최종 출력이 **모두 같은 렌더러**(WYSIWYG). 스티커 좌표는 `#editCanvas` 기준으로 매핑.
- `drawFooter` → 성수 로고 + `drawPhaseBadge`(누끼 로고, 필요할 때만 테두리) + `drawTimeTicket`(WORKOUT DONE 티켓).
- 움직이는 네컷: 촬영 중 6fps 저화질 버퍼(`startClipBuffer`) → 컷별 셔터 전 2초~후 0.8초 → `buildFrameFilm`이 완성 프레임 안 4칸에 10초 재생 + 완성사진 1.6초, `filmAudio`로 벽돌이의 모험 음악 삽입. mp4(avc1) 우선, 안 되면 webm.
- 카메라: `openBestCamera`(USB/OBSBOT 우선, 선택 기억), 세션 내 스트림 유지, `mute`는 2.5초 유예, `ended`만 재연결.
- 스티커 서랍: 탭=자동 배치, 0.22초 길게 누르기=드래그, 쓸기=스크롤. 붙인 스티커는 1손가락 이동/2손가락 핀치·회전(실시간 %표시).
- 폰(≤600px)·태블릿 세로 전용 레이아웃 미디어쿼리 있음.

## Supabase (프로젝트 f45-seongsu-crm)
- 브라우저는 publishable 키 + `x-app-token` 헤더(없으면 조회 0건). **토큰을 코드에 하드코딩 금지**(레포 public).
- `attendance.class_time`은 **KST 벽시계 시각이 UTC 표기로 저장**됨 → 변환하지 말 것(`toISOString().slice(11,16)`).
- 명단은 WHO'S IN 진입 때마다 재조회 + 대기화면 5분마다.
- `people`에서 **photo_url 절대 조회 금지**(회원 사진 외부 노출 방지). 이름·전화만.
- Storage 버킷 `booth`(public): `YYYY-MM-DD/<ts>-<rand>.jpg|.mp4|.webm`. 파일 키에 한글 불가.
- `booth_logs` 테이블: 촬영 기록(member_uids, member_names, photo_url, sms_sent).
- 문자: Edge Function `aligo-sms` → 오라클 고정IP 릴레이 → 알리고. **문자에 이모지 금지.**
- 백업: Edge Function `booth-archive` + pg_cron 매시간 7분 → 48시간 지난 파일을 Dropbox `앱/F45-Booth-Backup/booth/날짜/"날짜 시각 이름 종류.확장자"`로 옮기고 삭제.

## 테스트
- 로컬: `index.html?demo=1` 파일로 열면 대부분 동작(카메라는 https 필요).
- 수정 후 최소 확인: JS 문법(스크립트 추출 후 `node --check`), 콘솔 에러 없음, 폰(390×780)/태블릿(800×1232) 화면에서 버튼이 화면 밖으로 안 나가는지.

## 벽돌이 포즈 / 시작 문구 (2026-09-29 추가)
- `bears/bear_01~16.webp` — 시작화면(`#welcome .bearHero`)·종료화면(`#end .endBear`)에 들어올 때마다 랜덤. `.png`는 원본(고해상도) 보관용
- 포즈 추가: `bears/bear_17.webp` 식으로 넣고 `BEAR_POSES` 생성 루프의 `i<=16` 숫자만 올리면 됨 (720px 높이 webp 권장)
- 파일 로드 실패 시 index.html 내장 원본 그림으로 자동 복귀
- 시작 문구 `WELCOME_COPY` 20개 랜덤
- WHO 화면(2026-09-29): 좌 `.whoTimes`(시간 칩 세로, 인원수 배지) / 우 `.whoPeople`(명단 2열). `buildWhoSplit()`이 첫 진입 때 DOM 재배치, CSS는 `#whoSplitCss`
- 번호 직접 추가: `#friendForm.open`이 화면 위쪽 큰 팝업(키보드에 안 가리게), 번호 자동 하이픈, 추가 후 자동 닫힘. `#who`가 transform 컨테이너라 팝업/배경은 `#who` 안에 둬야 함
- 시작 문구 `#welcome .welcomeCopy .sub` 글씨 크기 clamp(22px,4.2vw,44px)
- WHO 컴팩트(2026-09-30): `#whoCompactCss` — 글씨 크기는 그대로, 카드·시간버튼 여백 최소화, 카드의 시간 줄(small) 숨김, 제목 한 줄. 태블릿 기준 시간 11개 스크롤 없음, 명단 '전체' 34명/페이지
- 코치/매니저 스티커(2026-10-01): `STICKER_LIB`의 team_민재~team_승준 9개를 절취선 시트에서 다시 잘라 교체(흰 테두리 다이컷). 원본은 `team/*.webp`

## 안드로이드 전용 앱 (2026-10-03, `android/`)
- 갤럭시탭 크롬이 USB 카메라(Insta360 Link 2C, VID 2E1A/PID 4C03)를 못 봐서 만든 앱. 전체화면 WebView로 GitHub Pages 주소를 띄움 → **웹 수정은 지금처럼 index.html push만 하면 앱에도 반영**
- 카메라: `UvcSource.java`가 UVCAndroid(`com.herohan:UVCAndroid`)로 직접 연결, MJPEG 1920x1080 우선. 4초 프레임 없으면 재연결
- 프레임 전달: 웹이 `/booth/__booth_cam/frame.jpg?after=<번호>`를 fetch → 앱이 `shouldInterceptRequest`로 가로채 최신 JPEG 반환(같은 출처라 canvas 오염 없음)
- index.html 맨 끝 블록: `window.BoothNative` 있으면 `openBestCamera`가 `openNativeCamera()`(canvas + `captureStream(30)`)를 씀. 일반 브라우저에선 기존 getUserMedia 그대로
- 키오스크: 화면 꺼짐 방지, 뒤로가기 막음, 세로 고정, 시스템바 숨김, 외부 링크 차단
- 숨은 설정: 화면 **왼쪽 위 모서리 5번 탭** → 시작 주소(최초 1회 `?key=토큰` 붙여 저장), 카메라 다시 연결, 새로고침, 카메라 상태
- USB 권한: 카메라 꽂을 때 "F45 Booth → 항상" 고르면 계속 유지 (`res/xml/device_filter.xml`). Insta360은 마이크 내장이라 앱에 RECORD_AUDIO 권한이 없으면 "항상"이 회색으로 막힘 → 권한 유지 필수(녹음은 안 함)
- 빌드: `.github/workflows/android.yml` — `android/**` 바뀌면 자동 빌드. main이면 https://github.com/f45seongsu/booth/releases/download/android-latest/f45-booth.apk 로 올라감. 서명 키 `android/booth.keystore`(사이드로드용, 같은 키라 위에 덮어 설치 가능)
- 디버깅: PC 크롬 `chrome://inspect`로 앱 WebView 콘솔 확인 가능, JS에서 `BoothNative.camStatus()`
