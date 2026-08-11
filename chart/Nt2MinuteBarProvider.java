package com.nexttheme.stock.nt2.chart;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexttheme.stock.collector.KiwoomApiClient;
import com.nexttheme.stock.nt2.dto.Nt2MinuteBar;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * NT2 1분봉(ka10080) 공용 원시 조회 + 종목당 분당 1회 캐시.
 *
 * <p>갭모니터({@code Nt2AiPerformanceService})·익절신호({@code Nt2HighSellSignalEvaluator})
 * 공용 — autobuy(candle1mCache)는 세션필터·모멘텀 슬라이싱 전제(§4.4-A 재검토)가 있어
 * 별도 유지, 이 provider 를 사용하지 않는다.
 *
 * <p>⚠️ <b>세션 시각 필터를 절대 넣지 않는다</b> — PRE_MARKET(08:00)·AFTER_MARKET(15:40) 양쪽에서
 * 재사용해야 하므로, 세션 필터는 오직 {@link Nt2SessionVwapCalculator#calculate}
 * 내부에서만 적용한다.
 *
 * <p>설계서: {@code plan/design-session-vwap-single-source-20260726.md} §4.4-A.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class Nt2MinuteBarProvider {

  private final KiwoomApiClient kiwoomApiClient;

  private record Cache(long epochMinute, List<Nt2MinuteBar> bars) {}

  private final Map<String, Cache> cache = new ConcurrentHashMap<>();

  /** 정규장 마감(15:30) 이후 봉 제거 — _AL 은 NXT 애프터마켓을 포함하므로 재실행 시 오염 방지 필수(설계서 §5.1.1). */
  private static final LocalTime REGULAR_SESSION_END = LocalTime.of(15, 30);

  /**
   * 정규장 마감(15:30) 이후 봉을 잘라낸다 — {@link #fetch}의 "세션 필터 없음" 원칙과 별개.
   * "세션 정의"가 아니라 "재실행 시 애프터마켓 오염 방지 가드"(설계서 §5.1.1).
   */
  public static List<Nt2MinuteBar> guardRegularSession(List<Nt2MinuteBar> bars, LocalDate day) {
    return bars.stream()
        .filter(b -> !b.time().isAfter(day.atTime(REGULAR_SESSION_END)))
        .toList();
  }

  /** ka10080 원시 조회 — 세션 필터 없음(§3 경고). 종목당 분당 1회, 실패 시 빈 리스트. */
  public List<Nt2MinuteBar> fetch(String itemCode) {
    long nowMinute = System.currentTimeMillis() / 60_000L;
    Cache c = cache.get(itemCode);
    if (c != null && c.epochMinute() == nowMinute) return c.bars();
    List<Nt2MinuteBar> fresh = fetchUncached(itemCode);
    cache.put(itemCode, new Cache(nowMinute, fresh));
    return fresh;
  }

  /**
   * 전일 캐시 엔트리 전량 정리(audit #M2, 2026-07-26) — 키가 종목코드뿐이라 매 거래일
   * 새 엔트리가 계속 쌓이고, 지난 분(epochMinute) 엔트리는 다시 읽히지 않는 죽은 데이터로
   * 남는다. PRE_MARKET(08:00) 세션 리셋에서 1일 1회 호출.
   */
  public void clear() {
    cache.clear();
  }

  /** ka10080 호출 + 파싱만 — 세션 시각 필터는 절대 걸지 않는다(무필터 원시봉). 예외 격리. */
  private List<Nt2MinuteBar> fetchUncached(String itemCode) {
    try {
      JsonNode res = kiwoomApiClient.call("ka10080",
          Map.of("stk_cd", itemCode + "_AL", "tic_scope", "1", "upd_stkpc_tp", "1"));
      if (res == null) return List.of();
      JsonNode rows = res.path("stk_min_pole_chart_qry");
      if (!rows.isArray()) rows = res.path("stk_mnt_pole_chart_qry");
      if (!rows.isArray() || rows.isEmpty()) return List.of();
      return parseBars(rows);
    } catch (RuntimeException e) {
      log.warn("[Nt2MinuteBarProvider] ka10080 조회 실패 — {} err={}", itemCode, e.getMessage());
      return List.of();
    }
  }

  private static List<Nt2MinuteBar> parseBars(JsonNode rows) {
    List<Nt2MinuteBar> bars = new ArrayList<>();
    for (JsonNode row : rows) {
      LocalDateTime tm = parseCntrTm(row.path("cntr_tm").asText(""));
      if (tm == null) continue;
      bars.add(new Nt2MinuteBar(tm,
          Math.abs(parseD(row, "cur_prc")),
          Math.abs(parseD(row, "trde_qty")),
          Math.abs(parseD(row, "high_pric")),
          Math.abs(parseD(row, "low_pric"))));
    }
    return bars;
  }

  /** cntr_tm 파싱 — yyyyMMddHHmmss. 파싱 실패 시 null(해당 봉 제외). */
  private static LocalDateTime parseCntrTm(String raw) {
    if (raw == null || raw.length() < 14) return null;
    try {
      return LocalDateTime.of(
          Integer.parseInt(raw.substring(0, 4)),
          Integer.parseInt(raw.substring(4, 6)),
          Integer.parseInt(raw.substring(6, 8)),
          Integer.parseInt(raw.substring(8, 10)),
          Integer.parseInt(raw.substring(10, 12)),
          Integer.parseInt(raw.substring(12, 14)));
    } catch (NumberFormatException | java.time.DateTimeException e) {
      return null;
    }
  }

  private static double parseD(JsonNode node, String key) {
    String v = node.path(key).asText("0").trim().replace(",", "");
    try {
      return Double.parseDouble(v.startsWith("-") ? v : v.replace("-", ""));
    } catch (NumberFormatException e) {
      return 0;
    }
  }
}
