# 키움 REST API Java 연동 — Rate Limit·토큰 갱신·점검 감지를 한 클래스에 모으기

지난 편에서 앱키를 발급받아 `/oauth2/token`으로 첫 토큰을 받아봤습니다. 이번 편은 그 토큰을 실제로 "계속 쓰는" 계층 이야기입니다. TR 코드 하나 호출할 때마다 매번 URL을 찾아야 하고, 초당 호출 횟수를 넘기지 않아야 하고, 토큰이 만료되면 알아서 갱신해야 하고, 하필 점검 시간에 걸리면 서버가 JSON 대신 HTML을 돌려줍니다. 이 네 가지를 각 호출부에서 따로 처리하면 코드가 지저분해지고 버그가 숨을 곳이 많아집니다. `KiwoomApiClient` 하나가 이 전부를 책임집니다.

## 이번 글에서 배울 것

- `KiwoomApiClient.call()` 하나로 TR 라우팅·인증·Rate Limit·재시도가 전부 처리되는 호출 계층 구조
- `return_code`(1687/1700 vs 8005/8010)에 따라 분기하는 재시도 로직
- 토큰이 만료 임박이면 자동으로 갱신되는 lazy 갱신 흐름
- 점검 시간대에 `/oauth2/token`이 JSON이 아니라 HTML을 반환하는 걸 감지하는 방법
- `synchronized` 대신 `ReentrantLock`을 쓰는 이유(Virtual Thread pinning 회피)

## 왜 하나의 클래스에 몰아넣는가

키움 REST API는 TR 코드마다 엔드포인트가 다르고(3편에서 다룬 라우팅), 문서에 명시된 Rate Limit이 없어서 COM API 시절 관행인 "초당 5회"를 그대로 가정해야 합니다. 여기에 토큰 만료·점검 대응까지 호출부마다 각자 구현하면, 종목 100개를 순회하는 배치 코드 100곳에 똑같은 방어 로직이 복붙됩니다. 이 프로젝트는 그 방어 로직 전부를 `KiwoomApiClient` 한 곳에 가두고, 호출부는 `call(apiId, body)` 한 줄만 부르면 되게 만들었습니다.

## 구조 다이어그램

```
                         KiwoomApiClient.call(apiId, body)
                                     │
                          enabled=false? ──yes──▶ return null
                                     │ no
                                     ▼
                         callWithRetry (최대 3회, attempt 루프)
                                     │
              ┌──────────────────────┼──────────────────────┐
              ▼                      ▼                       ▼
        resolveToken()         tryAcquireSemaphore()   acquireRateSlot()
        (lazy 갱신, 5분전         (max-concurrency         (ReentrantLock,
         만료 감지)                 in-flight 제한)          200ms 간격)
              │                      │                       │
              └──────────────────────┴───────────┬───────────┘
                                                   ▼
                                    executeRequest() — POST + JSON body
                                    header: api-id / authorization /
                                            cont-yn / next-key
                                                   │
                                                   ▼
                                      return_code 파싱
                              ┌────────────┼────────────┐
                              ▼            ▼             ▼
                         0=성공       1687/1700       8005/8010
                        (JsonNode      Rate Limit      토큰 무효
                         반환)        → 백오프 재시도   → 토큰 폐기+재발급
```

토큰 발급 쪽은 별도 흐름입니다.

```
ensureToken()
   │
   ├─ 토큰 있고 만료 임박 아님 → 즉시 return
   ├─ 점검 백오프 중(tokenIssueBlockedUntil 이후 아님) → return (호출 자체 skip)
   └─ tokenLock.lock() → issueToken()
              │
              ├─ 응답 첫 글자 '<' → HTML(점검) → handleMaintenanceDetected()
              │        → start.html GET → 정규식 파싱 → 텔레그램 알림 → 백오프 시작
              └─ JSON 정상 → token/expires_dt 파싱 → KiwoomToken 갱신
```

## 실제 코드 살펴보기

### 1. TR 라우팅 + call() 진입점

`call()`은 `KiwoomProperties.enabled()`가 꺼져 있으면 바로 null을 반환합니다. 켜져 있으면 재시도 루프로 넘어갑니다.

