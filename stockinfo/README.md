# stockinfo — 종목정보 (VI · 거래원 · 공매도 · 투자자별 · NXT상장)

| TR | 용도 | 엔드포인트 | LIST 키 |
|---|---|---|---|
| **ka10001** | 종목 기본정보 | `/stkinfo` | (root 직속) |
| **ka10054** | VI 발동 종목 | `/stkinfo` | `vi_list` |
| **ka10059** | 종목별 투자자·기관별 매매 | `/stkinfo` | `stk_invsr_orgn` |
| **ka10040** | 당일 주요 거래원 | `/rkinfo` | `buy_array` / `sell_array` |
| **ka10014** | 공매도 추이 | `/shsa` | `shrts_trnsn` |
| **ka10100** | 종목정보 조회 (NXT 상장 여부) | `/stkinfo` | (root 직속) |

| 파일 | 대상 |
|---|---|
| [`KiwoomBasicInfoProvider.java`](KiwoomBasicInfoProvider.java) | ka10001 — 시총·52주·외인소진률 |
| [`Ka10054_ViHistory.java`](Ka10054_ViHistory.java) | ka10054 — 12개 파라미터 캐스케이드 |
| [`KiwoomSupplyHistoryProvider.java`](KiwoomSupplyHistoryProvider.java) | ka10059 — 수급 히스토리 + 연속일 |
| [`Ka10040_BrokerFlow.java`](Ka10040_BrokerFlow.java) | ka10040 — 거래원 TOP |
| [`KiwoomShortSellingProvider.java`](KiwoomShortSellingProvider.java) | ka10014 — 비인접 거래일 가드 (권장) |
| [`Ka10014_ShortSelling_NT1.java`](Ka10014_ShortSelling_NT1.java) | ka10014 — 구버전 (비교용) |
| [`Ka10100_NxtEnable.java`](Ka10100_NxtEnable.java) | ka10100 — camelCase 예외 |

---

## ka10054 — VI 발동

### ⚠️ 종목 조회 TR이 아닙니다

**`stk_cd` 파라미터가 없습니다.** 시장 조건으로 리스트를 받아 클라이언트에서 종목을 필터링하는 구조입니다.

그리고 **필수 파라미터가 12개**입니다. 하나 채우면 다음 걸 요구하는 캐스케이드로 늘어납니다.

```java
params.put("mrkt_tp",        "0");            // 전체 시장
params.put("bf_mkrt_tp",     "0");            // 정규+시간외
params.put("motn_tp",        "0");            // 정적+동적
params.put("motn_drc",       "0");            // 상승+하락
params.put("skip_stk",       "000000000");    // 제외 종목 없음
params.put("trde_qty_tp",    "0");            // 거래량 필터 미적용
params.put("min_trde_qty",   "0");
params.put("max_trde_qty",   "0");            // 0 = 제한 없음
params.put("trde_prica_tp",  "0");            // 거래대금 필터 미적용
params.put("min_trde_prica", "0");
params.put("max_trde_prica", "0");
params.put("stex_tp", resolveStexTp());
```

응답 `vi_list[]`: `stk_cd`, `viaplc_tp`(정적/동적/동시), `virelis_time`(해제시각), `vimotn_cnt`(발동횟수)

`vimotn_cnt > 0` 이고 `virelis_time`이 비어 있으면 **현재 발동 중**입니다.

---

## ka10059 — 종목별 투자자·기관별 매매

```java
Map.of("stk_cd", code, "qry_tp","1", "dt","20260811",
       "amt_qty_tp","1",     // 1=금액(원)
       "trde_tp","0",        // 0=순매수
       "unit_tp","1000")     // 1000=천주 단위
```

응답 `stk_invsr_orgn[]`: `dt`, `frgnr_invsr`(외인), `orgn`(기관), `ind_invsr`(개인) — **부호 보존 파싱 필수**

한 번 호출로 **~60일치**가 옵니다. 배열은 최신 → 과거 순.

### 연속 순매수일 계산

`reverse()`로 오래된→최신으로 바꾼 뒤 **끝에서부터** 역산하세요.

```java
for (int i = days.size() - 1; i >= 0; i--) {
    if (getter.apply(days.get(i)) > 0) consec++;
    else break;
}
```

⚠️ 최근 5일을 뽑을 때 `limit(5)`를 쓰면 **가장 오래된 5일**이 나옵니다. `subList(size-5, size)`.

