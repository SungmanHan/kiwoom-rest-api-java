package com.nexttheme.stock.nt2.aux;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexttheme.stock.nt2.KiwoomNt2Client;
import com.nexttheme.stock.nt2.Nt2KiwoomException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * NT2 ka10044 기관 기간별 매매 조회 — KiwoomForeignPeriodProvider 대칭.
 * 4기간(5일/10일/20일/60일) × 2시장(KOSPI+KOSDAQ) = 8 calls/slot.
 * baseDate는 오늘(슬롯 일자), end_dt는 전일(baseDate-1) 자동 적용.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KiwoomInstitutionPeriodProvider {

  private final KiwoomNt2Client client;

  private static final List<Integer> PERIOD_DAYS = List.of(5, 10, 20, 60);
  private static final List<String> MARKETS = List.of("001", "101");

  /** 4기간 기관 누적 순매수금액 (ka10044 netprps_amt 기준). */
  public record InstitutionNetMap(long d1, long d2, long d3, long d4) {}

  /**
   * 4기간 기관 누적 매매 일괄 조회.
   *
   * @param baseDate 슬롯 일자 (전일까지 집계 — end_dt = baseDate.minusDays(1))
   * @return 종목코드 → InstitutionNetMap — 미등장 기간은 0
   */
  public Map<String, InstitutionNetMap> fetchAllPeriods(LocalDate baseDate) {
    LocalDate endDate = baseDate.minusDays(1);
    Map<String, long[]> codeToArr = new HashMap<>();
    for (int i = 0; i < PERIOD_DAYS.size(); i++) {
      int days = PERIOD_DAYS.get(i);
      LocalDate startDate = endDate.minusDays(days - 1);
      Map<String, Long> onePeriod = fetchOnePeriod(startDate, endDate);
      final int idx = i;
      onePeriod.forEach((code, amt) -> {
        long[] arr = codeToArr.computeIfAbsent(code, k -> new long[4]);
        arr[idx] = amt;
      });
    }
    Map<String, InstitutionNetMap> result = new HashMap<>();
    codeToArr.forEach((code, arr) ->
        result.put(code, new InstitutionNetMap(arr[0], arr[1], arr[2], arr[3])));
    return result;
  }

  private Map<String, Long> fetchOnePeriod(LocalDate startDate, LocalDate endDate) {
    String strtDt = startDate.format(DateTimeFormatter.BASIC_ISO_DATE);
    String endDt  = endDate.format(DateTimeFormatter.BASIC_ISO_DATE);
    Map<String, Long> combined = new HashMap<>();
    for (String market : MARKETS) {
      combined.putAll(fetchOneMarket(strtDt, endDt, market));
    }
    return combined;
  }

  private Map<String, Long> fetchOneMarket(String strtDt, String endDt, String market) {
    Map<String, Object> body = Map.of(
        "strt_dt", strtDt, "end_dt", endDt,
        "trde_tp", "2",      // 2=순매수 (ka10034 KiwoomForeignPeriodProvider 동일)
        "mrkt_tp", market,
        "stex_tp", "3");
    JsonNode resp;
    try {
      resp = client.callTr("ka10044", body);
    } catch (Nt2KiwoomException e) {
      log.warn("[NT2 aux] ka10044 {}/{} mrkt={} 실패", strtDt, endDt, market, e);
      return Map.of();
    }
    if (resp.path("return_code").asInt(-1) != 0) return Map.of();
    Map<String, Long> map = new HashMap<>();
    for (JsonNode row : resp.path("daly_orgn_trde_stk")) {
      long amt = KiwoomNt2NumberParser.parseSignedLong(row.path("netprps_amt").asText());
      if (amt <= 0) continue;
      String raw = row.path("stk_cd").asText();
      if (raw.isBlank()) continue;
      map.put(stripSuffix(raw), amt);
    }
    return map;
  }

  private static String stripSuffix(String raw) {
    if (raw.endsWith("_AL") || raw.endsWith("_NX")) {
      return raw.substring(0, raw.length() - 3);
    }
    return raw;
  }
}