```java
public JsonNode call(String apiId, Map<String, Object> body) {
  if (!props.enabled()) {
    return null;
  }
  return callWithRetry(apiId, body, null, MAX_RETRY);
}
```

연속조회(`cont-yn`/`next-key`)가 필요한 TR은 `callContinuous()`로 nextKey를 넘깁니다. 다만 이 프로젝트의 연속조회 루프 자체는 아직 구현되어 있지 않고(클래스 상단 주석에 "루프 미구현 — Scope 2"라고 명시), 헤더 패스스루만 되어 있다는 점은 확인이 필요합니다.

```java
public JsonNode callContinuous(String apiId, Map<String, Object> body, String nextKey) {
  if (!props.enabled()) {
    return null;
  }
  return callWithRetry(apiId, body, nextKey, MAX_RETRY);
}
```

TR 코드 → URL 매핑은 `TR_PATH_MAP`에 정적으로 박혀 있습니다. 3편에서 본 것처럼 `ka00198`이 계좌 TR처럼 생겼지만 `/stkinfo`로 가는 식의 예외가 여럿 있어서, 이 맵 없이는 TR 코드만 보고 엔드포인트를 유추할 수 없습니다.

```java
private static final Map<String, String> TR_PATH_MAP = Map.ofEntries(
    Map.entry("ka10001", "/api/dostk/stkinfo"),
    Map.entry("ka10044", "/api/dostk/mrkcond"),
    Map.entry("ka00198", "/api/dostk/stkinfo"),
    Map.entry("ka90003", "/api/dostk/stkinfo"),
    Map.entry("ka90004", "/api/dostk/stkinfo"),
    Map.entry("ka10075", "/api/dostk/acnt"),
    Map.entry("ka10076", "/api/dostk/acnt"),
    Map.entry("ka10077", "/api/dostk/acnt"),
    Map.entry("kt10000", "/api/dostk/ordr")
    // ... 전체 매핑은 원본 파일 참고
);
```

매핑에 없는 TR이 들어오면 조용히 실패하는 대신 기본 경로로 보내고 경고 로그를 남깁니다.

```java
private static String resolveApiPath(String apiId) {
  String path = TR_PATH_MAP.get(apiId);
  if (path != null) {
    return path;
  }
  log.warn("[키움] TR 경로 미등록 — apiId={}, 기본 경로({}) 사용", apiId, DEFAULT_API_PATH);
  return DEFAULT_API_PATH;
}
```

### 2. Rate Limit — 인터벌 락과 동시성 세마포어는 다른 축

문서에 한도가 없어서 "COM API와 백엔드 공유 → 초당 5회"를 가정하고 운영 중입니다. 여기서 헷갈리기 쉬운 지점이 있는데, **호출 간격 제어(ReentrantLock)** 와 **동시 in-flight 개수 제한(Semaphore)** 은 서로 다른 문제를 풉니다. 전자는 "마지막 호출로부터 200ms는 지나야 한다"이고, 후자는 "동시에 max-concurrency개 이상 요청을 날리지 않는다"입니다. 하나만 있으면 안 됩니다.

```java
/** 글로벌 호출 간격 제어 — ReentrantLock으로 가상 스레드 carrier pinning 방지. */
private final ReentrantLock rateLimitLock = new ReentrantLock();
```

```java
private void acquireRateSlot() throws InterruptedException {
  int delayMs = props.requestDelayMs();
  if (delayMs <= 0) {
    return;
  }
  rateLimitLock.lock();
  try {
    long now = System.currentTimeMillis();
    long waitMs = delayMs - (now - lastCallEpochMs);
    if (waitMs > 0) {
      Thread.sleep(waitMs);
    }
    lastCallEpochMs = System.currentTimeMillis();
  } finally {
    rateLimitLock.unlock();
  }
}
```

세마포어는 생성자에서 `KiwoomProperties.maxConcurrency()`로 크기를 받습니다.

```java
this.semaphore = new Semaphore(kiwoomProperties.maxConcurrency());
```

### 3. return_code 분기 — 재시도해도 되는 실패, 안 되는 실패

