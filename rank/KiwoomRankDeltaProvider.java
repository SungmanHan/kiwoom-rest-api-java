package com.nexttheme.stock.nt2.aux;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexttheme.stock.nt2.KiwoomNt2Client;
import com.nexttheme.stock.nt2.Nt2KiwoomException;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * NT2 ka10032 거래대금 상위 (현재/전일 순위) 조회 (PR #6). 설계서 §1.3.
 *
 * <p>슬롯당 1회 호출 — 종목별 {@code now_rank} / {@code pred_rank} 매핑.
 * {@code rankDelta = pred_rank - now_rank} (양수 = 상승).
 *
 * <p>master TR_PATH_MAP 에 {@code ka10032 → /api/dostk/rkinfo} 등록됨.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KiwoomRankDeltaProvider {

  private final KiwoomNt2Client client;

  /** 현재/전일 순위 (1~100). 미등재 시 0. */
  public record RankInfo(int nowRank, int predRank) {
    /** {@code pred_rank - now_rank} — 양수면 *상승*. */
    public int delta() {
      return predRank - nowRank;
    }
  }

  /**
   * 거래대금 상위 100 종목 → (now, pred) 매핑.
   *
   * @return 종목코드 → RankInfo — 호출 실패 시 빈 Map
   */
  public Map<String, RankInfo> fetchAll() {
    Map<String, Object> body = Map.of(
        "mrkt_tp", "000",
        "mang_stk_incls", "1",
        "stex_tp", "3");
    JsonNode resp;
    try {
      resp = client.callTr("ka10032", body);
    } catch (Nt2KiwoomException e) {
      log.warn("[NT2 aux] ka10032 실패 — err={}", e.getMessage());
      return Map.of();
    }
    if (resp.path("return_code").asInt(-1) != 0) {
      return Map.of();
    }
    Map<String, RankInfo> map = new HashMap<>();
    for (JsonNode row : resp.path("trde_prica_upper")) {
      String raw = row.path("stk_cd").asText();
      if (raw.isBlank()) continue;
      map.put(stripSuffix(raw), new RankInfo(
          row.path("now_rank").asInt(0),
          row.path("pred_rank").asInt(0)));
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
