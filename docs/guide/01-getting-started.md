# 키움증권 REST API Java 연동 시작하기 — 앱키 발급부터 Access Token, 첫 API 호출까지

키움증권이 REST API를 공개하면서 예전 COM(OpenAPI+) 방식과 달리 순수 Java/Spring 환경에서도 증권 데이터를 다룰 수 있게 됐습니다. 이번 시리즈는 실제로 동작 중인 Java 프로젝트의 코드를 근거로, 인증부터 TR 호출까지 단계별로 따라가 봅니다. 1편은 그 첫 관문인 "앱키 발급 → Access Token → 첫 API 호출"까지입니다.

## 이번 글에서 배울 것

- App Key / Secret이 무엇이고 어떻게 관리해야 하는지
- `POST /oauth2/token`으로 Access Token을 발급받는 흐름
- 모든 요청에 공통으로 붙는 `Authorization` 등 5개 헤더
- `ka10001`(주식기본정보) 같은 TR을 실제로 호출하는 방법
- 첫 응답을 받았을 때 무엇을 확인해야 하는지, 그리고 흔한 실수 한 가지(토큰 로그에 body 찍기)

## 왜 이런 구조인가

키움 REST API는 TR(Transaction) 코드 단위로 기능이 나뉩니다. 예를 들어 현재가 조회는 `ka10001`, 주식체결내역은 `ka10015` 같은 식입니다. 그런데 이 TR 코드만 봐서는 어느 URL로 요청을 보내야 하는지 알 수 없습니다. 그래서 클라이언트 쪽에 "TR 코드 → 실제 엔드포인트" 매핑 테이블이 필요하고, 인증 토큰도 매 요청 헤더에 실어 보내야 합니다.

이 시리즈에서 사용하는 예제 프로젝트는 이 모든 걸 `KiwoomApiClient` 클래스 하나로 캡슐화했습니다. 호출하는 쪽(수집 로직)은 TR 코드와 파라미터만 넘기면 되고, 토큰 발급·갱신·라우팅·재시도는 이 클래스가 책임집니다.

## 구조 한눈에 보기

```
[App Key / Secret]  (환경변수 KIWOOM_APP_KEY / KIWOOM_SECRET_KEY)
        │
        │  POST /oauth2/token
        │  { "appkey": "...", "secretkey": "...", "grant_type": "client_credentials" }
        ▼
   ┌────────────────────────────┐
   │ Access Token (+expires_dt) │
   └────────────────────────────┘
        │
        │  kiwoomApiClient.call("ka10001", body)
        ▼
┌─────────────────────────────────────────────┐
│ KiwoomApiClient.callWithRetry()              │
│  1) ensureToken()      — 만료 임박 시 재발급   │
│  2) resolveApiPath()   — TR_PATH_MAP 조회      │
│  3) acquireRateSlot()  — 200ms 간격(초당 5회)  │
│  4) executeRequest()   — POST + 헤더 5종       │
└─────────────────────────────────────────────┘
        │
        ▼
POST /api/dostk/stkinfo          ← ka10001은 이 경로
  api-id        : ka10001
  authorization : Bearer {token}
  cont-yn       : N
  next-key      :
  Content-Type  : application/json;charset=UTF-8
        │
        ▼
{ "return_code": 0, "return_msg": "...", ...TR 고유 필드 }
```

핵심은 응답에 **공통 래퍼가 없다**는 점입니다. `return_code`/`return_msg`와 TR별 고유 필드가 한 JSON에 평탄하게 섞여 옵니다. 그래서 호출부는 `return_code`를 먼저 확인하는 습관을 들여야 합니다.

## App Key / Secret 발급과 보관

키움 REST API를 쓰려면 먼저 앱키(App Key)와 시크릿키(Secret Key)를 발급받아야 합니다. 이 프로젝트에서는 이 두 값을 코드에 직접 넣지 않고 환경변수로만 다룹니다.

```bash
export KIWOOM_APP_KEY=...
export KIWOOM_SECRET_KEY=...
```

코드에서도 실제로 `System.getenv`로만 읽어옵니다.