---

## ka10014 — 공매도

```java
Map.of("stk_cd", code, "tm_tp","1", "strt_dt", start, "end_dt", end)
```

`tm_tp="1"`(기간별)은 **집계 반환이 아니라 기간 조회**입니다. 일자별 행이 그대로 옵니다.

- `trde_wght` — 공매도 거래 비중(%). 부호 접두 있음
- `ovr_shrts_qty` — 누적 공매도량. **기간 합산 단조증가**라 추세 판단에 못 씁니다

### ⚠️ 배열이 거래일 연속이 아니다

`shrts_trnsn`은 **공매도가 발생한 날만** 담습니다. 실측(313건, 2026-07-20~24):

- `rows[0]`이 **당일이 아닐 수 있다** — T-1이 다수, 최대 5일 stale
- `rows[0]` ↔ `rows[1]`이 **인접 거래일이 아닐 수 있다** — 16%가 4일 간격

"전일 대비 증감"을 그냥 계산하면, 실제로는 4일 전과 비교한 값에 "전일 대비" 라벨이 붙습니다.

**두 행의 날짜 간격을 먼저 확인**하고, 인접이 아니면 판정을 보류하세요.

```java
long days = ChronoUnit.DAYS.between(prev, latest);
if (days == 1) return true;                                        // 화~금
if (days == 3 && latest.getDayOfWeek() == MONDAY) return true;     // 금→월
return false;                                                       // 그 외 판정 보류
```

⚠️ 달력일 차이만 쓰면 **월요일이 전부 비인접(3일)으로 오판**됩니다. 요일 조건이 필수입니다.

### 역순 가드

`rows[0]`=최신 전제가 깨져 오름차순으로 오면 `days`가 음수가 되고, **전 종목이 조용히 "판정 보류"로 붕괴**합니다. 원인 추적이 불가능해지니 WARN을 남기세요.

### 조회 기간은 넉넉히

달력 7일로 잡으면 연휴에 행이 2개 미만이라 추세 판정 자체가 안 됩니다. **14일** 권장.

### 수집률은 슬롯별로 보세요

NULL이 많다고 파싱 실패로 단정하지 마세요. 대부분 **애초에 호출하지 않은 종목**입니다 (후보만 조회하는 구조면 당연). 시간대를 섞어 비교하면 정상 동작을 회귀로 오판합니다.

---

## ka10040 — 당일 주요 거래원

```java
Map.of("stk_cd", code)
```

`buy_array[]` / `sell_array[]`. `rows[0]`이 1위입니다.

- `mbr_kor_name` 또는 `mbr_nm` — 회원사명 (**둘 다 대비**)
- `buy_qty` — 매수 수량(주). 금액이 아닙니다

외국계 창구 판정은 이름 문자열 매칭이 현실적입니다:
`모건 / 골드만 / 씨티 / CS / UBS / 메릴 / JP / 도이치 / HSBC / BNP / 노무라 / 맥쿼리`

---

## ka10100 — NXT 상장 여부

```java
Map.of("stk_cd", code)
```

### ⚠️ 응답 키가 camelCase — `nxtEnable`

거의 모든 TR이 snake_case인데 **이 TR만 다릅니다.** 문서에 없고 실측으로 확인했습니다.

(2026-07-25 실측: 흥구석유 024060 = `"N"`, 삼성전자 005930 / 현대힘스 460930 = `"Y"`)

### 3-상태로 다루세요

```java
Boolean isNxtEnabled(String code) {
    JsonNode res = call("ka10100", Map.of("stk_cd", code));
    if (res == null) return null;                        // 조회 실패 = 미확인
    return "Y".equalsIgnoreCase(res.path("nxtEnable").asText(""));
}
```

`null`(미확인)과 `false`(미상장 확정)는 다릅니다. 저녁 NXT 발주 가능 여부를 판단한다면, 미확인을 미상장으로 뭉개면 멀쩡한 종목을 버리게 됩니다.

---

## ka10001 — 기본정보

상세는 [`quote/README.md`](../quote/README.md#ka10001--기본정보--현재가) 참고. 이 폴더에는 시총·52주·외인소진률만 쓰는 경량 버전이 있습니다.

단위 주의: `mac`(시총) = **억원**, `dstr_stk`(유통주식수) = **천주**.
