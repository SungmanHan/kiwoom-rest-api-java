# 키움 REST API 연속조회 구현: cont-yn / next-key 헤더로 다음 페이지 받기 (Java)

> 키움 REST API Java 학습 시리즈 10/11편

## 이번 글에서 배울 것

- 키움 REST API가 "다음 페이지"를 요청하는 방식 (`cont-yn`, `next-key` 헤더)
- 요청은 전부 `POST` + JSON body인데, 연속조회 정보는 body가 아니라 **헤더**로 오간다는 점
- 연속조회 루프를 짤 때 반드시 같이 챙겨야 할 Rate Limit·에러 코드 처리
- 연속조회 도중 실패했을 때 "조회 실패"와 "값이 0"을 구분해야 하는 이유

## 연속조회는 헤더로 한다

키움 REST API는 모든 요청이 `POST` + JSON body입니다. GET은 없습니다. 그리고 TR 코드(`api-id`)도 body가 아니라 헤더로 들어갑니다. 연속조회도 같은 방식으로 **헤더**를 사용합니다.

core 모듈 README가 정리한 공통 요청 헤더는 다음과 같습니다.

```
api-id        : ka10001          ← TR 코드가 헤더로 들어간다 (body 아님)
authorization : Bearer {token}
cont-yn       : N | Y            ← 연속조회
next-key      : {키}
Content-Type  : application/json;charset=UTF-8
```

- `cont-yn` : `N` 또는 `Y`. 연속조회 여부를 나타내는 헤더입니다.
- `next-key` : 다음 조회를 이어가기 위한 키 값입니다.

> 확인이 필요합니다: 이번 편의 재료(core README)에는 `cont-yn` / `next-key` 헤더의 존재와 값 형태(`N | Y`, `{키}`)만 나와 있습니다. **응답 헤더에서 어떤 값이 내려오는지, 마지막 페이지를 어떻게 판별하는지**는 재료에 서술돼 있지 않아 이 글에서 단정하지 않습니다. 실제 TR의 응답 헤더는 키움 공식 문서나 `KiwoomApiClient.java` 원본에서 확인하세요.

또 하나 기억할 점은, 응답에 공통 래퍼가 없다는 것입니다. `return_code` / `return_msg`와 각 TR 고유 필드가 평탄하게 섞여 오므로, 연속조회 루프에서도 매 응답마다 `return_code`를 먼저 확인해야 합니다.

## 실제 코드 살펴보기

이번 편의 재료는 core README 하나뿐이며, 연속조회 루프 자체의 소스 코드는 포함돼 있지 않습니다. 그래서 루프의 **뼈대는 README가 보여 주는 규칙만으로 설명**합니다. 아래는 코드가 아니라 절차입니다.

1. 첫 호출은 `cont-yn: N` 으로 보낸다.
2. 응답의 `return_code` 가 `0` 인지 확인한다. (`0` 이 아니면 아래 "에러 코드" 표를 따른다.)
3. 서버가 "이어서 조회할 데이터가 있다"고 알려 오면, 받은 `next-key` 를 요청 헤더에 담고 `cont-yn: Y` 로 같은 TR을 다시 호출한다.
4. 더 이상 이어질 데이터가 없을 때까지 3을 반복한다.
5. 매 호출 사이에는 Rate Limit 간격을 지킨다.

3~4번에서 "서버가 무엇으로 알려 오는지"가 위에서 말한 확인 필요 항목입니다.

호출 사이의 간격을 지키는 부분은 README에 실제 코드가 있습니다. 루프의 각 반복이 이 함수를 거친다고 생각하면 됩니다.

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

전체 코드: https://github.com/SungmanHan/kiwoom-rest-api-java/tree/main/core

## 여기서 주의해야 할 점

### 1. 페이지마다 호출이 늘어나니 Rate Limit이 곧 병목이다

