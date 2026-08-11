# program — 프로그램매매

**엔드포인트가 둘로 갈립니다.** 형제 TR인데 배치가 다릅니다.

| TR | 용도 | 엔드포인트 | LIST 키 |
|---|---|---|---|
| **ka90008** | 종목 시간별 프로그램매매 추이 | `/mrkcond` | `stk_tm_prm_trde_trnsn` |
| **ka90013** | 종목 일별 프로그램매매 추이 | `/mrkcond` | `stk_daly_prm_trde_trnsn` |
| **ka90003** | 프로그램 순매수 상위 50 | **`/stkinfo`** | `prm_netprps_upper_50` |
| **ka90004** | 종목별 프로그램매매 현황 | **`/stkinfo`** | `stk_prm_trde_prst` |

| 파일 | 대상 |
|---|---|
| [`Ka90003_04_08_13_ProgramTrade.java`](Ka90003_04_08_13_ProgramTrade.java) | 4종 전체 구현 |
| [`KiwoomProgramTradeProvider.java`](KiwoomProgramTradeProvider.java) | 통합 진입점 |

**금액 단위는 전부 백만원** → ×1,000,000 해야 원입니다.

---

## ka90008 — 종목 시간별

```java
Map.of("amt_qty_tp","1", "stk_cd", code, "date","20260811")   // 파라미터 3개만
```

배열은 **최신 → 과거**. `rows[0].prm_netprps_amt` = 당일 누적.

### ⚠️ 1회 호출당 ~20틱만 온다

6~7초 간격 × 20 ≈ **직전 2분치**입니다. "당일 30분 차분" 같은 걸 1회 호출로 계산할 수 없습니다.

`rows[0] - rows[last]` 를 "30분 차분"이라 부르면 이름과 실제가 어긋납니다. 연속조회를 구현하든가, **계산 의미를 실제 창(2분)에 맞춰 재정의**하세요.

- `prm_netprps_amt` — 누적 순매수 (백만원)
- `prm_netprps_amt_irds` — 틱 증감분. 최근 N틱 부호 다수결로 방향 판정에 씁니다.

---

## ka90013 — 종목 일별

```java
Map.of("amt_qty_tp","1", "stk_cd", code, "date","20260811")
```

최신부터 역순으로 `prm_netprps_amt` 부호가 유지되는 일수를 세면 **연속 순매수일**이 나옵니다.

⚠️ 첫 행이 0이면(`sign == 0`) 연속 계산을 시작하지 마세요. 0을 양수로 취급하면 연속일이 잘못 늘어납니다.

> LIST 키가 `stk_daly_prm_trde_trnsn` 인데, 다른 경로에서 `prg_trde_stk_dt` 로 관측된 코드도 있습니다. 응답을 실제로 찍어 보고 확정하세요.

---

## ka90003 — 시장 프로그램 순매수 TOP50

```java
Map.of("trde_upper_tp","2",    // ★ 2 = 순매수 상위 (반대로 넣으면 순매도가 온다)
       "amt_qty_tp","1",
       "mrkt_tp", "P00101",     // KOSPI. KOSDAQ은 P10102
       "stex_tp","1")
```

**KOSPI + KOSDAQ 각각 호출 후 병합**해야 전체가 나옵니다. `mrkt_tp="000"`으로 한 번에 안 됩니다.

응답: `stk_cd`, `rank` (1~50). 50위 밖은 아예 안 옵니다 — Map에 없는 걸 "0위"가 아니라 "순위권 밖"으로 다루세요.

---

## ka90004 — 종목별 프로그램매매 현황

```java
Map.of("dt","20260811", "mrkt_tp","P00101", "stex_tp","1")
```

전 종목 일별 스냅샷. 역시 **KOSPI + KOSDAQ 2회**.

### ⚠️ 장 마감 전 당일 조회 시 빈 문자열 행이 온다

`dt`=오늘로 조회하면 장중에는 **모든 필드가 빈 문자열인 행**이 정상 개수만큼 옵니다. `isEmpty()` 체크로는 안 잡힙니다.

```java
if (rows.get(0).path("stk_cd").asText("").isBlank()) {
    log.info("데이터 부재 — 장 마감 전 당일 호출 추정");
    return Map.of();
}
```

값 필드는 `netprps_prica` (백만원).

---

## 호출 설계 — 종목별 vs 시장 단위

초기에 ka90004를 **종목당 1회씩 100회** 호출했는데, 전부 실패했습니다. 이 TR은 **시장 단위 LIST API** 입니다.

시장 단위 2회로 바꾸니 100회 → 2회 (약 60초 절감). 종목별 루프를 짜기 전에 **그 TR이 리스트를 주는지 먼저 확인**하세요.

| TR | 호출 단위 |
|---|---|
| ka90008, ka90013 | **종목별** — 종목 수만큼 호출 |
| ka90003, ka90004 | **시장 단위** — 2회 (KOSPI+KOSDAQ)로 전 종목 커버 |

---

## 실제 응답 예시

### ka90003 — 프로그램 순매수 TOP50 (KOSPI, 2026-05-07 15:50)

```jsonc
{
  "prm_netprps_upper_50": [          // ← 15행. "50"인데 50개가 안 온다
    {
      "rank": "1",
      "stk_cd": "034020", "stk_nm": "두산에너빌리티",
      "cur_prc": "+136400", "pred_pre": "+9400", "flu_rt": "+7.40",
      "acc_trde_qty": "7804463",
      "prm_sell_amt":    "175815",   // 프로그램 매도 (백만원)
      "prm_buy_amt":     "313595",   // 프로그램 매수
      "prm_netprps_amt": "+137780"   // 순매수 → ×1e6 = 1,378억
    }
  ],
  "return_code": 0
}
```

⚠️ **키 이름은 `_upper_50`인데 실제로는 50개 미만이 옵니다** (이 응답은 15행). 조건을 만족하는 종목만 오는 것으로 보입니다. 50개를 전제로 인덱싱하지 마세요.

### ka90004 — 종목별 프로그램매매 (장 마감 전 당일 조회)

```jsonc
{
  "tot_1": "", "tot_2": "", "tot_3": "", "tot_4": "", "tot_5": "", "tot_6": "",
  "stk_prm_trde_prst": [             // ← 길이 1. 비어 있지 않다
    {
      "stk_cd": "", "stk_nm": "", "cur_prc": "", "flu_sig": "", "pred_pre": "",
      "buy_cntr_qty": "", "buy_cntr_amt": "",
      "sel_cntr_qty": "", "sel_cntr_amt": "",
      "netprps_prica": "", "all_trde_rt": ""
    }
  ],
  "return_code": 0,                  // ← 성공
  "return_msg": "정상적으로 처리되었습니다"
}
```

**모든 필드가 빈 문자열**입니다. `return_code=0`이고 배열도 비어 있지 않으니, 첫 행 `stk_cd`가 공란인지 봐야만 잡힙니다.

장 마감 후(또는 전일 `dt`)로 조회하면 정상 데이터가 옵니다.