키움 응답에는 공통 래퍼가 없고 `return_code`/`return_msg`가 다른 필드와 평탄하게 섞여 옵니다. 이 코드는 `return_code`만 보고 재시도 여부를 결정합니다.

```java
private boolean shouldRetry(int returnCode, String returnMsg, String apiId,
    int attempt, int maxRetry) throws InterruptedException {
  if ((returnCode == 1687 || returnCode == 1700) && attempt < maxRetry) {
    long backoffMs = BACKOFF_MS[Math.min(attempt, BACKOFF_MS.length - 1)];
    log.warn("[키움] rate limit({}), {}ms 백오프. apiId={} attempt={}",
        returnCode, backoffMs, apiId, attempt);
    Thread.sleep(backoffMs);
    return true;
  }
  // return_code가 8005/8010으로 직접 오는 경우와 return_code=3 + msg 내 에러코드 포함 케이스 모두 처리
  boolean isTokenInvalid = returnCode == 8005 || returnCode == 8010
      || returnMsg.contains("8005") || returnMsg.contains("8010");
  if (isTokenInvalid && attempt < maxRetry) {
    log.warn("[키움] 토큰 무효(code={} msg={}), 재발급 후 재시도. apiId={}", returnCode, returnMsg, apiId);
    tokenRef.set(null);
    return true;
  }
  return false;
}
```

주목할 부분은 토큰 무효 판정이 `return_code == 8005 || return_code == 8010`만 보는 게 아니라 `returnMsg.contains("8005")`까지 함께 본다는 점입니다. `return_code`가 `3`으로 오면서 메시지 안에 8005/8010이 섞여 있는 변형 케이스가 있기 때문입니다. 코드만 보고 분기했다면 이 변형을 놓칩니다.

`BACKOFF_MS`는 200/500/1000ms 3단계입니다.

```java
private static final long[] BACKOFF_MS = {200L, 500L, 1000L};
```

### 4. 토큰 갱신 — lazy + double-checked

토큰은 첫 `call()` 시점에 발급되고, 이후로는 만료 5분 전(`tokenRefreshMarginMinutes`)에 자동 갱신됩니다. 여러 스레드가 동시에 `ensureToken()`을 호출해도 실제 갱신은 한 번만 일어나도록 double-checked 패턴을 씁니다.

```java
private void ensureToken() {
  KiwoomToken current = tokenRef.get();
  if (current != null && !isExpiringSoon(current)) {
    return;
  }

  LocalDateTime blockedUntil = tokenIssueBlockedUntil.get();
  if (blockedUntil != null && LocalDateTime.now().isBefore(blockedUntil)) {
    log.debug("[키움] 토큰 발급 백오프 중 — until={}", blockedUntil);
    return;
  }

  tokenLock.lock();
  try {
    current = tokenRef.get();
    if (current != null && !isExpiringSoon(current)) {
      return;
    }
    KiwoomToken fresh = issueToken();
    if (fresh != null) {
      tokenRef.set(fresh);
      if (tokenIssueBlockedUntil.getAndSet(null) != null) {
        notifyMaintenanceEnded();
      }
    }
  } finally {
    tokenLock.unlock();
  }
}
```

발급 요청 자체는 앱키/시크릿키를 환경변수로만 읽습니다. 코드에 하드코딩된 키는 없고, README도 다음과 같이 안내합니다.

```bash
export KIWOOM_APP_KEY=********
export KIWOOM_SECRET_KEY=********
```

### 5. 점검 시간대 HTML 응답 감지

키움 API가 점검 중이면 `/oauth2/token`이 JSON 대신 HTML 페이지를 돌려줍니다. 이걸 그대로 `objectMapper.readTree()`에 넣으면 `JsonParseException`이 터집니다. 이 프로젝트는 파싱을 시도하기 전에 응답 문자열의 첫 글자만 보고 미리 걸러냅니다.

```java
private static boolean isMaintenanceResponse(String body) {
  if (body == null) return false;
  String trimmed = body.trim();
  return !trimmed.isEmpty() && trimmed.charAt(0) == '<';
}
```