```java
private KiwoomToken issueToken() {
    String appKey = System.getenv("KIWOOM_APP_KEY");
    String secretKey = System.getenv("KIWOOM_SECRET_KEY");

    if (appKey == null || appKey.isBlank() || secretKey == null || secretKey.isBlank()) {
      log.warn("[키움] 토큰 발급 스킵 — KIWOOM_APP_KEY/SECRET_KEY 환경변수 미설정");
      return null;
    }
    ...
```

App Key / Secret Key는 그 자체로 민감 정보이므로 실제 값을 로그·커밋·문서 어디에도 남기지 않는 것이 원칙입니다. (이 문서에서도 실제 값 대신 `********`로만 표기합니다.)

## Access Token 발급 흐름

인증은 OAuth2 방식이고, 요청은 이렇게 생겼습니다.

```
POST /oauth2/token
{ "appkey": "...", "secretkey": "...", "grant_type": "client_credentials" }
```

응답:

```
{ "token": "********", "expires_dt": "20261231235959" }
```

`expires_dt`는 `yyyyMMddHHmmss` 형식의 만료 시각입니다. 코드에서는 이 값을 파싱해 `KiwoomToken(accessToken, expiresAt)` 레코드로 들고 있다가, 만료가 임박하면 자동으로 재발급합니다.

```java
private boolean isExpiringSoon(KiwoomToken token) {
    return token.expiresAt().minusMinutes(props.tokenRefreshMarginMinutes())
        .isBefore(LocalDateTime.now());
}
```

토큰 발급·갱신은 `ensureToken()`이 담당하는데, 매 `call()`마다 이 메서드가 먼저 실행되어 "필요할 때만" 새로 발급받는 lazy 방식입니다. 첫 `call()` 호출 시점에 토큰이 없으면 그때 처음 발급하고, 이후에는 만료 몇 분 전(설정값)에만 갱신합니다.

```java
private void ensureToken() {
    KiwoomToken current = tokenRef.get();
    if (current != null && !isExpiringSoon(current)) {
      return;
    }
    ...
    tokenLock.lock();
    try {
      current = tokenRef.get();
      if (current != null && !isExpiringSoon(current)) {
        return;
      }
      KiwoomToken fresh = issueToken();
      if (fresh != null) {
        tokenRef.set(fresh);
        ...
      }
    } finally {
      tokenLock.unlock();
    }
}
```

`synchronized` 대신 `ReentrantLock`을 쓴 이유도 눈여겨볼 만합니다. 가상 스레드(Virtual Thread) 환경에서 `synchronized` 블록 안에 블로킹 I/O(토큰 발급 HTTP 호출)가 있으면 캐리어 스레드가 pinning되는 문제가 있어, 명시적으로 락을 걸고 해제합니다.

## Authorization 헤더 구성

토큰을 발급받았다면, 이후 모든 TR 요청에는 아래 5개 헤더가 공통으로 붙습니다.

```
api-id        : ka10001          ← TR 코드가 헤더로 들어간다 (body 아님)
authorization : Bearer {token}
cont-yn       : N | Y            ← 연속조회
next-key      : {키}
Content-Type  : application/json;charset=UTF-8
```

여기서 초보자가 헷갈리기 쉬운 부분이 있습니다. **TR 코드는 요청 body가 아니라 `api-id` 헤더에 들어갑니다.** body에는 순수하게 조회 파라미터만 담습니다. 실제 요청을 만드는 코드는 다음과 같습니다.

```java
private String executeRequest(String apiId, Map<String, Object> body, String nextKey, String accessToken) {
    ObjectNode requestBody = objectMapper.valueToTree(body);
    String apiPath = resolveApiPath(apiId);

    return webClient.post()
        .uri(apiPath)
        .header("api-id", apiId)
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
        .header("cont-yn", nextKey != null ? "Y" : "N")
        .header("next-key", nextKey != null ? nextKey : "")
        .bodyValue(requestBody)
        .retrieve()
        .bodyToMono(String.class)
        .block(EXECUTE_BLOCK_TIMEOUT);
}
```

그리고 **키움 REST API는 요청이 전부 POST + JSON body**입니다. 조회성 API라도 GET은 쓰지 않습니다. Spring REST 클라이언트에 익숙한 개발자라면 "조회인데 왜 POST?"라고 생각하기 쉬운 지점이라 미리 짚어둡니다.

