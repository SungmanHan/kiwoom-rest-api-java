# quote — 시세 · 호가 · 체결강도 · 장중수급

| TR | 용도 | 엔드포인트 | 거래소 선택 |
|---|---|---|---|
| **ka10001** | 주식기본정보 + 현재가 | `/stkinfo` | `stk_cd` 접미사 |
| **ka10004** | 10단 호가 | `/mrkcond` | `stk_cd` 접미사 |
| **ka10046** | 체결강도 추이 (시간별) | `/mrkcond` | `stk_cd` 접미사 |
| **ka10063** | 장중 투자자별 매매 | `/mrkcond` | `stex_tp` |
| **ka10065** | 장중 투자자별 매매 **상위** | `/rkinfo` | `stex_tp` |
| **ka10051** | 업종별 투자자 순매수 (시장 전체) | `/sect` | `stex_tp` |

**같은 폴더에서 두 메커니즘이 섞입니다.** 단일 종목 TR은 접미사, 시장 단위 TR은 파라미터입니다.

| 파일 | 대상 |
|---|---|
| [`KiwoomOrderBookProvider.java`](KiwoomOrderBookProvider.java) | ka10004 — 10단 호가 정규화 (권장 구현) |
| [`Ka10004_OrderBook_Legacy.java`](Ka10004_OrderBook_Legacy.java) | ka10004 — 총잔량 집계 + 필드명 fallback 패턴 |
| [`KiwoomCntrStrengthProvider.java`](KiwoomCntrStrengthProvider.java) | ka10046 — 체결강도 추이 + 오후 반전 감지 |
| [`Ka10046_ExecStrength_Legacy.java`](Ka10046_ExecStrength_Legacy.java) | ka10046 — 응답 키 3단 fallback 예시 |
| [`KiwoomIntradayInvestorProvider.java`](KiwoomIntradayInvestorProvider.java) | ka10063 + ka10065 — 빈배열 재시도 포함 |
| [`Ka10063_IntradayFlow.java`](Ka10063_IntradayFlow.java) | ka10063 — 시장 2회 병합 |
| [`Ka10051_MarketWideSupply.java`](Ka10051_MarketWideSupply.java) | ka10051 — 시장 전체 수급, all-or-nothing 정책 |
| [`Ka10001_CurrentPricePolling.java`](Ka10001_CurrentPricePolling.java) | ka10001 — 병렬 폴링 + 시간외 `_AL` |

---

## ka10001 — 기본정보 / 현재가

```java
Map.of("stk_cd", "005930")        // KRX
Map.of("stk_cd", "005930_AL")     // KRX+NXT 통합 (시간외 실거래가)
```

한 번 호출로 **가격 + 52주 + 시총 + 재무**가 다 옵니다. 그래서 폴링 주력으로 쓰기 좋습니다.

| 분류 | 필드 |
|---|---|
| 가격 | `cur_prc`, `base_pric`, `open_pric`, `high_pric`, `low_pric`, `upl_pric`(상한), `lst_pric`(하한) |
| 등락 | `flu_rt`, `pred_pre`, `pre_sig` |
| 거래 | `trde_qty`, `acc_trde_qty`, `trde_pre` |
| 52주 | `250hgst`, `250lwst`, `250hgst_pric_dt`, `250hgst_pric_pre_rt` |
| 시총·주식 | `mac`(억원), `flo_stk`, `dstr_stk`(천주), `dstr_rt`, `for_exh_rt` |
| 재무 | `per`, `pbr`, `roe`, `eps`, `bps` |

⚠️ 재무 지표는 **외부 벤더가 주 1회 갱신**합니다. 실시간이 아닙니다.

### 시간외 가격을 받으려면 `_AL`

정규장 밖에서 plain 코드로 부르면 **정규장 종가**가 옵니다. NXT 체결가가 필요하면 `_AL`을 붙이세요.

접미사는 **배치 진입 시 1회 결정**하세요. 종목마다 시간을 다시 재면 경계에서 종목별로 다른 거래소를 보게 됩니다.

```java
final String sfx = afterHoursSuffix && !resolveStexTp().equals("1") ? "_AL" : "";
```

그리고 **결과 Map의 key는 반드시 plain 코드**로 쓰세요. `_AL` 붙은 코드로 저장하면 이후 lookup이 전부 미스납니다.

---

## ka10004 — 10단 호가

