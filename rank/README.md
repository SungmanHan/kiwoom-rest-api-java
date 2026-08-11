# rank — 순위 조회

전부 **`stex_tp` 파라미터 방식**입니다 (접미사 아님). 다만 **응답의 `stk_cd`에는 접미사가 붙어서** 옵니다.

| TR | 용도 | 엔드포인트 | LIST 키 |
|---|---|---|---|
| **ka10030** | 당일 거래량/거래대금 상위 | `/rkinfo` | `tdy_trde_qty_upper` |
| **ka10032** | 거래대금 상위 (현재/전일 순위) | `/rkinfo` | `trde_prica_upper` |
| **ka10034** | 외인 기간별 매매 상위 | `/rkinfo` | `for_dt_trde_upper` |
| **ka10044** | 일별 기관매매 종목 | **`/mrkcond`** ⚠️ | `daly_orgn_trde_stk` |
| **ka10016** | 신고저가 | `/stkinfo` | `ntl_pric` |
| **ka00198** | 실시간 종목조회 순위 (관심도) | `/stkinfo` | `item_inq_rank` |

| 파일 | 대상 |
|---|---|
| [`Nt2MarketValueProvider.java`](Nt2MarketValueProvider.java) | ka10030 — 거래대금 게이트 |
| [`KiwoomRankDeltaProvider.java`](KiwoomRankDeltaProvider.java) | ka10032 — 순위 변동 |
| [`KiwoomForeignPeriodProvider.java`](KiwoomForeignPeriodProvider.java) | ka10034 — 4기간 외인 누적 |
| [`KiwoomInstitutionPeriodProvider.java`](KiwoomInstitutionPeriodProvider.java) | ka10044 — 4기간 기관 누적 |
| [`KiwoomNewHighProvider.java`](KiwoomNewHighProvider.java) | ka10016 — 5기간 신고가 |
| [`KiwoomAttentionRankProvider.java`](KiwoomAttentionRankProvider.java) | ka00198 — 관심순위 TOP20 |

---

## ka10030 — 거래량/거래대금 상위

```java
Map.of(
  "mrkt_tp",       "000",     // 전체
  "sort_tp",       "3",       // 3 = 거래대금 정렬
  "mang_stk_incls","16",      // 관리종목 등 복합 제외 코드
  "crd_tp",        "0",
  "trde_qty_tp",   "0",
  "pric_tp",       "7",
  "trde_prica_tp", "5000",    // ★ 거래대금 하한 게이트 — 5000 = 500억
  "mrkt_open_tp",  "0",       // 0=전체 / 1=장중
  "stex_tp",       "3"        // KRX+NXT 통합
)
```

**`trde_prica_tp`가 서버 사이드 필터**입니다. 여기서 걸러 받으면 클라이언트에서 100~200종목만 다루면 됩니다.

`trde_amt` 단위는 **백만원**. (검증: 삼성전자 11,526,863 → 11.5조원)

### 비대칭 주의

유니버스를 `stex_tp=3`(통합)으로 뽑아 놓고, 그 종목들의 호가(ka10004)·차트(ka10081)는 접미사를 안 붙여 KRX만 보는 상태가 흔합니다. **선정 기준과 상세 조회의 거래소가 다릅니다.** 의도한 게 아니면 맞추세요.

---

## ka10032 — 거래대금 상위 (순위 변동)

```java
Map.of("mrkt_tp","000", "mang_stk_incls","1", "stex_tp","3")
```

`now_rank` / `pred_rank`가 같이 옵니다. `pred_rank - now_rank` > 0 이면 순위 **상승**.

ka10030과 대상이 겹치지만, **"어제 대비 얼마나 올라왔는가"** 는 여기서만 나옵니다.

---

## ka10034 / ka10044 — 외인 / 기관 기간별

두 TR의 **파라미터 체계가 다릅니다.**

### ka10034 (외인) — 기간을 코드로