문서에 한도 명시가 없고, COM API와 백엔드를 공유하므로 이 프로젝트는 **초당 5회**를 가정해 운영합니다. 연속조회는 한 번의 논리적 조회가 여러 번의 HTTP 호출로 바뀌므로 한도에 가장 빨리 닿는 패턴입니다. 위 `acquireRateSlot()` 처럼 호출 간 인터벌(200ms)을 루프 안에서도 지키세요. 인터벌과 동시 in-flight 제한(`Semaphore`)은 **다른 축**이라 둘 다 필요합니다.

### 2. 루프 도중 에러 코드는 종류별로 다르게 다룬다

| `return_code` | 의미 | 대응 |
|---|---|---|
| `0` | 성공 | — |
| `1687`, `1700` | Rate limit | 지수 백오프(200/500/1000ms) 재시도 |
| `8005`, `8010` | 토큰 무효 | 토큰 폐기 → 재발급 → 재시도 |
| `3` + msg에 8005/8010 | 토큰 무효 (변형) | 위와 동일 — **메시지까지 검사** |

토큰 무효 판별은 코드 값뿐 아니라 메시지까지 봅니다.

```java
boolean tokenInvalid = rc == 8005 || rc == 8010 || msg.contains("8005") || msg.contains("8010");
```

> 확인이 필요합니다: 재시도할 때 같은 `next-key` 로 다시 보내야 하는지 README에는 명시가 없습니다. 재시도가 페이지를 건너뛰거나 중복 수집하지 않는지는 실제 TR로 확인하세요.

### 3. 중간 페이지 실패를 "끝"으로 착각하지 않는다

이 프로젝트의 `KiwoomApiClient.call()` 은 HTTP 오류·타임아웃·토큰 실패를 포함한 **모든 실패에 `null`을 반환**합니다. 연속조회 루프에서 `null`을 "더 이상 데이터 없음"과 같게 처리하면, 3페이지째에서 실패했는데 마치 2페이지까지가 전부인 것처럼 조용히 종료됩니다.

> 어느 쪽이든 **"조회 실패(0)"와 "값이 실제로 0"을 구분**하세요.

null 반환 대신 예외로 올리고 싶다면 `KiwoomNt2Client` 가 null을 `Nt2KiwoomException` 으로 래핑해 줍니다. 페이지네이션처럼 중간 실패를 반드시 구분해야 하는 호출부에 적합합니다. (두 스타일의 비교는 다음 편의 주제입니다.)

### 4. 조회 TR만 루프에 태운다

⛔ **주문 TR은 재시도 경로에 태우지 마세요.** 주문은 비멱등입니다. 연속조회 루프는 조회성 TR에만 적용하고, 재시도·반복 호출 로직이 주문 TR로 새지 않게 분리하세요.

### 5. 점검 중에는 루프를 시작하지 않는다

점검 중 `/oauth2/token` 은 JSON이 아니라 HTML을 반환합니다. 호출부는 `isMaintenanceActive()` 하나로 작업 자체를 건너뛸 수 있으므로, 여러 페이지를 도는 긴 루프는 시작 전에 이 값을 확인하면 실패 로그가 쏟아지는 것을 막을 수 있습니다.

## 이번 글 정리

- 연속조회는 body가 아니라 **`cont-yn` / `next-key` 헤더**로 한다.
- README가 보여 주는 헤더 구성은 `cont-yn: N | Y` 와 `next-key: {키}` 까지이며, 첫 호출·이어받기 호출에 각각 어떤 값을 싣는지는 공식 문서로 확인한다.
- 루프의 각 반복마다 Rate Limit 간격(200ms)과 `return_code` 검사를 거친다.
- 실패(`null`)와 정상 종료를 구분하지 않으면 데이터가 조용히 잘린다.
- 응답 헤더의 종료 판별 방식은 재료에 없으므로 공식 문서·원본 소스로 확인한다.

## 다음 글 예고

다음 편은 **실패 정책 — null vs 예외** 입니다. 이번 글에서 잠깐 등장한 `KiwoomApiClient.call()` 의 `null` 반환과 `Nt2KiwoomException` 래핑, 두 스타일이 왜 공존하는지를 다룹니다.

전체 코드: https://github.com/SungmanHan/kiwoom-rest-api-java/tree/main/core