**LIST가 없습니다.** root 직속 60여 개 필드입니다. 1차와 2~5차의 키 패턴이 다릅니다.

```
매수 1차 : buy_fpr_bid      / buy_fpr_req
매수 2~5차: buy_2th_pre_bid  / buy_2th_pre_req   …  buy_5th_pre_bid
매도 1차 : sel_fpr_bid      / sel_fpr_req
매도 2~5차: sel_2th_pre_bid  / sel_2th_pre_req   …
총잔량   : tot_buy_req / tot_sel_req
기준시각 : bid_req_base_tm
```

### 저녁에는 총잔량이 0으로 온다

애프터마켓에는 `tot_buy_req` / `tot_sel_req`가 0입니다. **`ovt_buy_req` / `ovt_sel_req`로 폴백**하세요. (실측 2026-07-23)

```java
long regular = Math.abs(parse(resp.path("tot_buy_req")));
return regular > 0 ? regular : Math.abs(parse(resp.path("ovt_buy_req")));
```

### `stex_tp`가 없다

거래소는 `stk_cd` 접미사로만 고릅니다. `_AL`로 바꾸면 **호가 깊이가 늘어나므로**, 잔량 임계값을 쓰고 있다면 재캘리브레이션이 선결입니다.

---

## ka10046 — 체결강도

응답 LIST 키가 관측된 것만 3가지입니다: **`cntr_str_tm`**(현행) / `stk_ccls_strt_array` / `cntr_str_array`. fallback 체인을 두세요.

배열은 **최신 → 과거** 순. `rows[0]`이 현재값입니다.

- `cntr_str` — 체결강도 (100 기준)
- `trd_tm` — 시각 (있을 때만. **존재 확인 후 쓰세요**)

시간 기반 판정(예: "14:00 이후 반전")을 하려면 `trd_tm` 유무를 먼저 확인해야 합니다. 없으면 그 판정 자체를 하지 마세요 — 없는 걸 0으로 채우면 전부 오전으로 분류됩니다.

---

## ka10063 — 장중 투자자별 매매

```java
Map.of(
  "mrkt_tp",         "P00101",   // KOSPI. KOSDAQ은 P10102
  "amt_qty_tp",      "2",        // 1=금액 / 2=수량(주)
  "invsr",           "6",        // 6=외국인 / 7=기관계
  "frgn_all",        "0",        // ⚠️ "1" 쓰지 말 것
  "smtm_netprps_tp", "0",
  "stex_tp",         resolveStexTp()
)
```

LIST: `opmr_invsr_trde` / 값: `netprps_qty`(수량) 또는 `netprps_amt`(금액)

### ⚠️ `frgn_all="1"` 이면 수급이 2배가 된다

`"1"`로 두면 **KOSPI와 KOSDAQ 응답이 동일하게** 옵니다. 두 시장을 합산하는 코드에서 그대로 2배가 됩니다. `"0"`(전체 외국인)을 쓰세요.

### `mrkt_tp="000"`(전체)를 믿지 마세요

KOSPI만 포함되는 케이스가 있습니다. **KOSPI + KOSDAQ 각각 호출 후 병합**이 안전합니다.
외인·기관 × KOSPI·KOSDAQ = **4회 고정 호출**.

### 빈 배열이 온다

장중인데 `return_code=0` + 빈 배열이 옵니다. 장중 한정 3회 재시도(300ms × attempt).

소진되면 **0이 아니라 null(미수집)** 로 올리세요. "순매수 0"과 "조회 실패"는 다릅니다.

---

## ka10065 — 장중 투자자별 매매 상위

```java
Map.of("trde_tp","1", "mrkt_tp","000", "orgn_tp", orgnTp, "amt_qty_tp","1", "stex_tp","1")
```

`orgn_tp`: **`"9000"`=외국인 / `"9999"`=기관** — ka10063의 `invsr`("6"/"7")과 **코드 체계가 다릅니다.**

LIST: `opmr_invsr_trde_upper` / 값 필드는 `netprps_*`가 아니라 **`netslmt`** 입니다.

---

## ka10051 — 시장 전체 투자자 순매수

한 번 호출로 **종합 + 전 업종**(KOSPI 28개 / KOSDAQ 32개)이 옵니다. 종합만 쓰려면 필터링하세요.

