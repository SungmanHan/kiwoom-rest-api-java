# core — 인증 · TR 라우팅 · Rate Limit · 숫자 파싱

**다른 폴더를 보기 전에 여기부터 보세요.** 모든 TR 호출이 이 계층을 통과합니다.

| 파일 | 역할 |
|---|---|
| [`KiwoomApiClient.java`](KiwoomApiClient.java) | ★ 핵심. 토큰 발급·갱신, TR→URL 라우팅, Rate Limiter, 재시도, 점검 감지 |
| [`KiwoomProperties.java`](KiwoomProperties.java) | `app.kiwoom.*` 설정 바인딩 (운영/모의 URL 토글 포함) |
| [`KiwoomWebClientConfig.java`](KiwoomWebClientConfig.java) | WebClient 빈 — connect/read 타임아웃 |
| [`KiwoomNt2NumberParser.java`](KiwoomNt2NumberParser.java) | 부호 보존 파서 (`--` 음수 표기 처리) + 종목코드 `A` 접두 제거 |
| [`StexTpResolver.java`](StexTpResolver.java) | 거래소 구분 시간대 분기 (KRX/NXT SSoT) |
| [`KiwoomNt2Client.java`](KiwoomNt2Client.java) | 원시 응답 DB 적재 래퍼 (선택) |
| [`Nt2KiwoomException.java`](Nt2KiwoomException.java) | 실패를 null이 아닌 예외로 올리고 싶을 때 |

---

## 인증 (OAuth2)

```
POST /oauth2/token
{ "appkey": "...", "secretkey": "...", "grant_type": "client_credentials" }
```

응답: `{ "token": "...", "expires_dt": "20261231235959" }`

이후 모든 요청 헤더:

```
api-id        : ka10001          ← TR 코드가 헤더로 들어간다 (body 아님)
authorization : Bearer {token}
cont-yn       : N | Y            ← 연속조회
next-key      : {키}
Content-Type  : application/json;charset=UTF-8
```

**요청은 전부 POST + JSON body**입니다. GET은 없습니다.
**응답에 공통 래퍼가 없습니다** — `return_code` / `return_msg` + 각 TR 고유 필드가 평탄하게 섞여 옵니다.

### 키는 환경변수로만

```bash
export KIWOOM_APP_KEY=...
export KIWOOM_SECRET_KEY=...
```

⚠️ **토큰 발급 실패 로그에 응답 body를 찍지 마세요** — appkey가 에코백될 수 있습니다. status만 남기세요.

---

## TR → URL 라우팅

TR 코드만으로는 엔드포인트를 알 수 없어서 **매핑 테이블이 필요합니다.**

| 엔드포인트 | TR |
|---|---|
| `/api/dostk/stkinfo` | ka10001, ka10003, ka10015, ka10016, ka10054, ka10059, ka00198, ka90003, ka90004 |
| `/api/dostk/mrkcond` | ka10004, ka10044, ka10046, ka10047, ka10063, ka10086, ka10087, ka90005, ka90008, ka90013 |
| `/api/dostk/chart` | ka10060, ka10079, ka10080, ka10081, ka10082, ka10083 |
| `/api/dostk/rkinfo` | ka10023, ka10027, ka10030, ka10031, ka10032, ka10034, ka10040, ka10042, ka10045, ka10065 |
| `/api/dostk/frgnistt` | ka10008, ka10009 |
| `/api/dostk/shsa` | ka10014 |
| `/api/dostk/sect` | ka10051, ka20001, ka20003 |
| `/api/dostk/thme` | ka90001, ka90002 |
| `/api/dostk/acnt` | kt00001, kt00004, kt00007, kt00009, kt00016, kt00017, kt00018, ka10075, ka10076, ka10077 |
| `/api/dostk/ordr` | kt10000, kt10001, kt10002, kt10003 |

### 직관을 배신하는 배치

- **`ka00198`** — 계좌(`ka00xxx`)처럼 생겼지만 `/stkinfo` 입니다.
- **`ka10044`** — 다른 `ka100xx` 순위 TR과 달리 `/mrkcond` 입니다.
- **`ka90003` / `ka90004`** — 형제인 ka90008/90013은 `/mrkcond`인데 이 둘만 `/stkinfo` 입니다.
- **`ka10075` / `ka10076` / `ka10077`** — 주문 관련인데 `/ordr`가 아니라 `/acnt` 입니다.

---

## Rate Limit

문서에 한도 명시가 없습니다. COM API와 백엔드를 공유하므로 **초당 5회** 를 가정해 운영 중입니다.

```java
private final ReentrantLock rateLimitLock = new ReentrantLock();   // synchronized 아님 (VT pinning)
private volatile long lastCallEpochMs = 0;

private void acquireRateSlot() throws InterruptedException {
    rateLimitLock.lock();
    try {
        long waitMs = 200 - (System.currentTimeMillis() - lastCallEpochMs);
        if (waitMs > 0) Thread.sleep(waitMs);
        lastCallEpochMs = System.currentTimeMillis();
    } finally { rateLimitLock.unlock(); }
}
```

인터벌(200ms)과 동시 in-flight 제한(`Semaphore`)은 **다른 축**입니다. 둘 다 필요합니다.

---

## 에러 코드별 분기

| `return_code` | 의미 | 대응 |
|---|---|---|
| `0` | 성공 | — |
| `1687`, `1700` | Rate limit | 지수 백오프(200/500/1000ms) 재시도 |
| `8005`, `8010` | 토큰 무효 | 토큰 폐기 → 재발급 → 재시도 |
| `3` + msg에 8005/8010 | 토큰 무효 (변형) | 위와 동일 — **메시지까지 검사** |
| `407022` | NXT 시장가 미지원 | 지정가로 전환 |

```java
boolean tokenInvalid = rc == 8005 || rc == 8010 || msg.contains("8005") || msg.contains("8010");
```

⛔ **주문 TR은 이 재시도 경로에 태우지 마세요.** 비멱등입니다.

---

## 점검(maintenance) 감지

점검 중 `/oauth2/token`은 **JSON이 아니라 HTML**을 반환합니다.

```java
private static boolean isMaintenanceResponse(String body) {
    String t = body == null ? "" : body.trim();
    return !t.isEmpty() && t.charAt(0) == '<';
}
```

감지 후 처리:
1. 토큰 발급 시도를 **N분간 차단** (백오프)
2. `http://api.kiwoom.com/start.html` GET → 정규식으로 중단 일시·사유·내용 추출
3. 운영자 알림 (동일 점검 중복 알림은 `period|reason` 키로 차단)
4. 백오프 만료 후 자동 재시도 → 성공하면 "점검 종료" 알림

호출부는 `isMaintenanceActive()` 하나로 작업 자체를 skip할 수 있습니다. 점검 중에 수십 개 잡이 각자 실패 로그를 쏟는 걸 막아 줍니다.

---

## 실패 정책 — null 반환 vs 예외

이 프로젝트는 **`KiwoomApiClient.call()`이 모든 실패에 `null`을 반환**합니다 (HTTP 오류·타임아웃·토큰 실패 포함). 종목 단위 수집에서 한 종목 실패가 배치 전체를 죽이면 안 되기 때문입니다.

명시적 catch를 강제하고 싶은 호출부는 [`KiwoomNt2Client`](KiwoomNt2Client.java)가 null을 `Nt2KiwoomException`으로 래핑합니다. 두 스타일이 공존합니다.

> 어느 쪽이든 **"조회 실패(0)"와 "값이 실제로 0"을 구분**하세요. 손익·수급에서 이 둘을 뭉개면 원인 추적이 불가능해집니다.
