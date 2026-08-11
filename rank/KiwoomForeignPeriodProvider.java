package com.nexttheme.stock.nt2.aux;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexttheme.stock.nt2.KiwoomNt2Client;
import com.nexttheme.stock.nt2.Nt2KiwoomException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * NT2 ka10034 외인 기간별 매매상위 조회 (PR #6). 설계서 §1.4.
 *
 * <p>4기간 (dt=1/2/3/4 → 5일/10일/20일/60일 누적) 각 1회 호출. 슬롯당 4회.
 * 종목별 4기간 누적 순매수량을 모아 라이프사이클 분류 입력으로 사용.
 *
 * <p>master TR_PATH_MAP 에 {@code ka10034 → /api/dostk/rkinfo} 등록됨.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KiwoomForeignPeriodProvider {

  private final KiwoomNt2Client client;

  private static final List<Integer> PERIODS = List.of(1, 2, 3, 4);

  /** 4기간 외인 누적 순매수량 (주). */
  public record ForeignNetMap(long d1, long d2, long d3, long d4) {}

  /**
   * 4기간 외인 누적 매매 일괄 조회.
   *
   * @return 종목코드 → ForeignNetMap — 한 기간만 등재된 종목은 나머지 0
   */
  public Map<String, ForeignNetMap> fetchAllPeriods() {
    Map<String, long[]> codeToDt = new HashMap<>();
    for (int dt : PERIODS) {
      Map<String, Long> oneDt = fetchOnePeriod(dt);
      int idx = PERIODS.indexOf(dt);
      oneDt.forEach((code, qty) -> {
        long[] arr = codeToDt.computeIfAbsent(code, k -> new long[4]);
        arr[idx] = qty;
      });
    }
    Map<String, ForeignNetMap> result = new HashMap<>();
    codeToDt.forEach((code, arr) ->
        result.put(code, new ForeignNetMap(arr[0], arr[1], arr[2], arr[3])));
    return result;
  }

  private Map<String, Long> fetchOnePeriod(int dt) {
    Map<String, Object> body = Map.of(
        "mrkt_tp", "000",
        "trde_tp", "2",
        "dt", String.valueOf(dt),
        "stex_tp", "3");
    JsonNode resp;
    try {
      resp = client.callTr("ka10034", body);
    } catch (Nt2KiwoomException e) {
      log.warn("[NT2 aux] ka10034 dt={} 실패 — err={}", dt, e.getMessage());
      return Map.of();
    }
    if (resp.path("return_code").asInt(-1) != 0) {
      return Map.of();
    }
    Map<String, Long> map = new HashMap<>();
    for (JsonNode row : resp.path("for_dt_trde_upper")) {
      String raw = row.path("stk_cd").asText();
      if (raw.isBlank()) continue;
      long qty = KiwoomNt2NumberParser.parseSignedLong(row.path("netprps_qty").asText());
      map.put(stripSuffix(raw), qty);
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
