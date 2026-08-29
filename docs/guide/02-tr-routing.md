# 키움 REST API Java 연동 — TR 코드로 Endpoint를 찾는 라우팅 테이블 구조

지난 글에서 App Key/Secret으로 Access Token을 발급받고 첫 API 호출까지 해봤습니다. 이번 글은 그다음 질문에 답합니다. **TR 코드 하나를 받으면, 그걸 어느 URL로 보내야 하는지 어떻게 판단할까?**

키움 REST API는 TR 코드가 100개 넘게 흩어져 있고, 이걸 몇 개의 엔드포인트로 묶어서 받습니다. 그런데 이 묶음 규칙이 이름만 보고는 예측이 안 됩니다. 오늘은 이 라우팅 구조와, 겉보기 규칙을 깨는 예외 TR 4건을 다룹니다.

## 이번 글에서 배울 것

- TR 코드만으로는 Endpoint를 알 수 없는 이유 — 그래서 매핑 테이블이 필요합니다
- 10개 Endpoint에 100개 넘는 TR 코드가 묶이는 전체 구조
- 이름 패턴을 믿었다가 틀리는 예외 TR 4건 (`ka00198`, `ka10044`, `ka90003`/`ka90004`, `ka10075`~`ka10077`)
- 라우팅이 끝난 뒤 요청 한 건이 실제로 거치는 단계(Rate Limit → 전송 → 에러 분기 → 점검 감지)

## 왜 TR 코드만으로는 부족한가

인증 편에서 봤듯, 실제 요청 헤더에는 TR 코드가 이렇게 들어갑니다.

```
api-id        : ka10001          ← TR 코드가 헤더로 들어간다 (body 아님)
authorization : Bearer {token}
cont-yn       : N | Y            ← 연속조회
next-key      : {키}
Content-Type  : application/json;charset=UTF-8
```

`api-id` 헤더는 서버가 "이 요청이 무슨 TR인지" 구분하는 값이지, 클라이언트가 어느 URL로 보내야 하는지를 알려주는 값이 아닙니다. 요청은 전부 POST + JSON body이고, TR 코드 문자열 자체에는 URL 정보가 들어 있지 않습니다. 그래서 클라이언트 쪽에 **TR 코드 → URL 매핑 테이블**이 별도로 필요합니다. 이 매핑을 갖고 있는 게 `KiwoomApiClient`이고, 매핑 테이블 없이 TR 코드만으로 요청을 보내면 애초에 어느 서버 경로로 갈지 결정할 수 없습니다.

## 구조 — TR 코드가 URL로 바뀌기까지

```
클라이언트 코드
    │  call("ka10001", requestBody)
    ▼
┌───────────────────────────────────────┐
│   TR 코드 → URL 매핑 테이블 조회         │
│                                         │
│  ka10001,ka10003,ka10015...→ /stkinfo  │
│  ka10004,ka10044,ka10046...→ /mrkcond  │
│  ka10060,ka10079...        → /chart    │
│  ka10023,ka10030...        → /rkinfo   │
│  ka10008,ka10009           → /frgnistt │
│  ka10014                   → /shsa     │
│  ka10051,ka20001,ka20003   → /sect     │
│  ka90001,ka90002           → /thme     │
│  kt00001,kt00004...        → /acnt     │
│  kt10000,kt10001...        → /ordr     │
└───────────────────────────────────────┘
    │  URL 확정: /api/dostk/stkinfo
    ▼
┌───────────────────────────────────────┐
│  Rate Limit 슬롯 획득 (200ms 간격)       │
└───────────────────────────────────────┘
    │
    ▼
┌───────────────────────────────────────┐
│  POST {baseUrl}/api/dostk/stkinfo      │
│  header: api-id: ka10001               │
│          authorization: Bearer {token} │
│  body:   TR별 요청 파라미터              │
└───────────────────────────────────────┘
    │  응답 수신
    ▼
  return_code 분기 (0 성공 / 1687,1700 재시도 / 8005,8010 토큰재발급 / 407022 지정가전환)
```

한 번의 TR 호출이 "매핑 테이블 조회 → Rate Limit 대기 → 실제 전송 → return_code 분기" 4단계를 순서대로 거친다는 걸 기억해두면, 다음 글에서 다룰 응답 파싱 문제를 이해할 때도 도움이 됩니다.

## TR → URL 매핑 전체 표

키움 REST API가 실제로 쓰는 엔드포인트는 10개입니다.

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

한눈에 봐도 접두사 규칙이 느슨합니다. `ka100xx` 계열이 `stkinfo`·`mrkcond`·`chart`·`rkinfo` 네 곳에 걸쳐 흩어져 있고, `kt`로 시작한다고 무조건 계좌/주문인 것도 아닙니다. 결국 코드를 짤 때 "TR 코드 앞자리를 보고 URL을 유추"하는 방식은 쓸 수 없고, 이 표 그대로를 상수 매핑으로 갖고 있어야 합니다.

## 실제 코드 살펴보기 — 라우팅 이후 벌어지는 일

라우팅으로 URL이 정해진 다음, 요청 한 건이 거치는 나머지 단계를 코드로 봅니다.

### Rate Limit 슬롯 획득

키움 문서에는 REST API 호출 한도가 명시돼 있지 않습니다. COM API와 백엔드를 공유한다는 전제로 **초당 5회**를 가정해 운영 중입니다.

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

