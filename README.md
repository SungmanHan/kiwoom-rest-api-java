# 키움증권 REST API — 실전 사용 기록 (Java / Spring Boot)

키움증권 REST API를 **1년 가까이 실운영 시스템에서 굴리며** 쌓인 호출 코드와 응답 파싱 코드를 정리한 저장소입니다.

공식 스펙 문서를 옮겨 적은 게 아니라, **"실제로 어떤 파라미터를 넣어야 응답이 오고, 응답을 어떻게 파싱해야 안 깨지는지"** 를 코드로 남겼습니다. 공식 문서에 안 적혀 있거나, 적혀 있는 것과 실제 동작이 달랐던 부분은 주석에 실측 날짜와 함께 표시돼 있습니다.

> **공식 스펙은 이 저장소에 없습니다.** TR별 요청/응답 Element 전체 명세는 키움증권이 제공하는 [공식 REST API 문서](https://openapi.kiwoom.com)를 보세요. 여기 있는 건 그 문서를 읽고 구현한 뒤 **깨져 가며 알게 된 것들**입니다.

---

## 이 저장소를 볼 만한 사람

- 키움 REST API로 뭔가 만들려는데 **어떤 파라미터가 필수인지** 문서만 봐서는 모르겠는 사람
- 응답이 `return_code=0`인데 **배열이 비어 있는** 이유를 찾는 사람
- **NXT(넥스트레이드)** 붙이려는데 거래소 구분이 TR마다 왜 다른지 헷갈리는 사람
- 주문(kt10000/kt10003)을 **재시도해도 되는지** 고민 중인 사람

---

## 폴더 구조

| 폴더 | 대상 | 주요 TR |
|---|---|---|
| [`core/`](core/) | 인증·라우팅·Rate Limit·숫자 파싱 — **먼저 읽을 것** | OAuth2 (`/oauth2/token`) |
| [`account/`](account/) | 계좌 조회 (잔고·예수금·평가·기간손익) | kt00018 · kt00001 · kt00004 · kt00017 · kt00016 · ka10076 · ka10077 |
| [`order/`](order/) | 주문 발주·정정·취소·체결확인 | kt10000 · kt10001 · kt10002 · kt10003 · kt00007 · ka10075 |
| [`quote/`](quote/) | 시세·호가·체결강도·장중수급 | ka10001 · ka10004 · ka10046 · ka10063 · ka10051 · ka10065 |
| [`chart/`](chart/) | 분·일·주·월봉 | ka10080 · ka10081 · ka10082 · ka10083 |
| [`rank/`](rank/) | 순위 조회 (거래대금·거래량·신고가·수급상위·관심순위) | ka10030 · ka10032 · ka10034 · ka10044 · ka10016 · ka00198 |
| [`theme/`](theme/) | 테마 그룹·구성종목 | ka90001 · ka90002 |
| [`program/`](program/) | 프로그램매매 | ka90003 · ka90004 · ka90008 · ka90013 |
| [`stockinfo/`](stockinfo/) | 종목정보 (VI·거래원·공매도·투자자별·NXT상장) | ka10054 · ka10040 · ka10014 · ka10059 · ka10100 |

각 폴더의 `README.md`에 **TR별 요청 body · 응답 LIST 키 · 함정**이 정리돼 있고, 같은 폴더의 `.java` 파일이 그 구현입니다.

---

## ⚠️ 가장 많이 시간을 뺏긴 함정 8가지

전체 목록은 [`docs/PITFALLS.md`](docs/PITFALLS.md)에 있습니다. 여기 있는 8개만 알아도 대부분 막힙니다.

### 1. 음수는 dash 2개 (`--`)로 온다

```java
"--369770"  →  -369770   // 이걸 Long.parseLong 하면 NumberFormatException
"+424822"   →   424822
```

수급·손익 같은 **부호가 의미를 갖는 필드**는 반드시 [`core/KiwoomNt2NumberParser.java`](core/KiwoomNt2NumberParser.java) 같은 전용 파서를 쓰세요. 가격·거래량은 부호를 통째로 버리면 됩니다(항상 양수).

### 2. 거래소 선택 방식이 TR 종류마다 다르다 — 파라미터 vs 종목코드 접미사

키움에는 거래소를 고르는 **두 가지 서로 다른 메커니즘**이 있고, TR이 어느 쪽인지는 문서에 명시돼 있지 않습니다.

| TR 유형 | 거래소 선택 방법 | 예시 |
|---|---|---|
| **랭킹/시장 단위** TR | `stex_tp` 파라미터 (`1`=KRX, `2`=NXT, `3`=통합) | ka10030, ka10032, ka10063, ka10016 |
| **단일 종목** TR | **`stk_cd` 접미사** — plain=KRX / `_NX`=NXT / `_AL`=통합 | ka10001, ka10004, ka10080, ka10081 |

`ka10004`에 `stex_tp`를 넣어봐야 무시됩니다. `"005930_AL"`로 보내야 통합 호가가 옵니다.

> 이게 실제로 문제가 되는 지점: 유니버스는 `stex_tp=3`(통합)으로 뽑았는데 호가·차트는 접미사를 안 붙여 KRX만 보는 **소스 불일치**가 조용히 생깁니다. 저희는 실제로 이 상태로 한동안 운영했습니다.

### 3. 응답의 종목코드에도 접미사가 붙어서 돌아온다

`stex_tp=3`으로 요청하면 응답의 `stk_cd`가 `"005930_AL"`입니다. 6자리 코드로 lookup하는 코드가 **전부 조용히 미스**납니다.

```java
if (raw.endsWith("_AL") || raw.endsWith("_NX")) raw = raw.substring(0, raw.length() - 3);
```

보유종목(kt00018) 응답은 또 다르게 **`"A005930"`처럼 `A` 접두사**가 붙습니다. 접두·접미 둘 다 처리해야 합니다.

### 4. NXT는 시장가 주문을 지원하지 않는다

저녁 애프터마켓(15:40~20:00) NXT 구간에서 시장가(`trde_tp=3`)를 넣으면 `407022`로 거부됩니다. **지정가만** 됩니다.

### 5. 토큰 발급 응답이 JSON이 아니라 HTML로 올 때가 있다

점검 중에는 `/oauth2/token`이 **점검 안내 HTML**을 반환합니다. 그대로 `readTree` 하면 `JsonParseException`이 나고, 재시도 로직이 있으면 점검 끝날 때까지 계속 두드립니다.

```java
// 첫 글자가 '<' 이면 JSON 아님 → 점검으로 간주하고 백오프
private static boolean isMaintenanceResponse(String body) {
    String t = body == null ? "" : body.trim();
    return !t.isEmpty() && t.charAt(0) == '<';
}
```

[`core/KiwoomApiClient.java`](core/KiwoomApiClient.java)는 여기서 한 발 더 나가, `http://api.kiwoom.com/start.html`을 긁어 **중단 일시·사유**를 정규식으로 뽑아 알림까지 보냅니다.

### 6. `return_code=0` + 빈 배열 = 정상 응답이 아니다

장중인데 `ka10063`이 빈 배열을 주는 경우가 있습니다. 에러가 아니라 그냥 **데이터가 아직 없는 것**입니다. 저희는 장중 한정 3회 재시도(300ms×n 백오프)를 붙였습니다 — [`quote/KiwoomIntradayInvestorProvider.java`](quote/KiwoomIntradayInvestorProvider.java).

`ka90004`는 더 지독합니다. 당일(`dt`=오늘) 조회 시 장 마감 전에는 **모든 필드가 빈 문자열인 행**을 정상 개수만큼 반환합니다. 첫 행 `stk_cd`가 공란이면 데이터 부재로 판단해야 합니다.

### 7. 응답 키 네이밍이 일관되지 않다

대부분 snake_case인데 **`ka10100`만 `nxtEnable`(camelCase)** 입니다. 실측으로 알아낸 것이고 문서에는 없습니다.

같은 개념도 TR마다 키가 다릅니다 — 순매수가 `netprps_qty`(수량) / `netprps_amt`(금액) / `netslmt`(ka10065)로 갈립니다.

### 8. 필수 파라미터가 문서보다 많다 (cascade)

파라미터를 하나 채우면 그제서야 **다음 필수 파라미터를 요구하는** 에러가 나옵니다. `ka10054`(VI)는 이렇게 캐스케이드로 11개까지 늘어났습니다.

```java
params.put("mrkt_tp", "0");  params.put("bf_mkrt_tp", "0");
params.put("motn_tp", "0");  params.put("motn_drc", "0");
params.put("skip_stk", "000000000");                      // 제외 종목 없음
params.put("trde_qty_tp", "0");   params.put("min_trde_qty", "0");   params.put("max_trde_qty", "0");
params.put("trde_prica_tp", "0"); params.put("min_trde_prica", "0"); params.put("max_trde_prica", "0");
params.put("stex_tp", resolveStexTp());
```

---

## Rate Limit · 재시도

문서에 명시된 한도가 없어 **COM API 기준(초당 5회)** 을 그대로 가정하고 운영 중입니다.

- 글로벌 인터벌 **200ms** — `ReentrantLock`으로 전 스레드 직렬화
- 동시 in-flight 제한 — `Semaphore(maxConcurrency)`
- `return_code` **1687 / 1700** = rate limit → 지수 백오프(200/500/1000ms) 재시도
- `return_code` **8005 / 8010** = 토큰 무효 → 재발급 후 재시도 (`return_code=3` + msg에 코드가 섞여 오는 케이스도 있어 메시지까지 봅니다)

> ⚠️ **Virtual Thread 환경이면 `synchronized` 대신 `ReentrantLock`** 을 쓰세요. `synchronized` 블록 안에서 blocking I/O를 하면 carrier thread가 pinning 됩니다.

> ⛔ **주문 TR(kt10000/kt10001/kt10003)은 절대 재시도하지 마세요.** 비멱등이라 이중 매수가 납니다. 타임아웃이 났다면 재발주가 아니라 `ka10075`(미체결 조회)로 **먼저 확인**하는 게 맞습니다.

---

## 코드에 대해

- 실운영 중인 Spring Boot 3.5 / Java 21 애플리케이션에서 **그대로 발췌**했습니다. Lombok · Jackson `JsonNode` · Spring `WebClient` 의존.
- **그대로 빌드되는 라이브러리가 아닙니다.** 원 프로젝트의 설정 서비스·예외 타입·DTO를 참조하는 부분이 있습니다. 호출 파라미터와 파싱 로직을 가져다 쓰는 용도로 보세요.
- 파일 상단에 `// 출처: ...` 주석이 있는 건 큰 클래스에서 메서드만 떼어낸 발췌본입니다.
- 인증 정보는 **환경변수** `KIWOOM_APP_KEY` / `KIWOOM_SECRET_KEY`로만 읽습니다. 이 저장소에 키·계좌번호·잔고 데이터는 없습니다.

## 라이선스

MIT — 코드는 마음대로 쓰세요. 다만 **투자 판단의 근거로 쓰지 마세요.** 매매 로직이 아니라 API 연동 방법에 대한 기록입니다.