```java
Map.of("mrkt_tp","0",        // 0=KOSPI / 1=KOSDAQ
       "amt_qty_tp","0",     // 0=금액 (단위: 억원)
       "base_dt","20260811",
       "stex_tp","3")
```

LIST: `inds_netprps` / 값: `frgnr_netprps`, `orgn_netprps`, `ind_netprps`

### 종합 row 찾기 — 접미사가 붙는다 (미문서화)

| `stex_tp` | `inds_cd` |
|---|---|
| 1 (KRX) | `001` (KOSPI) / `101` (KOSDAQ) |
| 2 (NXT) | `001_NX` |
| 3 (통합) | `001_AL` |

`equals("001")`로 찾으면 통합 호출에서 못 찾습니다. **prefix 매칭**하세요. (실측 2026-05-28)

```java
inds.equals(code) || inds.startsWith(code + "_")
```

### All-or-nothing 정책

KOSPI 또는 KOSDAQ 중 하나만 실패했을 때 나머지로 "시장 전체"를 만들면 **틀린 숫자에 맞는 라벨**이 붙습니다. 둘 다 성공했을 때만 반환하고, 아니면 통째로 생략하세요.

---

## 실제 응답 예시

운영 중 실제로 받은 응답입니다 (공개 시장 데이터). 축약했지만 **필드명·값 형식은 원본 그대로**입니다.

### ka10001 — 삼성전자 (2026-05-06 10:13)

```jsonc
{
  "stk_cd": "005930", "stk_nm": "삼성전자",
  "cur_prc":   "+259500",     // ← 부호는 등락 방향. 가격은 절대값을 쓸 것
  "base_pric": "232500",      // ← 전일 종가 (부호 없음)
  "open_pric": "+254000", "high_pric": "+261500", "low_pric": "+251000",
  "upl_pric":  "+302000",     // 상한가
  "lst_pric":  "-163000",     // 하한가 (부호가 -로 온다)
  "pred_pre": "+27000", "pre_sig": "2", "flu_rt": "+11.61",
  "trde_qty": "21257584", "trde_pre": "-64.57",

  "250hgst": "+261500", "250hgst_pric_dt": "20260506", "250hgst_pric_pre_rt": "-0.76",
  "250lwst":  "-53700", "250lwst_pric_dt": "20250526", "250lwst_pric_pre_rt": "+383.24",

  "mac": "15171093",          // 시가총액 — 억원 → ×1e8 = 1,517조
  "flo_stk": "5846279",       // 상장주식수 — 천주
  "dstr_stk": "4441577",      // 유통주식수 — 천주 → ×1000
  "dstr_rt": "76.0",
  "for_exh_rt": "+49.37",     // 외국인 소진률

  "per": "39.54", "pbr": "4.06", "roe": "10.9", "eps": "6564", "bps": "63976",
  "cap": "7780", "fav": "100", "fav_unit": "원", "setl_mm": "12",
  "return_code": 0, "return_msg": "정상적으로 처리되었습니다"
}
```

`oyr_hgst`/`oyr_lwst`(연중 최고/최저)와 `250hgst`/`250lwst`(250일)는 **다른 필드**입니다. 52주 신고가를 원하면 `250*` 쪽입니다.

### ka10004 — 호가 (삼성전자, 2026-05-06 10:13)

**LIST가 없는 root 직속 71개 필드**입니다.

```jsonc
{
  "bid_req_base_tm": "101339",       // 기준시각 HHmmss

  "sel_fpr_bid": "+259500",          // 매도 1차 호가
  "sel_fpr_req": "100124",           //          잔량
  "sel_2th_pre_bid": "+260000",      // 매도 2차 — 키 패턴이 1차와 다르다
  "sel_2th_pre_req": "28646",
  "sel_2th_pre_req_pre": "+3",       // 직전 대비 증감

  "buy_fpr_bid": "+259000",          // 매수 1차
  "buy_fpr_req": "66904",
  "buy_2th_pre_bid": "+258500",
  "buy_2th_pre_req": "83724",

  "tot_sel_req": "947512",           // 총 매도잔량
  "tot_buy_req": "490989",           // 총 매수잔량
  "tot_sel_req_jub_pre": "--47",     // ← 여기도 dash 2개 음수

  "ovt_sel_req": "0",                // 시간외 — 정규장엔 0
  "ovt_buy_req": "0",                //   저녁엔 tot_* 가 0이고 여기에 값이 온다
  "return_code": 0
}
```