`synchronized` 대신 `ReentrantLock`을 쓴 이유가 명시돼 있습니다 — Virtual Thread pinning을 피하기 위해서입니다. Java 21 Virtual Thread 환경에서 `synchronized` 블록 안에 블로킹 호출(`Thread.sleep`)이 있으면 캐리어 스레드가 묶이는데, `ReentrantLock`은 그 문제가 없습니다. 그리고 이 200ms 간격 제어와, 동시에 in-flight 요청 개수를 제한하는 `Semaphore`는 **서로 다른 축**이라 둘 다 필요하다고 못 박혀 있습니다. 하나만 걸면 순간적으로 여러 요청이 동시에 나가는 걸 못 막습니다.

### 응답 return_code 분기

```java
boolean tokenInvalid = rc == 8005 || rc == 8010 || msg.contains("8005") || msg.contains("8010");
```

| `return_code` | 의미 | 대응 |
|---|---|---|
| `0` | 성공 | — |
| `1687`, `1700` | Rate limit | 지수 백오프(200/500/1000ms) 재시도 |
| `8005`, `8010` | 토큰 무효 | 토큰 폐기 → 재발급 → 재시도 |
| `3` + msg에 8005/8010 | 토큰 무효(변형) | 위와 동일 — **메시지까지 검사** |
| `407022` | NXT 시장가 미지원 | 지정가로 전환 |

`rc == 8005`만 보고 끝내면 안 됩니다. 토큰 무효가 `return_code=3`으로 오면서 메시지 문자열 안에 8005/8010이 섞여 오는 변형 케이스가 있어서, 코드 비교와 메시지 문자열 검사를 같이 해야 놓치지 않습니다.

### 점검(maintenance) 감지

점검 중에는 `/oauth2/token`이 JSON이 아니라 HTML을 반환합니다.

```java
private static boolean isMaintenanceResponse(String body) {
    String t = body == null ? "" : body.trim();
    return !t.isEmpty() && t.charAt(0) == '<';
}
```

응답 첫 글자가 `<`인지만 보는 아주 단순한 판별입니다. JSON 파싱을 시도했다가 실패하는 대신, 파싱 전에 이 체크로 빠르게 걸러냅니다. 점검이 감지되면 토큰 발급 시도를 N분간 차단하고, `http://api.kiwoom.com/start.html`을 GET해서 중단 사유를 긁어온 뒤 운영자에게 알립니다. 호출부는 `isMaintenanceActive()` 하나만 확인하면 되고, 덕분에 점검 중에 수십 개 잡이 각자 실패 로그를 쏟는 상황을 막습니다.

## 여기서 주의해야 할 점 — 직관을 배신하는 배치 4건

매핑 표를 처음 보면 "이름이 비슷하면 같은 엔드포인트겠지"라고 추측하기 쉽습니다. 아래 4건은 그 추측이 틀리는 실제 사례입니다. 새 TR을 매핑 테이블에 추가할 때 이름만 보고 넣지 말고 반드시 문서로 확인해야 하는 이유이기도 합니다.

- **`ka00198`** — 이름이 계좌 TR(`ka00xxx`)처럼 생겼지만 실제로는 `/stkinfo`입니다.
- **`ka10044`** — 같은 `ka100xx` 순위 TR들과 달리 이것만 `/mrkcond`입니다.
- **`ka90003` / `ka90004`** — 형제 번호인 `ka90008`/`ka90013`은 `/mrkcond`인데, 이 둘만 `/stkinfo`입니다.
- **`ka10075` / `ka10076` / `ka10077`** — 이름만 보면 주문 관련 같지만 `/ordr`가 아니라 `/acnt`로 갑니다.

그리고 라우팅과 직접 관련은 없지만 같은 계층에서 반드시 지켜야 하는 규칙 두 가지도 짚고 갑니다.

⛔ **주문 TR(`/ordr`, `kt10000`~`kt10003`)은 위 재시도 경로에 태우면 안 됩니다.** 주문은 비멱등이라, rate limit이나 토큰 무효로 실패한 걸 자동 재시도하면 같은 주문이 중복 발생할 수 있습니다.

⚠️ **토큰 발급 실패를 로그에 남길 때 응답 body를 그대로 찍지 마세요.** appkey가 에코백될 수 있습니다. status 코드만 남기는 걸 원칙으로 합니다.

## 직접 해보기

여러분이 다음에 붙일 TR 코드가 있다면, 코드를 짜기 전에 위 표에서 먼저 찾아보세요.

1. 사용하려는 TR 코드가 표의 어느 행에 있는지 확인합니다.
2. `ka`/`kt` 접두사나 번호대만 보고 유추하지 말고, 표에 없는 TR이면 공식 문서에서 endpoint를 직접 확인한 뒤 매핑 테이블에 추가합니다.
3. 주문 계열(`kt10000`~`kt10003`)인지 먼저 확인해서, 재시도 로직에 실수로 태우지 않도록 분리합니다.

전체 코드: https://github.com/SungmanHan/kiwoom-rest-api-java/tree/main/core

## 다음 글 예고

라우팅이 끝나고 요청이 성공하면 `return_code=0`과 함께 TR 고유 필드가 응답 body에 평탄하게 옵니다. 그런데 이 응답의 숫자 필드들이 진짜 숫자가 아니라 **문자열**로 옵니다. 게다가 음수 표기가 일반적인 `-`가 아닌 다른 방식으로 옵니다. 다음 글에서는 이 응답 파싱 문제와, 그걸 다루는 부호 보존 파서를 살펴봅니다.
