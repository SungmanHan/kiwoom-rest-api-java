# chart — 분봉 · 일봉 · 주봉 · 월봉

전부 `/api/dostk/chart`. **전부 `stk_cd` 접미사 방식** (`stex_tp` 없음).

| TR | 봉 | LIST 키 |
|---|---|---|
| **ka10080** | 분봉 (`tic_scope`=1/5/15/…) | `stk_min_pole_chart_qry` 또는 `stk_mnt_pole_chart_qry` |
| **ka10081** | 일봉 | `stk_dt_pole_chart_qry` |
| **ka10082** | 주봉 | (동일 패턴) |
| **ka10083** | 월봉 | (동일 패턴) |

| 파일 | 대상 |
|---|---|
| [`Nt2MinuteBarProvider.java`](Nt2MinuteBarProvider.java) | ka10080 — 종목당 분당 1회 캐시 |
| [`Ka10080_MinuteChart_Parallel.java`](Ka10080_MinuteChart_Parallel.java) | ka10080 — Virtual Thread 병렬 수집 |
| [`KiwoomNt2DailyChartProvider.java`](KiwoomNt2DailyChartProvider.java) | ka10081 — 정렬 안전장치 포함 |
| [`KiwoomNt2WeeklyChartProvider.java`](KiwoomNt2WeeklyChartProvider.java) | ka10082 |
| [`KiwoomNt2MonthlyChartProvider.java`](KiwoomNt2MonthlyChartProvider.java) | ka10083 |
| [`Ka10081_82_83_RequestParams.java`](Ka10081_82_83_RequestParams.java) | 일·주·월봉 요청 파라미터 |

---

## 요청

```java
// 분봉
Map.of("stk_cd", code, "tic_scope", "1", "upd_stkpc_tp", "1")

// 일 / 주 / 월봉
Map.of("stk_cd", code, "base_dt", "20260811", "upd_stkpc_tp", "1")
```

### `upd_stkpc_tp="1"` (수정주가) — 필수

문서에 나오는 **`updn_tp`는 미지원**입니다. 이걸 보내면 원하는 결과가 안 옵니다.

수정주가를 안 쓰면 액면분할·유상증자 구간에서 캔들이 통째로 튑니다. 이동평균·박스권 계산이 전부 깨집니다.

---

## 응답 필드

| 필드 | 의미 | 주의 |
|---|---|---|
| `dt` | 일자 (YYYYMMDD) | 일·주·월봉 |
| `cntr_tm` | 체결시각 (yyyyMMddHHmmss) | 분봉 — **14자리** |
| `cur_prc` | 종가 | 부호 접두 있음 → strip |
| `open_pric` / `high_pric` / `low_pric` | 시/고/저 | 부호 접두 있음 |
| `trde_qty` | 거래량 | |
| `trde_prica` | 거래대금 | **백만원** → ×1,000,000 |

가격은 항상 양수라 부호를 통째로 버리면 됩니다. `Math.abs()` 또는 접두 strip.

---

## 정렬 — 최신이 `rows[0]` (내림차순)

**전제로 두고 방치하면 위험합니다.** 뒤집혀 오면 이동평균이 조용히 반대로 계산됩니다. 예외도 안 납니다.

```java
if (candles.getFirst().date().isBefore(candles.getLast().date())) {
    log.warn("정렬 오름차순 감지 — reverse 적용");
    return new ArrayList<>(candles.reversed());
}
```

가정이 깨졌을 때 **로그를 남기는 게 핵심**입니다. 조용히 고치면 다음에 원인을 못 찾습니다.

### `limit(N)` 방향 실수

`reverse()`로 오래된→최신 순으로 만든 뒤 `limit(5)` 하면 **가장 오래된 5일**이 나옵니다. `subList(size-5, size)`를 쓰세요.

---

## 한 번에 몇 개나 오는가

- **ka10081(일봉)** — 1회 호출로 **600행** (실측 2026-07-26). 250일치가 필요하면 연속조회가 필요 없습니다.
- **ka10080(분봉)** — `tic_scope`에 따라 다름. 당일치를 다 받으려면 연속조회를 봐야 합니다.

연속조회는 헤더 `cont-yn: Y` + `next-key` 패스스루입니다.

---

## `_AL`을 붙이면 애프터마켓 봉이 섞인다

분봉을 `_AL`(통합)로 받으면 NXT 애프터마켓 체결이 **정규장 봉 뒤에 붙어서** 옵니다.

정규장 기준 지표(VWAP, 종가 등)를 계산한다면 15:30 이후 봉을 잘라내야 합니다. 특히 **장 마감 후 재실행 시** 같은 코드가 다른 결과를 내는 원인이 됩니다.

```java
bars.stream().filter(b -> !b.time().isAfter(day.atTime(LocalTime.of(15, 30)))).toList();
```

> 이건 "세션 정의"가 아니라 **재실행 오염 방지 가드**입니다. 세션 필터를 원시 조회 계층에 박아 넣으면 프리마켓 용도로 재사용할 때 막힙니다. 조회는 무필터로, 필터는 계산 시점에.

---

## 캐시

분봉을 여러 소비자가 부르면 종목당 호출이 중복됩니다. **종목당 분당 1회** 캐시로 충분히 줄어듭니다.

```java
private record Cache(long epochMinute, List<Bar> bars) {}
private final Map<String, Cache> cache = new ConcurrentHashMap<>();
```

⚠️ 키가 종목코드뿐이면 매 거래일 새 엔트리가 계속 쌓입니다. **하루 1회 `clear()`** 를 걸어두세요.

---

## 병렬 수집

종목 간 병렬, 종목 내 순차가 기본 패턴입니다. Virtual Thread + `Semaphore` 게이트:

```java
try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
    var futures = codes.stream()
        .map(code -> executor.submit(() -> withGate(gate, () -> collectOne(code, ticScope))))
        .toList();
    // future.get(timeout) — 타임아웃 시 cancel(true) 후 빈 리스트로 진행
}
```

동시성 상한은 **Semaphore**로, 호출 간격은 **글로벌 Rate Limiter**로 각각 거세요. 하나로 해결하려 하면 둘 중 하나가 새어 나갑니다.