점검으로 판정되면 재시도 대신 즉시 중단하고, 점검 안내 페이지(`start.html`)를 별도로 GET해서 중단 일시·사유·내용을 정규식으로 뽑아냅니다.

```java
private static final Pattern MAINT_PERIOD = Pattern.compile(
    "<h2>1\\.\\s*중단\\s*일시</h2>\\s*<p><b>([^<]+)</b>");
private static final Pattern MAINT_REASON = Pattern.compile(
    "<h2>2\\.\\s*중단\\s*사유</h2>\\s*<p>([^<]+)</p>");
private static final Pattern MAINT_CONTENT = Pattern.compile(
    "<h2>3\\.\\s*중단\\s*내용</h2>\\s*<p>([^<]+)</p>");
```

```java
private MaintenanceNotice fetchMaintenanceNotice() {
  try {
    String html = webClient.get()
        .uri("http://api.kiwoom.com/start.html")
        .retrieve()
        .bodyToMono(String.class)
        .block(Duration.ofSeconds(3));
    if (html == null) return null;
    String period  = matchFirst(MAINT_PERIOD,  html);
    String reason  = matchFirst(MAINT_REASON,  html);
    String content = matchFirst(MAINT_CONTENT, html);
    if (period == null && reason == null && content == null) return null;
    return new MaintenanceNotice(period, reason, content);
  } catch (Exception e) {
    log.debug("[키움] 점검 페이지 파싱 실패 (계속 진행): {}", e.getMessage());
    return null;
  }
}
```

점검이 감지되면 토큰 발급 시도 자체를 N분간 차단합니다. 이 차단 상태는 `isMaintenanceActive()` 하나로 외부에 노출되어, 호출부(스냅샷·브리핑 등)가 점검 중임을 알고 작업 자체를 건너뛸 수 있습니다.

```java
public boolean isMaintenanceActive() {
  LocalDateTime blocked = tokenIssueBlockedUntil.get();
  return blocked != null && LocalDateTime.now().isBefore(blocked);
}
```

이 한 줄이 없으면 점검 시간에 잡(job) 수십 개가 각자 토큰 발급을 시도하다가 실패 로그만 잔뜩 쌓입니다.

### 6. WebClient 설정 — connect/read 타임아웃 이중 방어

`KiwoomApiClient`가 쓰는 `WebClient` 빈은 별도 설정 클래스에서 만들어집니다. connect 타임아웃과 read 타임아웃을 이중으로 걸어둔 게 특징입니다.

```java
@Bean("kiwoomWebClient")
public WebClient kiwoomWebClient(
    KiwoomProperties kiwoomProperties,
    @Value("${app.api.connect-timeout-ms:3000}") int connectTimeoutMs,
    @Value("${app.api.response-timeout-ms:10000}") int responseTimeoutMs) {
  HttpClient httpClient = ResilientHttpClient.pooled("kiwoom", connectTimeoutMs, Duration.ofMillis(responseTimeoutMs))
      .doOnConnected(conn ->
          conn.addHandlerLast(new ReadTimeoutHandler(responseTimeoutMs, TimeUnit.MILLISECONDS)));

  return WebClient.builder()
      .baseUrl(kiwoomProperties.activeUrl())
      .clientConnector(new ReactorClientHttpConnector(httpClient))
      .defaultHeader("Content-Type", "application/json;charset=UTF-8")
      .defaultHeader("Accept", "application/json")
      .codecs(c -> c.defaultCodecs().maxInMemorySize(2 * 1024 * 1024))
      .filter(ApiLogFilter.create(logDir))
      .build();
}
```

`KiwoomApiClient` 쪽에도 별도의 `.block()` 타임아웃이 하나 더 있습니다. `ReadTimeoutHandler`가 발화하지 않는 예외 상황(예: 와이파이 전환으로 소켓은 `TCP_ESTABLISHED`인데 실제로는 죽은 경우)을 대비한 백스톱입니다.

```java
private static final Duration EXECUTE_BLOCK_TIMEOUT = Duration.ofSeconds(12);
```

전체 코드: https://github.com/SungmanHan/kiwoom-rest-api-java/tree/main/core

## 실제 요청과 응답