## 실제 코드 살펴보기 — ka10001 호출

`ka10001`(주식기본정보) 같은 TR을 호출하려면 먼저 TR 코드가 어느 URL로 라우팅되는지 알아야 합니다. 이 매핑은 `KiwoomApiClient`에 `Map`으로 정의돼 있습니다.

```java
private static final Map<String, String> TR_PATH_MAP = Map.ofEntries(
    // 종목 정보: /api/dostk/stkinfo
    Map.entry("ka10001", "/api/dostk/stkinfo"),
    Map.entry("ka10003", "/api/dostk/stkinfo"),
    Map.entry("ka10015", "/api/dostk/stkinfo"),
    Map.entry("ka10016", "/api/dostk/stkinfo"),
    Map.entry("ka10054", "/api/dostk/stkinfo"),
    Map.entry("ka10059", "/api/dostk/stkinfo"),
    // ... (전체 목록은 다음 편에서)
);
```

`ka10001`은 `/api/dostk/stkinfo` 경로로 매핑돼 있습니다. 호출부는 이 매핑을 직접 알 필요 없이 TR 코드만 넘기면 됩니다.

```java
public JsonNode call(String apiId, Map<String, Object> body) {
    if (!props.enabled()) {
      return null;
    }
    return callWithRetry(apiId, body, null, MAX_RETRY);
}
```

호출 예시는 이렇게 됩니다.

```java
Map<String, Object> body = Map.of(); // TR별 요청 파라미터는 TR 문서 기준 — 이번 편 재료(KiwoomApiClient.java)에는
                                      // ka10001의 구체적인 요청 필드 정의가 포함돼 있지 않아 확인이 필요합니다.
JsonNode response = kiwoomApiClient.call("ka10001", body);
```

내부적으로는 `callWithRetry()`가 토큰 확보 → 세마포어 획득 → rate limit 슬롯 확보 → 실제 HTTP 요청 순으로 진행합니다.

```java
private JsonNode callWithRetry(String apiId, Map<String, Object> body,
    String nextKey, int maxRetry) {
  for (int attempt = 0; attempt <= maxRetry; attempt++) {
    KiwoomToken token = resolveToken(apiId);
    if (token == null) return null;
    if (!tryAcquireSemaphore(apiId)) return null;
    long startMs = System.currentTimeMillis();
    try {
      acquireRateSlot();
      String responseBody = executeRequest(apiId, body, nextKey, token.accessToken());
      JsonNode root = objectMapper.readTree(responseBody);
      int returnCode = root.path("return_code").asInt(-1);
      String returnMsg = root.path("return_msg").asText("");
      if (returnCode == 0) {
        logRawDebug(apiId, body, root, responseBody);
        logKiwoomSuccess(apiId, System.currentTimeMillis() - startMs);
        return root;
      }
      if (shouldRetry(returnCode, returnMsg, apiId, attempt, maxRetry)) continue;
      log.warn("[키움] API 오류 return_code={} msg={} apiId={}", returnCode, returnMsg, apiId);
      logKiwoomError(apiId, System.currentTimeMillis() - startMs, "return_code=" + returnCode);
      return null;
    } catch (WebClientResponseException e) {
      log.warn("[키움] HTTP 오류 status={} apiId={}", e.getStatusCode(), apiId);
      logKiwoomError(apiId, System.currentTimeMillis() - startMs, "HTTP " + e.getStatusCode());
      return null;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return null;
    } catch (Exception e) {
      log.warn("[키움] 호출 실패 apiId={} err={}", apiId, e.getMessage(), e);
      logKiwoomError(apiId, System.currentTimeMillis() - startMs, e.getMessage());
      return null;
    } finally {
      semaphore.release();
    }
  }
  return null;
}
```

전체 코드: https://github.com/SungmanHan/kiwoom-rest-api-java/tree/main/core

## 첫 응답 확인하기

`call()`이 정상적으로 끝나면 `JsonNode`가 반환됩니다. 이 응답에는 앞서 설명한 대로 공통 래퍼가 없고, `return_code` / `return_msg`와 TR 고유 필드가 평탄하게 섞여 있습니다.

