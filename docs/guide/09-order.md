# 키움 REST API 주문 발주·정정·취소 Java 구현 — 미체결 조회와 재시도 금지 규칙 (9/11)

키움 REST API Java 연동 시리즈 9편입니다. 이번 편은 시리즈에서 **가장 위험한 영역**인 주문(kt10000~kt10003)과 미체결 조회(ka10075)를 다룹니다. 조회 API와 달리 주문 API는 호출 한 번이 실제 계좌에 영향을 주므로, 재시도 정책과 상태 관리가 핵심입니다.

## 이번 글에서 배울 것

- 주문 TR 4종(kt10000 / kt10001 / kt10002 / kt10003)의 요청 body 구성
- 주문 접수 성공을 판정하는 기준
- 비멱등 주문 API를 **재시도하면 안 되는 이유**와 타임아웃 시 올바른 순서
- 정정이 "취소 + 재발주"로 동작한다는 점과 새 `ord_no`로 갈아타야 하는 이유
- ka10075 미체결 조회와 킬스위치·dry-run 게이트 설계

## TR 목록과 엔드포인트

| TR | 용도 | 엔드포인트 |
|---|---|---|
| kt10000 | 주식 매수주문 | `/api/dostk/ordr` |
| kt10001 | 주식 매도주문 | `/api/dostk/ordr` |
| kt10002 | 주식 정정주문 | `/api/dostk/ordr` |
| kt10003 | 주식 취소주문 | `/api/dostk/ordr` |
| kt00007 | 계좌별 주문체결내역 상세 | `/api/dostk/acnt` |
| ka10075 | 미체결 조회 | `/api/dostk/acnt` |

주문과 관련된 조회 2종(kt00007, ka10075)은 `/ordr`가 아니라 **`/acnt`** 엔드포인트입니다. 라우팅을 TR 이름 접두사로 추측하면 틀립니다. (TR과 엔드포인트 매핑은 2편에서 다뤘습니다.)

구현 클래스는 `KiwoomOrderClient.java` 입니다.

## 실제 코드 살펴보기

### 발주 요청 body (kt10000 / kt10001)

```java
Map.of(
  "dmst_stex_tp", "KRX",        // KRX / NXT / SOR
  "stk_cd",       "005930",
  "ord_qty",      "10",
  "ord_uv",       "71000",      // 지정가. 시장가는 빈 문자열 ""
  "trde_tp",      "0",          // 0=지정가 / 3=시장가 / 81=장마감후시간외
  "cond_uv",      ""
)
```

응답의 `ord_no`가 주문번호입니다. **`return_code=0` 이고 `ord_no`가 비어 있지 않아야** 접수 성공으로 봅니다. `return_code`만 확인하면 부족합니다.

### 정정 요청 body (kt10002)

```java
Map.of(
  "dmst_stex_tp", "KRX",
  "orig_ord_no",  "********",
  "stk_cd",       "005930",
  "mdfy_qty",     "0",          // "0" = 잔량 전부
  "mdfy_uv",      "70500"       // 스펙상 필수
)
```

### 취소 요청 body (kt10003)

```java
Map.of(
  "dmst_stex_tp", "KRX",
  "orig_ord_no",  "********",
  "stk_cd",       "005930",
  "cncl_qty",     "0"           // "0" = 잔량 전부
)
```

`mdfy_qty` / `cncl_qty`에 `"0"`을 주면 잔량 전부가 대상입니다. 부분 체결된 수량은 브로커가 자동으로 보존합니다.

### 미체결 조회 (ka10075)

```java
// 특정 종목
Map.of("all_stk_tp","1", "stk_cd", itemCode, "trde_tp","0", "stex_tp","0")
// 전체
Map.of("all_stk_tp","0", "trde_tp","0", "stex_tp","0")
```

응답의 LIST 키는 **`oso`** 입니다. 주요 필드는 `ord_no`, `stk_cd`, `ord_qty`, `ord_pric`, `oso_qty`(미체결수량), `orig_ord_no`, `ord_stt` 입니다.

## 여기서 주의해야 할 점

### 1. 비멱등 주문은 절대 재시도하지 않는다

`kt10000` / `kt10001` / `kt10003`은 **비멱등**입니다. 응답 타임아웃이 났다고 같은 요청을 다시 보내면 이중 접수가 될 수 있습니다. 타임아웃은 "실패"가 아니라 "결과를 모름"입니다.

**타임아웃 시 올바른 순서**

```
1. ka10075 (미체결 조회, all_stk_tp=1 + stk_cd) 로 이미 접수됐는지 확인
2. 없을 때만 재발주
```

