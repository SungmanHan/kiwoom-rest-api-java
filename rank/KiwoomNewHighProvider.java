package com.nexttheme.stock.nt2.aux;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexttheme.stock.nt2.KiwoomNt2Client;
import com.nexttheme.stock.nt2.Nt2KiwoomException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * NT2 ka10016 신고가 다층 조회 (PR #6). 설계서 §1.2.
 *
 * <p>5기간 (5/20/60/120/250일) 각 1회 호출 → 종목코드 Set. 슬롯당 5회 호출.
 *
 * <p>master TR_PATH_MAP 에 {@code ka10016 → /api/dostk/stkinfo} 등록됨.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KiwoomNewHighProvider {

  private final KiwoomNt2Client client;

  private static final List<Integer> PERIODS = List.of(5, 20, 60, 120, 250);

  /**
   * 5기간 신고가 종목 일괄 조회.
   *
   * @return {@code {5: Set<itemCode>, 20: ..., 60: ..., 120: ..., 250: ...}} — 호출 실패 시 빈 Set
   */
  public Map<Integer, Set<String>> fetchAllPeriods() {
    Map<Integer, Set<String>> result = new HashMap<>();
    for (int dt : PERIODS) {
      result.put(dt, fetchOnePeriod(dt));
    }
    return result;
  }

  private Set<String> fetchOnePeriod(int dt) {
    Map<String, Object> body = Map.of(
        "mrkt_tp", "000",
        "ntl_tp", "1",
        "high_low_close_tp", "1",
        "stk_cnd", "0",
        "trde_qty_tp", "0",
        "crd_cnd", "0",
        "updown_incls", "1",
        "dt", String.valueOf(dt),
        "stex_tp", "3");
    JsonNode resp;
    try {
      resp = client.callTr("ka10016", body);
    } catch (Nt2KiwoomException e) {
      log.warn("[NT2 aux] ka10016 dt={} 실패 — err={}", dt, e.getMessage());
      return Set.of();
    }
    if (resp.path("return_code").asInt(-1) != 0) {
      return Set.of();
    }
    Set<String> codes = new HashSet<>();
    for (JsonNode row : resp.path("ntl_pric")) {
      String raw = row.path("stk_cd").asText();
      if (raw.isBlank()) continue;
      codes.add(stripSuffix(raw));
    }
    return codes;
  }

  private static String stripSuffix(String raw) {
    if (raw.endsWith("_AL") || raw.endsWith("_NX")) {
      return raw.substring(0, raw.length() - 3);
    }
    return raw;
  }
}