인증 요청/응답 형태는 README에 이렇게 정리되어 있습니다.

```
POST /oauth2/token
{ "appkey": "...", "secretkey": "...", "grant_type": "client_credentials" }
```

응답: `{ "token": "...", "expires_dt": "20261231235959" }`

이후 모든 TR 호출은 헤더에 인증 정보와 TR 코드를 싣습니다. body가 아니라 헤더에 `api-id`가 들어간다는 점이 다른 REST API와 다른 부분입니다.

```
api-id        : ka10001          ← TR 코드가 헤더로 들어간다 (body 아님)
authorization : Bearer {token}
cont-yn       : N | Y            ← 연속조회
next-key      : {키}
Content-Type  : application/json;charset=UTF-8
```

## 여기서 주의해야 할 점

- **App Key/Secret은 코드에 넣지 않는다.** 환경변수(`KIWOOM_APP_KEY`, `KIWOOM_SECRET_KEY`)로만 주입하고, 값은 어디에 로그로도 남기지 않습니다.
- **토큰 발급 실패 로그에 응답 body를 그대로 찍지 않는다.** `WebClientResponseException` catch 블록에서 이 프로젝트가 `e.getStatusCode()`만 남기는 이유가 이겁니다 — appkey가 에코백될 수 있기 때문입니다.

  ```java
  } catch (WebClientResponseException e) {
    // 응답 body에 appkey가 에코백될 수 있으므로 status만 기록
    log.warn("[키움] 토큰 발급 HTTP 오류 attempt={} status={}", attempt, e.getStatusCode());
  }
  ```
- **주문 TR은 이 재시도 경로에 태우면 안 된다.** 재시도 루프는 조회성 TR을 전제로 합니다. 주문은 비멱등이라, 같은 요청이 두 번 나가면 중복 주문이 될 수 있습니다.
- **`synchronized` 대신 `ReentrantLock`을 쓴다.** `rateLimitLock`과 `tokenLock` 모두 `synchronized` 블록이 아니라 `ReentrantLock`입니다. Virtual Thread(가상 스레드) 위에서 `synchronized` 블록 안에 blocking I/O(`Thread.sleep`, WebClient `.block()`)가 들어가면 그 가상 스레드가 caller 스레드에 "pinning"되어 캐리어 스레드를 반환하지 못합니다. `ReentrantLock`은 이 pinning 문제가 없어서 락 안에서 안전하게 sleep/block을 호출할 수 있습니다.
- **502 같은 HTML 에러 페이지도 점검으로 오판할 수 있다.** `isMaintenanceResponse()`는 응답 첫 글자가 `<`이기만 하면 점검으로 간주합니다. 이 프로젝트는 이 false positive를 알고도 감수합니다 — 어차피 백오프 후 자동 재시도되므로 운영 영향이 미미하다고 판단한 것입니다.
- **"조회 실패(null)"와 "값이 실제로 0"을 구분한다.** `call()`은 모든 실패를 `null`로 통일합니다. 호출부에서 null을 0으로 잘못 취급하면 수급·손익 데이터에서 원인 추적이 불가능해집니다. 명시적 예외가 필요한 호출부는 null을 예외로 감싸는 별도 래퍼를 쓰는 방식이 공존합니다(이 폴더 구성에서는 해당 래퍼 파일이 재료에 포함되어 있지 않아 구체 구현은 확인이 필요합니다).

## 이번 글 정리

`KiwoomApiClient`는 TR 라우팅, 인증·토큰 갱신, Rate Limit(인터벌 락 + 동시성 세마포어), 재시도 분기, 점검 감지까지 호출 계층의 관심사를 전부 한 곳에 모아둔 클래스입니다. 호출부는 `call(apiId, body)` 한 줄만 부르면 되고, 실패는 항상 `null`로 돌아옵니다. 이 계층 덕분에 이후 편에서 다룰 시세·순위·계좌 조회 코드는 Rate Limit이나 토큰 걱정 없이 TR 파라미터 조립에만 집중할 수 있습니다.

## 다음 글 예고

다음 편에서는 이 호출 계층을 실제로 사용해 시세를 조회하는 방법을 다룹니다.