```java
Map.of("mrkt_tp","000", "trde_tp","2",       // 2 = 순매수
       "dt", "1",                             // 1/2/3/4 = 5일/10일/20일/60일
       "stex_tp","3")
```

값: `netprps_qty` (**수량, 주**)

### ka10044 (기관) — 기간을 날짜 범위로

```java
Map.of("strt_dt","20260805", "end_dt","20260810",
       "trde_tp","2",
       "mrkt_tp","001",        // ⚠️ 001=KOSPI / 101=KOSDAQ — 시장별 호출 필요
       "stex_tp","3")
```

값: `netprps_amt` (**금액**)

| | ka10034 | ka10044 |
|---|---|---|
| 기간 지정 | `dt` 코드 | `strt_dt`/`end_dt` |
| 시장 | `000` 한 번 | `001`+`101` 각각 |
| 값 | 수량(주) | 금액 |
| 슬롯당 호출 | 4회 | **8회** (4기간 × 2시장) |

같은 개념인데 대칭이 아닙니다. 수량과 금액을 섞어서 비교하지 마세요.

---

## ka10016 — 신고저가

```java
Map.of("mrkt_tp","000", "ntl_tp","1",           // 1 = 신고가
       "high_low_close_tp","1", "stk_cnd","0",
       "trde_qty_tp","0", "crd_cnd","0",
       "updown_incls","1",
       "dt", "250",                              // 5/20/60/120/250
       "stex_tp","3")
```

기간별로 **각각 호출**해야 합니다. 5기간이면 슬롯당 5회.

---

## ka00198 — 실시간 종목조회 순위 (관심도)

HTS `[0198]` 화면에 해당합니다. "지금 사람들이 뭘 보고 있나"를 보여 줍니다.

```java
Map.of("qry_tp", "4")     // 4 = 당일 누적 / 1 = 1분
```

| 필드 | 의미 |
|---|---|
| `bigd_rank` | 순위 |
| `stk_cd` / `stk_nm` | 종목 (6자리, **접미사 없음**) |
| `rank_chg` | 순위 변동 — 부호가 값에 포함 (`"+1"`, `"-3"`) |
| `base_comp_chgr` | 기준 대비 등락률 |
| `tm` | 응답 시각 |

### ⚠️ 제약이 많습니다

- **TOP20 고정** — 확장 불가
- **`cont-yn: N`** — 연속조회 불가
- **과거 조회 불가** — 수집 시점에 안 받아둔 값은 **영구 복구 불가**

마지막 항목 때문에, 쓸지 안 쓸지 모르는 필드도 **일단 원본 그대로 적재**해 두는 게 맞습니다.

### `rank_chg` 파싱

- 공백은 **0이 아니라 null** 로 두세요. "변동 없음(0)"과 "값 없음"이 다릅니다.
- **`rank_chg_sign`은 쓰지 마세요** — 변동 없을 때 빈 문자열이라 부호원으로 못 씁니다. 부호는 `rank_chg` 값 문자열에 들어 있습니다.

### 갱신 주기가 동결될 때가 있다

같은 `tm`(예: `210000`)으로 여러 번 응답이 오는 구간이 있습니다. 이때 두 호출을 비교하면 "값이 안 바뀐다"는 잘못된 결론이 납니다. **`tm`이 다른 시점끼리 비교**하세요. (실측 2026-07-26 오판 → 2026-07-27 정정)

---

## 공통 — 응답 종목코드 접미사 제거

`stex_tp=3`으로 요청하면 응답 `stk_cd`가 `"005930_AL"` 입니다. `ka00198`만 예외적으로 접미사가 없습니다.

```java
if (raw.endsWith("_AL") || raw.endsWith("_NX")) raw = raw.substring(0, raw.length() - 3);
```

이걸 빼먹으면 **예외 없이 조용히 Map lookup이 전부 실패**합니다. 순위 데이터가 통째로 안 붙는데 에러는 안 납니다.