```java
int returnCode = root.path("return_code").asInt(-1);
String returnMsg = root.path("return_msg").asText("");
if (returnCode == 0) {
  // 성공 — root 자체가 TR 고유 필드까지 포함한 최종 응답
  return root;
}
```

`ka10001` 응답의 구체적인 필드 이름(현재가·전일대비 등)은 이번 편 재료에는 포함돼 있지 않습니다. 실제 필드 파싱은 이후 편(TR별 응답 처리)에서 다룰 예정이니, 이번 편에서는 "return_code == 0이면 성공, 나머지는 root에서 직접 필드를 꺼내 쓴다"는 응답 구조 자체를 확인하는 데 집중하면 됩니다.

## 여기서 주의해야 할 점

- **토큰 발급 실패 로그에 응답 body를 찍지 마세요.** appkey가 에코백될 수 있습니다. 실제 코드도 HTTP 오류 시 status만 남깁니다.

  ```java
  } catch (WebClientResponseException e) {
    // 응답 body에 appkey가 에코백될 수 있으므로 status만 기록
    log.warn("[키움] 토큰 발급 HTTP 오류 attempt={} status={}", attempt, e.getStatusCode());
  }
  ```

- **`call()`은 실패 시 예외 대신 `null`을 반환**합니다. HTTP 오류, 타임아웃, 토큰 발급 실패까지 전부 `null`로 통일돼 있습니다. 처음 붙이는 입장에서는 "왜 예외가 안 터지지?"라고 당황할 수 있는데, 종목 단위 수집에서 한 종목의 실패가 배치 전체를 죽이지 않도록 하기 위한 설계입니다. 명시적으로 예외를 원한다면 `KiwoomNt2Client`가 `null`을 `Nt2KiwoomException`으로 래핑해줍니다.
- **요청은 전부 POST입니다.** 조회 TR이라도 GET을 시도하면 안 됩니다.
- **TR 코드는 body가 아니라 `api-id` 헤더**에 넣습니다. body에는 조회 파라미터만 담습니다.
- **점검 중에는 `/oauth2/token`이 JSON이 아니라 HTML을 반환**합니다. 이 프로젝트는 응답 첫 글자가 `<`인지로 점검 여부를 판별합니다.

  ```java
  private static boolean isMaintenanceResponse(String body) {
      if (body == null) return false;
      String trimmed = body.trim();
      return !trimmed.isEmpty() && trimmed.charAt(0) == '<';
  }
  ```

  이 체크가 없으면 점검 중 HTML 응답을 JSON으로 파싱하려다 예외가 발생합니다.

## 직접 해보기

1. 환경변수로 App Key / Secret Key를 설정합니다.

   ```bash
   export KIWOOM_APP_KEY=발급받은_앱키
   export KIWOOM_SECRET_KEY=발급받은_시크릿키
   ```

2. 애플리케이션을 기동하면 `KiwoomApiClient`가 Spring 빈으로 등록되고, 토큰은 첫 `call()` 호출 시점에 lazy하게 발급됩니다. 별도의 "토큰 발급 API"를 직접 호출할 필요는 없습니다.
3. 아무 TR이나 하나 호출해봅니다.

   ```java
   JsonNode response = kiwoomApiClient.call("ka10001", Map.of());
   ```

4. 로그에서 `[키움] 토큰 발급 성공. expiresAt=...`가 찍히는지, 그리고 응답이 `null`이 아닌지 확인합니다. `null`이 나온다면 환경변수 미설정이나 `return_code` 오류일 가능성이 높으니 로그의 `return_code`/`return_msg`를 먼저 확인하세요.

## 다음 글 예고

이번 편에서는 인증과 첫 호출까지만 다뤘습니다. 다음 편에서는 `TR_PATH_MAP`에 등록된 전체 TR 코드와, "직관을 배신하는" 예외 케이스들(예: 계좌 조회처럼 생긴 `ka00198`이 실제로는 `/stkinfo`인 경우) — **TR 코드 → Endpoint 라우팅**을 상세히 다룹니다.