Rate limit 재시도(1687/1700)는 클라이언트 내부에서 일어나므로, 주문 경로가 그 재시도 정책을 타는지도 확인해야 합니다. 이 프로젝트는 발주 메서드에서 `apiClient.call()`을 **1회만** 호출하고 자체 재시도를 하지 않습니다.

이중 발주 방지는 API 레이어가 아니라 **호출자의 원자적 상태 전이**(claim UPDATE)로 막는 것이 맞습니다.

### 2. 정정은 "취소 + 재발주"다

`kt10002`가 접수되면 **원 `ord_no`는 무효화**되고 새 `ord_no`가 발급됩니다. 원래 주문번호로 계속 체결 조회를 하면 영원히 잡히지 않습니다. 응답의 새 `ord_no`로 갈아타야 합니다.

### 3. 조회 TR의 경로와 키

ka10075 응답 LIST 키는 `oso`, 엔드포인트는 `/acnt` 입니다. 주문 TR(`/ordr`)과 섞어 쓰지 않도록 주의하세요.

### 4. NXT 제약

| 항목 | 제약 |
|---|---|
| 시장가 | 미지원 — `trde_tp=3` 은 `407022` 로 거부됩니다. 지정가만 가능 |
| 모의투자 | 미지원 — `mockapi.kiwoom.com`은 KRX만 지원. NXT 검증은 운영계에서만 가능 |

저녁 애프터마켓(15:40~20:00)에 주문을 낼 계획이라면 **지정가 전제로 설계**해야 합니다.

### 5. 킬스위치·dry-run은 진입부에서 강제한다

발주·취소가 **같은 게이트**를 통과하게 만드세요. 경로별로 따로 붙이면 반드시 하나를 빠뜨립니다.

```java
private Optional<OrderResult> killSwitchGate(String logCtx) {
    if (!orderMode.isEnabled()) return Optional.of(OrderResult.failed(-1, null));   // 발주 차단
    if (orderMode.isDryRun())   return Optional.of(OrderResult.dryRun("DRYRUN-" + ...)); // 가상 ord_no
    return Optional.empty();   // 실발주 진행
}
```

dry-run이 **가상 주문번호를 반환**하는 것이 포인트입니다. 그래야 하위 상태머신을 실제 발주와 같은 경로로 검증할 수 있습니다.

## 체결 확인 (kt00007) 덧붙임

접수 직후 체결 여부는 kt00007로 확인합니다.

```java
Map.of(
  "qry_tp",       "4",      // 4 = 체결내역
  "stk_bond_tp",  "0",
  "sell_tp",      "0",      // 0=전체 / 1=매도 / 2=매수
  "fr_ord_no",    ordNo,
  "dmst_stex_tp", "KRX"
)
```

응답 `acnt_ord_cntr_prps_dtl[]` 에서 `ord_no`가 일치하는 행을 찾습니다.

| 필드 | 의미 |
|---|---|
| `cntr_qty` | 체결 수량 |
| `cntr_uv` | 체결 단가 |
| `ord_remnq` | 잔량 — `0`이고 `cntr_qty > 0`이면 전량 체결 |

- 과거 일자는 `ka10076` / `ka10077`이 아니라 `kt00007`에 `ord_dt`를 넣어 조회합니다. 두 TR은 당일 세션만 커버합니다. `dmst_stex_tp="%"`는 거래소 와일드카드입니다.
- `sell_tp=0`(전체)은 방향이 섞여 오므로 그대로 가중평균을 내면 값이 오염됩니다. `sell_tp`를 명시하거나 `io_tp_nm`으로 필터링하세요. 체결이 여러 트랜치로 나뉠 수 있으니 평균 단가는 수량 가중으로 계산합니다.

## 이번 글 정리

- 주문 TR은 `/ordr`, 조회 TR(kt00007, ka10075)은 `/acnt`
- 접수 성공 = `return_code=0` **그리고** `ord_no` 비어 있지 않음
- kt10000 / kt10001 / kt10003은 비멱등 — 타임아웃 시 ka10075로 먼저 확인하고, 없을 때만 재발주
- 정정(kt10002)은 취소+재발주 — 새 `ord_no`로 추적
- 킬스위치·dry-run은 모든 주문 경로가 공유하는 한 곳에서 강제

전체 코드: https://github.com/SungmanHan/kiwoom-rest-api-java/tree/main/order

## 다음 글 예고

다음 10편은 **연속조회 (cont-yn/next-key)** 입니다. 한 번의 응답에 담기지 않는 목록을 `cont-yn` / `next-key` 헤더로 이어서 가져오는 방법을 다룹니다.
