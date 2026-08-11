package com.nexttheme.stock.nt2.aux;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexttheme.stock.nt2.KiwoomNt2Client;
import com.nexttheme.stock.nt2.Nt2KiwoomException;
import com.nexttheme.stock.nt2.dto.CntrStrengthResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * NT2 ka10046 체결강도 조회. 설계서 §3 (체결강도 ka10046), C-5 확장.
 *
 * <p>응답 루트 키: {@code cntr_str_tm} 배열 — 최신부터 과거 순.
 * C-5: 단일값에서 {@link CntrStrengthResult} (5분/60분 추이 + 오후반전) 로 확장.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KiwoomCntrStrengthProvider {

  private static final String TR_CODE = "ka10046";

  private final KiwoomNt2Client client;

  /**
   * 종목 체결강도 추이 수집 (C-5).
   *
   * @return {@link CntrStrengthResult} — 실패 시 {@code empty()}.
   */
  public CntrStrengthResult fetchResult(String itemCode) {
    Map<String, Object> body = Map.of("stk_cd", itemCode + "_AL");
    try {
      JsonNode resp = client.callTr(TR_CODE, body);
      JsonNode rows = resp.path("cntr_str_tm");
      if (!rows.isArray() || rows.isEmpty()) return CntrStrengthResult.empty();

      double current = KiwoomNt2NumberParser.parseSignedDouble(
          rows.get(0).path("cntr_str").asText("0"));
      String trend5  = buildTrend(rows, 3);
      String trend60 = buildTrend(rows, 12);

      boolean hasTrdTm = rows.get(0).has("trd_tm");
      boolean reversal = hasTrdTm && detectAfternoonReversal(rows);

      return new CntrStrengthResult(current, trend5, trend60, hasTrdTm, reversal);
    } catch (Nt2KiwoomException e) {
      log.warn("[NT2 supply] ka10046 실패 — code={} err={}", itemCode, e.getMessage());
      return CntrStrengthResult.empty();
    }
  }

  /**
   * 종목 체결강도 조회 (하위 호환, C-5 이전 인터페이스).
   *
   * @return 체결강도 값 (100 기준). 실패 시 0.0.
   */
  public double fetchCntrStrength(String itemCode) {
    return fetchResult(itemCode).current();
  }

  /** "과거→최신" 형식 추이 문자열. 배열이 count 미만이면 가능한 만큼만 포함. */
  private static String buildTrend(JsonNode rows, int count) {
    int limit = Math.min(count, rows.size());
    List<String> vals = new ArrayList<>(limit);
    for (int i = limit - 1; i >= 0; i--) {
      vals.add(String.format("%.1f", KiwoomNt2NumberParser.parseSignedDouble(
          rows.get(i).path("cntr_str").asText("0"))));
    }
    return String.join("→", vals);
  }

  /**
   * 오후 반전 감지 — 14:00 이후 평균 > 이전 평균이면 true.
   * trd_tm 키 존재 확인 후 호출할 것.
   */
  private static boolean detectAfternoonReversal(JsonNode rows) {
    List<Double> pm = new ArrayList<>();
    List<Double> am = new ArrayList<>();
    for (JsonNode row : rows) {
      String tm  = row.path("trd_tm").asText("000000");
      double val = KiwoomNt2NumberParser.parseSignedDouble(row.path("cntr_str").asText("0"));
      if (tm.compareTo("140000") >= 0) {
        pm.add(val);
      } else {
        am.add(val);
      }
    }
    if (pm.isEmpty() || am.isEmpty()) return false;
    double pmAvg = pm.stream().mapToDouble(Double::doubleValue).average().orElse(0);
    double amAvg = am.stream().mapToDouble(Double::doubleValue).average().orElse(0);
    return pmAvg > amAvg;
  }

}
