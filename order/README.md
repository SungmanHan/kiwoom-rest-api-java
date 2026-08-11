# order — 주문 발주 · 정정 · 취소 · 체결확인

가장 위험한 영역입니다. **여기서 실수하면 돈이 나갑니다.**

구현: [`KiwoomOrderClient.java`](KiwoomOrderClient.java)

---

## TR 목록

| TR | 용도 | 엔드포인트 |
|---|---|---|
| **kt10000** | 주식 매수주문 | `/api/dostk/ordr` |
| **kt10001** | 주식 매도주문 | `/api/dostk/ordr` |
| **kt10002** | 주식 정정주문 | `/api/dostk/ordr` |
| **kt10003** | 주식 취소주문 | `/api/dostk/ordr` |
| **kt00007** | 계좌별 주문체결내역 상세 | `/api/dostk/acnt` |
| **ka10075** | 미체결 조회 | `/api/dostk/acnt` |

주문 관련이지만 조회 2종은 **`/ordr`가 아니라 `/acnt`** 입니다.

---

## 요청 body

### 매수 / 매도 (kt10000 / kt10001)

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

응답: `ord_no` (주문번호). `return_code=0` 이고 `ord_no`가 비어 있지 않아야 접수 성공입니다.

### 정정 (kt10002)

```java
Map.of(
  "dmst_stex_tp", "KRX",
  "orig_ord_no",  "0000123",
  "stk_cd",       "005930",
  "mdfy_qty",     "0",          // "0" = 잔량 전부
  "mdfy_uv",      "70500"       // 스펙상 필수
)
```

### 취소 (kt10003)

```java
Map.of(
  "dmst_stex_tp", "KRX",
  "orig_ord_no",  "0000123",
  "stk_cd",       "005930",
  "cncl_qty",     "0"           // "0" = 잔량 전부
)
```

부분체결분은 브로커가 자동 보존합니다.

---

## ⛔ 절대 재시도하지 마세요

`kt10000` / `kt10001` / `kt10003`은 **비멱등**입니다. 응답 타임아웃이 났다고 재발주하면 이중 체결됩니다.

**타임아웃 시 올바른 순서:**

```
1. ka10075 (미체결 조회, all_stk_tp=1 + stk_cd) 로 이미 접수됐는지 확인
2. 없을 때만 재발주
```

Rate limit 재시도(1687/1700)는 클라이언트 내부에서 일어나므로, **주문 경로는 그 재시도 정책도 확인**하세요. 이 프로젝트는 발주 메서드에서 `apiClient.call()`을 1회만 호출하고 자체 재시도를 하지 않습니다.

이중 발주 방지는 API 레이어가 아니라 **호출자의 원자적 상태 전이**(claim UPDATE)로 막는 게 맞습니다.

---

## 정정은 "취소 + 재발주"다

`kt10002` 접수 시 **원 `ord_no`는 무효화**되고 새 `ord_no`가 발급됩니다.

원 주문번호로 계속 체결 조회를 하면 영원히 안 잡힙니다. **응답의 새 `ord_no`로 갈아타세요.**

---

## 체결 확인 (kt00007)

### 접수 직후 확인

```java
Map.of(
  "qry_tp",       "4",      // 4 = 체결내역
  "stk_bond_tp",  "0",
  "sell_tp",      "0",      // 0=전체 / 1=매도 / 2=매수
  "fr_ord_no",    ordNo,
  "dmst_stex_tp", "KRX"
)
```

응답 `acnt_ord_cntr_prps_dtl[]` 에서 `ord_no`가 일치하는 행을 찾아:

| 필드 | 의미 |
|---|---|
| `cntr_qty` | 체결 수량 |
| `cntr_uv` | 체결 단가 |
| `ord_remnq` | 잔량 — **`0`이고 `cntr_qty > 0`이면 전량 체결** |

### 과거 일자 조회

`ka10076` / `ka10077`은 **당일 세션만** 커버합니다. 과거 일자는 `kt00007`에 `ord_dt`를 넣으세요.

```java
Map.of("qry_tp","4", "stk_bond_tp","1", "sell_tp","1",
       "ord_dt","20260714", "stk_cd","000660", "dmst_stex_tp","%")
```

`dmst_stex_tp="%"` = 거래소 와일드카드. (실측 2026-07-15: `return_code=0`, 1건 반환)

### ⚠️ `sell_tp=0`으로 평균가를 내지 마세요

전체 조회는 **매수·매도가 섞여서** 옵니다. 그대로 가중평균을 내면 값이 오염됩니다. 방향별 집계가 필요하면 `sell_tp`를 명시하거나 `io_tp_nm`으로 필터링하세요.

체결이 **트랜치로 분할**되는 경우도 흔하니, 평균 단가는 수량 가중으로 계산하세요.

---

## 미체결 조회 (ka10075)

```java
// 특정 종목
Map.of("all_stk_tp","1", "stk_cd", itemCode, "trde_tp","0", "stex_tp","0")
// 전체
Map.of("all_stk_tp","0", "trde_tp","0", "stex_tp","0")
```

응답 LIST 키는 **`oso`**. 주요 필드: `ord_no`, `stk_cd`, `ord_qty`, `ord_pric`, `oso_qty`(미체결수량), `orig_ord_no`, `ord_stt`.

---

## NXT 제약

| 항목 | 제약 |
|---|---|
| 시장가 | **미지원** — `trde_tp=3` 은 `407022` 거부. 지정가만. |
| 모의투자 | **미지원** — `mockapi.kiwoom.com`은 KRX만. NXT 검증은 운영계에서만. |

저녁 애프터마켓(15:40~20:00)에 발주한다면 **지정가 전제로 설계**해야 합니다.

---

## 킬스위치 · dry-run은 진입부에서 강제하세요

매수·매도·취소가 **같은 게이트**를 통과하게 만드세요. 경로별로 따로 붙이면 반드시 하나를 빠뜨립니다.

```java
private Optional<OrderResult> killSwitchGate(String logCtx) {
    if (!orderMode.isEnabled()) return Optional.of(OrderResult.failed(-1, null));   // 발주 차단
    if (orderMode.isDryRun())   return Optional.of(OrderResult.dryRun("DRYRUN-" + ...)); // 가상 ord_no
    return Optional.empty();   // 실발주 진행
}
```

dry-run이 **가상 주문번호를 반환**하는 게 포인트입니다. 그래야 하위 상태머신을 실제 발주와 똑같은 경로로 검증할 수 있습니다.
