# 키움 REST API 함정 전체 목록

실운영에서 실제로 깨져 가며 알아낸 것들입니다. 각 항목의 **실측 날짜**는 그 동작을 라이브 응답으로 확인한 시점입니다.

> 공식 스펙(요청/응답 Element 전체 명세)은 여기 없습니다 → [키움증권 오픈API 공식 문서](https://openapi.kiwoom.com)

---

## 1. 숫자 파싱

### 1-1. 음수는 dash 2개 (`--`)

| 원본 | 의미 |
|---|---|
| `"--369770"` | -369,770 |
| `"+424822"` | +424,822 |
| `"0"` | 0 |
| `""` | 값 없음 (0으로 볼지 null로 볼지는 필드마다 다름) |

`Long.parseLong("--369770")` → `NumberFormatException`.

**부호를 살려야 하는 필드**: `netprps_qty`, `netprps_amt`, `netslmt`, `evltv_prft`, `tdy_rlzt_pl` 등 수급·손익 계열
**부호를 버려도 되는 필드**: `cur_prc`, `open_pric`, `high_pric`, `trde_qty` 등 가격·거래량 (항상 양수)

두 파서를 분리해 두는 걸 권합니다 → [`core/KiwoomNt2NumberParser.java`](../core/KiwoomNt2NumberParser.java)

### 1-2. 빈 문자열을 0으로 만들면 정보가 사라진다

`rank_chg`(ka00198)는 **변동 없음(0)** 과 **값 없음** 이 의미가 다릅니다. 범용 파서가 공백을 0으로 뭉개면 구분이 불가능해집니다. 계측용 컬럼이라면 `Integer` null로 받으세요.

관련해서 **`rank_chg_sign` 필드는 쓰지 마세요** — 변동이 없을 때 빈 문자열이라 부호원으로 못 씁니다. 부호는 `rank_chg` 값 문자열 자체에 들어 있습니다(`"+1"`, `"-3"`).

### 1-3. 단위가 필드마다 다르다

| 필드 | 단위 | 환산 |
|---|---|---|
| `trde_amt` (ka10030) | **백만원** | ×1,000,000 → 원 |
| `trde_prica` (ka10081) | **백만원** | ×1,000,000 → 원 |
| `prm_netprps_amt` (ka90008/13) | **백만원** | ×1,000,000 → 원 |
| `frgnr_netprps` (ka10051, `amt_qty_tp=0`) | **억원** | ×100,000,000 → 원 |
| `mac` (ka10001 시가총액) | **억원** | ×100,000,000 → 원 |
| `dstr_stk` (ka10001 유통주식수) | **천주** | ×1,000 → 주 |
| 가격 전반 | 원 | — |

> 실측 검증법: 삼성전자 `trde_amt` = 11,526,863 → 백만원 단위면 11.5조원. 맞습니다.

---

## 2. 거래소 (KRX / NXT) — 가장 헷갈리는 부분

### 2-1. 선택 메커니즘이 두 가지다

| 유형 | 방법 | 값 |
|---|---|---|
| **랭킹·시장 단위 TR** | `stex_tp` 파라미터 | `1`=KRX / `2`=NXT / `3`=통합(SOR) |
| **단일 종목 TR** | **`stk_cd` 접미사** | plain=KRX / `_NX`=NXT / `_AL`=통합 |

- `stex_tp` 방식: ka10030, ka10032, ka10034, ka10044, ka10016, ka10054, ka10063, ka10051, ka90001, ka90002
- 접미사 방식: **ka10001, ka10004, ka10046, ka10080, ka10081, ka10082, ka10083**

접미사 방식 TR에 `stex_tp`를 넣어도 **에러 없이 무시**됩니다. 그래서 잘못됐다는 걸 알아채기 어렵습니다.

### 2-2. 계좌·주문은 또 다른 파라미터를 쓴다

`dmst_stex_tp` — 값이 숫자가 아니라 **문자열** `"KRX"` / `"NXT"` / `"SOR"` 입니다. `kt00018`, `kt00004`, `kt10000`, `kt10003`, `kt00007` 등.

`kt00007`(체결내역) 과거 조회 시에는 `dmst_stex_tp="%"` (와일드카드)를 씁니다.

### 2-3. 시간대별 분기

```
08:00~09:00  프리마켓   → NXT
09:00~15:30  정규장     → KRX
15:30~20:00  애프터마켓 → NXT
```

경계는 `stex_tp`("1"/"2")와 `dmst_stex_tp`("KRX"/"NXT")가 **동일**하므로 판정 함수 하나를 SSoT로 두세요 → [`core/StexTpResolver.java`](../core/StexTpResolver.java)

⚠️ `LocalTime.now()`는 JVM 기본 타임존을 탑니다. **`ZoneId.of("Asia/Seoul")` 을 명시**하세요.

### 2-4. 응답의 종목코드에도 접미사가 붙는다

`stex_tp=3`으로 요청 → 응답 `stk_cd`가 `"005930_AL"`. NXT 시간대 호출 → `"005930_NX"`.

6자리 코드를 키로 쓰는 Map lookup이 **전부 조용히 실패**합니다. 예외도 안 납니다. 그냥 결과가 비어 있습니다.

```java
if (raw.endsWith("_AL") || raw.endsWith("_NX")) raw = raw.substring(0, raw.length() - 3);
```

**계좌 TR은 접미사가 아니라 접두사** — `kt00018` 보유목록의 `stk_cd`는 `"A005930"` 형태입니다. 앞의 `A`를 떼야 합니다.

### 2-5. NXT는 시장가를 지원하지 않는다

저녁 NXT 구간에서 `trde_tp=3`(시장가) → **`407022` 거부**. 지정가(`trde_tp=0`)만 됩니다.

### 2-6. 모의투자는 NXT를 지원하지 않는다

`mockapi.kiwoom.com`은 **KRX만** 커버합니다. NXT 동작 검증은 운영계에서만 가능합니다.

### 2-7. 통합(`_AL`)으로 바꾸면 호가 깊이가 달라진다

`ka10004`를 KRX → `_AL`로 전환하면 호가 잔량이 늘어납니다. 잔량 기반 임계값을 쓰고 있다면 **재캘리브레이션이 선결**입니다. 소스만 바꾸고 임계값을 그대로 두면 판정이 통째로 밀립니다.

### 2-8. `ka10051`의 업종코드에도 접미사가 붙는다 (미문서화)

| `stex_tp` | `inds_cd` |
|---|---|
| 1 (KRX) | `001` |
| 2 (NXT) | `001_NX` |
| 3 (통합) | `001_AL` |

종합 row를 `equals("001")`로 찾으면 통합 호출에서 못 찾습니다. **prefix 매칭**(`equals` 또는 `startsWith("001_")`)으로 처리하세요. — 실측 2026-05-28

---

## 3. 응답이 "정상"인데 비어 있는 경우

### 3-1. `return_code=0` + 빈 배열

`ka10063`(장중 투자자별)이 장중에도 빈 배열을 주는 경우가 있습니다. 에러가 아니라 데이터가 아직 없는 것입니다.

대응: 장중 한정 3회 재시도, 300ms × attempt 백오프. 소진되면 **0이 아니라 null(미수집)** 로 전파해야 "순매수 0"과 구분됩니다.

### 3-2. `ka90004` — 빈 문자열 행을 정상 개수만큼 반환

당일(`dt`=오늘) 조회 시 **장 마감 전**에는 모든 필드가 빈 문자열인 행이 옵니다. 배열 길이는 정상이라 `isEmpty()` 체크로는 못 잡습니다.

```java
if (rows.get(0).path("stk_cd").asText("").isBlank()) return Map.of();  // 데이터 미제공
```

### 3-3. `ka10014` — 거래일이 아니라 "공매도가 발생한 날"만 담긴다

`shrts_trnsn` 배열은 거래일 연속이 아닙니다. 실측(313건, 2026-07-20~24):

- `rows[0]`이 **당일이 아닐 수 있다** (T-1이 다수, 최대 5일 stale)
- `rows[0]` ↔ `rows[1]`이 **인접 거래일이 아닐 수 있다** (16%가 4일 간격)

"전일 대비 추세"를 계산하려면 **두 행의 날짜 간격을 먼저 확인**해야 합니다. 아니면 4일 전과 비교한 값을 "전일 대비"라고 프롬프트/화면에 흘리게 됩니다.

### 3-4. `ka00198`은 과거 조회가 불가능하다

TOP20 고정, `cont-yn: N`(연속조회 불가), **과거 시점 조회 불가**. 수집 시점에 안 받아둔 값은 영구 복구 불가입니다. 쓸지 안 쓸지 모르는 필드도 일단 원본 그대로 적재해 두세요.

### 3-5. `ka90008`은 1회 호출당 ~20틱만 준다

6~7초 간격 × 20 ≈ 2분치입니다. "당일 30분 차분" 같은 걸 1회 호출로 계산할 수 없습니다. 연속조회를 구현하거나, 계산 의미를 실제 창(직전 ~2분)에 맞춰 재정의해야 합니다.

---

## 4. 필수 파라미터 (문서보다 많음)

에러 메시지가 **한 번에 하나씩만** 알려줘서, 채울 때마다 새 필수 파라미터가 나오는 캐스케이드가 생깁니다.

| TR | 실제 필요한 파라미터 |
|---|---|
| **ka10054** (VI) | `mrkt_tp`, `bf_mkrt_tp`, `motn_tp`, `motn_drc`, `skip_stk`, `trde_qty_tp`, `min_trde_qty`, `max_trde_qty`, `trde_prica_tp`, `min_trde_prica`, `max_trde_prica`, `stex_tp` — **12개** |
| **ka10059** | `stk_cd`, `qry_tp`, `dt`, `amt_qty_tp`, `trde_tp`, `unit_tp` |
| **ka10063** | `mrkt_tp`, `amt_qty_tp`, `invsr`, `frgn_all`, `smtm_netprps_tp`, `stex_tp` |
| **ka10014** | `stk_cd`, `tm_tp`, `strt_dt`, `end_dt` |
| **ka10080/81/82/83** | `upd_stkpc_tp="1"` (수정주가) — 문서의 `updn_tp`는 **미지원** |
| **ka90003** | `trde_upper_tp`, `amt_qty_tp`, `mrkt_tp`, `stex_tp` |
| **ka90008/90013** | `amt_qty_tp`, `stk_cd`, `date` — 3개만 |

### `ka10063`의 `frgn_all` 함정

`frgn_all="1"`로 두면 **KOSPI와 KOSDAQ 응답이 동일하게** 옵니다. 두 시장을 합산하면 수급이 **2배**로 잡힙니다. `frgn_all="0"`(전체 외국인)을 쓰세요.

### `mrkt_tp="000"`을 믿지 마세요

"전체"인데 KOSPI만 포함되는 케이스가 있습니다. KOSPI(`P00101`) + KOSDAQ(`P10102`)를 **각각 호출해 병합**하는 게 안전합니다. ka10063 / ka90003 / ka90004가 이 패턴입니다.

---

## 5. 응답 키

### 5-1. `ka10100`은 응답 **전체**가 camelCase

거의 모든 TR이 snake_case인데 `ka10100`만 다릅니다. `nxtEnable` 하나가 아니라 `listCount`, `auditInfo`, `regDay`, `lastPrice`, `marketCode`, `upSizeName`, `orderWarning` … **전부** camelCase입니다. `return_code` / `return_msg`만 공통 필드라 snake_case로 남습니다.

```jsonc
{ "code":"005930", "name":"삼성전자", "listCount":"0000005846278608",
  "regDay":"19750611", "lastPrice":"00270000", "nxtEnable":"Y",
  "return_code":0 }
```

— 실측 2026-07-25 (흥구석유 024060=`"N"`, 삼성전자·현대힘스=`"Y"`)

이 TR은 **좌측 0 패딩**도 씁니다(`"0000005846278608"`, `"00270000"`). 숫자로 파싱하면 값은 맞지만, 문자열 비교나 코드 매칭에 그대로 쓰면 깨집니다.

### 5-2. LIST 키 이름이 TR마다 제각각

| TR | LIST 키 |
|---|---|
| ka10030 | `tdy_trde_qty_upper` |
| ka10032 | `trde_prica_upper` |
| ka10034 | `for_dt_trde_upper` |
| ka10044 | `daly_orgn_trde_stk` |
| ka10016 | `ntl_pric` |
| ka10046 | `cntr_str_tm` |
| ka10059 | `stk_invsr_orgn` |
| ka10063 / ka10065 | `opmr_invsr_trde` / `opmr_invsr_trde_upper` |
| ka10014 | `shrts_trnsn` |
| ka10051 | `inds_netprps` |
| ka10080 | `stk_min_pole_chart_qry` (또는 `stk_mnt_pole_chart_qry`) |
| ka10081 | `stk_dt_pole_chart_qry` |
| ka00198 | `item_inq_rank` |
| ka90001 / ka90002 | `thema_grp` / `thema_comp_stk` |
| ka90003 | `prm_netprps_upper_50` |
| ka90004 | `stk_prm_trde_prst` |
| ka90008 / ka90013 | `stk_tm_prm_trde_trnsn` / `stk_daly_prm_trde_trnsn` |
| kt00018 | `acnt_evlt_remn_indv_tot` |
| kt00007 | `acnt_ord_cntr_prps_dtl` |
| ka10075 | `oso` |
| ka10076 | `cntr` |

### 5-3. `ka10080`은 LIST 키가 두 가지로 관측된다

`stk_min_pole_chart_qry` / `stk_mnt_pole_chart_qry` — 둘 다 대비해 fallback을 두세요.

### 5-4. `ka10004`(호가)는 LIST가 없다

root 직속 60여 개 필드입니다. 1차 호가와 2~5차 호가의 **키 패턴이 다릅니다**:

```
매수 1차 : buy_fpr_bid      / buy_fpr_req
매수 2~5차: buy_2th_pre_bid  / buy_2th_pre_req  ... buy_5th_pre_bid
매도 1차 : sel_fpr_bid      / sel_fpr_req
매도 2~5차: sel_2th_pre_bid  / sel_2th_pre_req  ...
```

총잔량은 `tot_buy_req` / `tot_sel_req`인데, **저녁 애프터마켓에는 0**이 옵니다. 그때는 `ovt_buy_req` / `ovt_sel_req`로 폴백하세요. — 실측 2026-07-23

---

## 6. 인증 · 토큰

### 6-1. 점검 중에는 HTML이 온다

`/oauth2/token`이 점검 안내 **HTML 페이지**를 반환합니다. `readTree` 하면 `JsonParseException`.

```java
String t = body.trim();
boolean maintenance = !t.isEmpty() && t.charAt(0) == '<';
```

감지되면 **재시도를 멈추고 백오프**(저희는 5분)를 거세요. 안 그러면 점검 끝날 때까지 계속 두드립니다.
점검 안내는 `http://api.kiwoom.com/start.html`에서 중단 일시·사유를 파싱할 수 있습니다.

> false positive로 502 Bad Gateway 같은 HTML 에러 페이지도 점검으로 잡히지만, 백오프 후 자동 재시도되므로 실무 영향은 없었습니다.

### 6-2. 토큰 만료 형식

`expires_dt` = `"20261231235959"` (`yyyyMMddHHmmss`). 만료 몇 분 전에 미리 갱신하세요(저희는 5분).

### 6-3. 토큰 무효 코드가 두 경로로 온다

- `return_code`가 직접 **8005 / 8010**
- `return_code=3` + `return_msg` **안에** 8005/8010 문자열

메시지까지 봐야 합니다.

```java
boolean invalid = rc == 8005 || rc == 8010 || msg.contains("8005") || msg.contains("8010");
```

### 6-4. 토큰 발급 실패 로그에 응답 body를 찍지 마세요

**응답에 appkey가 에코백될 수 있습니다.** HTTP status만 남기세요.

---

## 7. Rate Limit · 동시성

- 문서에 명시 없음 → **COM API 기준 초당 5회** 가정, 글로벌 200ms 인터벌
- rate limit 코드: **1687 / 1700** → 지수 백오프(200/500/1000ms)
- 동시 in-flight는 별도 `Semaphore`로 제한 (인터벌과 다른 축)

### Virtual Thread 사용 시

`synchronized` 블록 안에서 blocking I/O를 하면 **carrier thread pinning**이 발생합니다. `ReentrantLock`을 쓰세요. Rate Limiter와 토큰 갱신 락 둘 다 해당됩니다.

### `.block()` 백스톱 타임아웃

WiFi 전환 등으로 네트워크 경로가 바뀌면 소켓이 `TCP_ESTABLISHED`로 남아 `ReadTimeoutHandler`가 발화하지 않는 경우가 있습니다. Netty 핸들러(10s)보다 **2초 여유를 둔 `.block(Duration)`** 백스톱을 겁니다.

---

## 8. 주문 (가장 위험한 영역)

### 8-1. 절대 재시도하지 마세요

`kt10000`(매수) / `kt10001`(매도) / `kt10003`(취소)는 **비멱등**입니다. 타임아웃이 났다고 재발주하면 이중 매수가 납니다.

타임아웃 시 올바른 순서:
1. `ka10075`(미체결 조회)로 **이미 접수됐는지 확인**
2. 없을 때만 재발주

### 8-2. 정정(`kt10002`)은 내부적으로 "취소 + 재발주"다

원 `ord_no`는 **무효화**됩니다. 응답으로 온 새 `ord_no`로 다시 조회해야 합니다.

### 8-3. `kt00007` 조회 시 방향을 지정하세요

`sell_tp=0`(전체)은 매수·매도가 **섞여서** 옵니다. 평균 체결가를 내면 값이 오염됩니다. 방향별 집계가 필요하면 `sell_tp`를 명시하거나 `io_tp_nm`으로 필터링하세요.

### 8-4. 당일 세션 한정 TR과 과거 조회 가능 TR이 나뉜다

| TR | 범위 |
|---|---|
| `ka10076`, `ka10077` | **당일 세션만** |
| `kt00007` (`ord_dt` 지정) | **과거 일자 조회 가능** — 실측 2026-07-15 |

### 8-5. `kt00004.tdy_lspft`는 당일 매도 실현손익을 반영 못 한다

당일 매도가 있으면 **`ka10076`(체결목록) → 종목별 `ka10077`(실현손익) 합산**으로 재계산해야 정확합니다. — 실측 2026-07-31

### 8-6. 킬스위치와 dry-run을 발주 경로 진입부에 강제하세요

매수·매도·취소가 **같은 게이트**를 통과하도록 설계해야 합니다. 경로별로 따로 만들면 하나를 빠뜨립니다. → [`order/KiwoomOrderClient.java`](../order/KiwoomOrderClient.java)

---

## 9. 데이터 정렬

### 9-1. 대부분 최신 → 과거 순 (내림차순)

`rows[0]`이 최신입니다. 하지만 **전제로 두고 방치하면 위험합니다** — 뒤집혀 오면 계산이 조용히 반대가 됩니다.

```java
// 첫 row가 마지막 row보다 과거면 reverse
if (candles.getFirst().date().isBefore(candles.getLast().date())) { ... }
```

`ka10014`는 응답 역순이 되면 전 종목이 조용히 "STABLE"로 붕괴하는 식으로 나타납니다. 정렬 가정이 깨지면 **경고 로그를 남기세요**.

### 9-2. `limit(5)`로 최근 5일을 뽑으면 반대가 나온다

응답을 `reverse` 해서 오래된→최신 순으로 만든 뒤 `limit(5)`를 하면 **가장 오래된 5일**이 나옵니다. `subList(size-5, size)`를 쓰세요.

---

## 9-3. 빈 문자열이 오는 자리를 숫자 파서에 넣지 마세요

조건이 안 맞으면 `0`이 아니라 **빈 문자열**이 옵니다. 실측 사례:

| TR | 필드 | 언제 |
|---|---|---|
| ka10030 | `opmr_trde_qty`, `af_mkrt_trde_qty`, `bf_mkrt_trde_qty` | `mrkt_open_tp` 조건 밖 |
| ka90004 | 전 필드 | 장 마감 전 당일 조회 |
| ka00198 | `rank_chg_sign` | 순위 변동 없을 때 (항상) |
| ka10001 | `mac_wght` | 상시 |

`Long.parseLong("")` 는 예외입니다. 파서 진입부에서 `isBlank()` 가드를 거세요.

## 9-4. 같은 개념인데 TR마다 형식이 다르다

실측으로 확인된 비대칭:

| 개념 | TR A | TR B |
|---|---|---|
| 종가 부호 | ka10080 분봉 `"+258750"` | ka10081 일봉 `"78600"` (부호 없음) |
| 순매수 | ka10063 `netprps_qty`/`netprps_amt` | ka10065 **`netslmt`** |
| 투자자 코드 | ka10063 `invsr` = `6`/`7` | ka10065 `orgn_tp` = `9000`/`9999` |
| 기간 지정 | ka10034 `dt` = `1~4` (코드) | ka10044 `strt_dt`/`end_dt` (날짜) |
| 값 단위 | ka10034 수량(주) | ka10044 금액 |
| 시장 지정 | ka10063 `P00101`/`P10102` | ka10044 `001`/`101` |

같은 파서·같은 시장 상수를 재사용하기 전에 **TR별로 확인**하세요.

## 9-5. 키 이름의 숫자를 믿지 마세요

`ka90003`의 LIST 키는 `prm_netprps_upper_50` 인데 **실제로는 50개 미만**이 옵니다 (실측 15행). 조건을 만족하는 종목만 반환하는 것으로 보입니다.

50개 고정을 전제로 인덱싱하거나 "50개가 아니면 실패"로 판정하지 마세요.

---

## 10. 그 외

### `ka10054`(VI)는 종목 조회 TR이 아니다

**시장 조건**으로 리스트를 받아 클라이언트에서 종목을 필터링하는 구조입니다. `stk_cd` 파라미터가 없습니다.

### 재무 전용 TR이 없다

분기·연간 재무제표 TR은 존재하지 않습니다. `ka10001`의 PER/ROE/EPS도 외부 벤더 데이터가 **주 1회** 갱신됩니다. 재무 기반 로직이 필요하면 **DART Open API**를 보세요.

### 원시 응답을 다 적재하면 금방 GB 단위가 된다

30초 폴링하는 TR(`ka10004`, `ka10046`)의 원시 응답을 전부 DB에 넣었더니 **1주일 보존분이 1.8GB**였습니다. 고volume TR은 적재 대상에서 빼고, 실패 응답만 남기는 게 낫습니다.

⚠️ SQLite는 `auto_vacuum=0`이면 **삭제해도 파일이 안 줄어듭니다**. freelist 재사용만 됩니다.