10단까지 있으므로 `sel_10th_pre_bid` 까지 존재합니다.

### ka10046 — 체결강도 (삼성전자)

```jsonc
{
  "cntr_str_tm": [                   // ← 60행, 최신 → 과거
    {
      "cntr_tm": "101700",           // 시각
      "cur_prc": "+260000", "flu_rt": "+11.83",
      "trde_qty": "49571",
      "acc_trde_prica": "5557461", "acc_trde_qty": "21651592",
      "cntr_str":       "146.41",    // 현재 체결강도 (100 기준)
      "cntr_str_5min":  "146.69",
      "cntr_str_20min": "150.26",
      "cntr_str_60min": "148.97",
      "stex_tp": "KRX"
    }
  ],
  "return_code": 0
}
```

`cntr_str_5min` / `_20min` / `_60min` 이 **응답에 이미 들어 있습니다.** 직접 이동평균을 낼 필요가 없습니다.
시각 필드는 `cntr_tm`인데, 코드에 따라 `trd_tm`으로 관측되는 경우가 있어 존재 확인 후 쓰세요.

### ka10063 — 장중 투자자별 (외국인, mrkt_tp=000)

```jsonc
{
  "opmr_invsr_trde": [               // ← 800행
    {
      "stk_cd": "900110", "stk_nm": "딥커머스",
      "cur_prc": "+1185", "flu_rt": "+1.20", "acc_trde_qty": "15970",

      "netprps_amt": "+2",           // 순매수 금액
      "netprps_qty": "+2000",        // 순매수 수량 (amt_qty_tp=2 일 때 이걸 씀)
      "buy_amt": "+9",  "buy_qty": "+8000",
      "sell_amt": "--7",             // ← -7
      "sell_qty": "--6000",          // ← -6000
      "netprps_amt_irds": "+1",      // 직전 대비 증감
      "prev_netprps_amt": "+1"
    }
  ],
  "return_code": 0
}
```

### ka10065 — 장중 투자자별 상위 (외국인)

```jsonc
{
  "opmr_invsr_trde_upper": [         // ← 100행
    { "stk_cd": "000660", "stk_nm": "SK하이닉스",
      "sel_qty": "-401450", "buy_qty": "+737424",
      "netslmt": "+335974" }         // ← netprps_* 가 아니라 netslmt
  ],
  "return_code": 0
}
```

이 TR의 `stk_cd`에는 **접미사가 없습니다**(`stex_tp=1`로 호출). `stex_tp=3`으로 바꾸면 붙습니다.

### ka10051 — 시장 전체 투자자 순매수 (KOSPI)

```jsonc
{
  "inds_netprps": [                  // ← 28행 = 종합 1 + 업종 27
    {
      "inds_cd": "001",              // ← 종합(KOSPI). stex_tp=3 이면 "001_AL"
      "inds_nm": "종합(KOSPI)",
      "cur_prc": "+838696", "pred_pre": "+33945", "flu_rt": "422",

      "frgnr_netprps": "+556",       // 외국인  (억원)
      "orgn_netprps":  "+3638",      // 기관계
      "ind_netprps":   "-3377",      // 개인

      "sc_netprps": "+2693",         // 증권
      "insrnc_netprps": "-26", "invtrt_netprps": "-381", "bank_netprps": "-20",
      "jnsinkm_netprps": "-22", "endw_netprps": "+1478",
      "etc_corp_netprps": "-838", "samo_fund_netprps": "-85",
      "native_trmt_frgnr_netprps": "+21", "natn_netprps": "+0"
    }
    // ... 업종 27개
  ],
  "return_code": 0
}
```

⚠️ `flu_rt: "422"` — 다른 TR의 `flu_rt`(`"+11.61"` 같은 소수)와 **형식이 다릅니다.** `pred_pre/cur_prc`로 역산하면 4.05%가 나와 "422 = 4.22%"와도 정확히 맞지 않습니다. 이 필드는 그대로 쓰지 말고 **직접 계산**하거나 용도를 먼저 확인하세요.

`orgn_netprps`(기관계)는 세부 기관(`sc_` 증권 / `insrnc_` 보험 / `bank_` 은행 / `endw_` 연기금 …)의 **합계**입니다. 둘을 더하면 이중 계상됩니다.
