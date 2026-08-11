package com.nexttheme.stock.nt2.aux;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexttheme.stock.nt2.KiwoomNt2Client;
import com.nexttheme.stock.nt2.Nt2KiwoomException;
import com.nexttheme.stock.nt2.settings.Nt2AppSettingsService;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * NT2 장중 투자자별 매매 조회.
 *
 * <p>ka10065: 장중 투자자별 순매수 상위 (외인/기관, NT2 5-A AI 컨텍스트용)
 * ka10063: 당일 장중 외인+기관 누적 흐름 (smtm_netprps_tp=1 당일기준, frgn_all=0 시장별 분리).
 * E-5 회귀 수정 (2026-05-07): 외인(invsr=6)만 호출 → 외인+기관(invsr=6/7) × KOSPI+KOSDAQ = 4회 + IntradayFlow 도입.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KiwoomIntradayInvestorProvider {

  /** 외인 코드 — {@code orgn_tp}. */
  public static final String FOREIGN = "9000";
  /** 기관 코드 — {@code orgn_tp}. */
  public static final String INSTITUTION = "9999";

  private static final int MAX_ATTEMPTS = 3;
  private static final int BACKOFF_MS = 300;

  private final KiwoomNt2Client client;
  private final Nt2AppSettingsService nt2Settings;

  /**
   * ka10063 — 당일 장중 외인+기관 누적 흐름.
   * NT1 collectIntradayFlowParallel 패턴 차용 — 외인+기관 × KOSPI+KOSDAQ = 4회 호출.
   *
   * <p>미수집(재시도 후에도 빈배열) 시 해당 investor의 netQty는 null로 전파.
   *
   * @param currentPrices 종목코드 → 현재가 (억원 환산용)
   * @return 종목코드 → IntradayFlow — 외인·기관 모두 없으면 누락
   */
  public Map<String, IntradayFlow> fetchIntradayFlow(Map<String, Long> currentPrices) {
    Optional<Map<String, Long>> foreignOpt = fetchInvestorNetQty("6", currentPrices.keySet());
    Optional<Map<String, Long>> instOpt    = fetchInvestorNetQty("7", currentPrices.keySet());

    Map<String, IntradayFlow> result = new LinkedHashMap<>();
    for (String code : currentPrices.keySet()) {
      Long fNet = foreignOpt.map(m -> m.getOrDefault(code, 0L)).orElse(null);
      Long iNet = instOpt.map(m -> m.getOrDefault(code, 0L)).orElse(null);
      if (fNet == null && iNet == null) continue;
      long price = currentPrices.get(code);
      String dir = resolveDirection(fNet, iNet);
      double fBn = (fNet != null && price > 0) ? fNet * price / 100_000_000.0 : 0.0;
      double iBn = (iNet != null && price > 0) ? iNet * price / 100_000_000.0 : 0.0;
      result.put(code, new IntradayFlow(fNet, iNet, dir, fBn, iBn));
    }
    return result;
  }

  private static String resolveDirection(Long fNet, Long iNet) {
    if (fNet == null && iNet == null) return "UNKNOWN";
    if (fNet == null) return iNet > 0 ? "ACCUMULATING" : iNet < 0 ? "DISTRIBUTING" : "MIXED";
    if (iNet == null) return fNet > 0 ? "ACCUMULATING" : fNet < 0 ? "DISTRIBUTING" : "MIXED";
    if (fNet > 0 && iNet > 0) return "ACCUMULATING";
    if (fNet < 0 && iNet < 0) return "DISTRIBUTING";
    return "MIXED";
  }

  /**
   * 시장 단위 반복 호출.
   *
   * @return Optional.of(map) = 정상 수집, Optional.empty() = 빈배열 재시도 소진(미수집)
   */
  private Optional<Map<String, Long>> fetchInvestorNetQty(String invsr, Set<String> universe) {
    Map<String, Long> result = new HashMap<>();
    String stexTp = resolveStexTp();
    for (String mrkt : List.of("P00101", "P10102")) {
      if (!fetchMarketInvestorNetQty(invsr, mrkt, stexTp, universe, result)) {
        return Optional.empty();
      }
    }
    return Optional.of(result);
  }

  /**
   * 단일 시장 ka10063 호출 — 빈배열 시 재시도.
   *
   * @return true = COLLECTED (out에 데이터 추가), false = UNCOLLECTED (out 무변경)
   */
  private boolean fetchMarketInvestorNetQty(String invsr, String mrkt, String stexTp,
                                            Set<String> universe, Map<String, Long> out) {
    Map<String, Object> body = Map.of(
        "mrkt_tp", mrkt, "amt_qty_tp", "2", "invsr", invsr,
        "frgn_all", "0", "smtm_netprps_tp", "1", "stex_tp", stexTp);
    for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
      try {
        JsonNode resp = client.callTr("ka10063", body);
        JsonNode rows = resp.path("opmr_invsr_trde");
        int returnCode = resp.path("return_code").asInt(-1);

        if (returnCode == 0 && rows.isArray() && !rows.isEmpty()) {
          for (JsonNode row : rows) {
            String code = stripSuffix(row.path("stk_cd").asText());
            if (code.isBlank() || !universe.contains(code)) continue;
            long qty = KiwoomNt2NumberParser.parseSignedLong(row.path("netprps_qty").asText("0"));
            out.merge(code, qty, Long::sum);
          }
          return true;
        }

        if (returnCode == 0 && rows.isArray() && rows.isEmpty()) {
          boolean retryEnabled = nt2Settings.getBoolean("nt2.supply.empty-retry.enabled", true);
          if (retryEnabled && isMarketHours() && attempt < MAX_ATTEMPTS) {
            log.debug("[NT2 supply] ka10063 invsr={} mrkt={} 빈배열 재시도 {}/{}", invsr, mrkt, attempt, MAX_ATTEMPTS);
            try {
              Thread.sleep((long) BACKOFF_MS * attempt);
            } catch (InterruptedException ie) {
              Thread.currentThread().interrupt();
              return false;
            }
            continue;
          }
          log.warn("[NT2 supply] ka10063 invsr={} mrkt={} 빈배열 — 미수집(attempt={})", invsr, mrkt, attempt);
          return false;
        }

        log.warn("[NT2 supply] ka10063 invsr={} mrkt={} return_code={} — 미수집", invsr, mrkt, returnCode);
        return false;

      } catch (Nt2KiwoomException e) {
        log.warn("[NT2 supply] ka10063 invsr={} mrkt={} 실패 — err={}", invsr, mrkt, e.getMessage());
        return false;
      }
    }
    return false;
  }

  /**
   * ka10065 — 장중 투자자별 순매수 상위 종목 netslmt 매핑.
   *
   * @param orgnTp {@link #FOREIGN} or {@link #INSTITUTION}
   * @return 종목코드 → netslmt (부호 보존, 양수=순매수) — 호출 실패 시 빈 Map
   */
  public Map<String, Long> fetchTopBuyers(String orgnTp) {
    Map<String, Object> body = Map.of(
        "trde_tp", "1",
        "mrkt_tp", "000",
        "orgn_tp", orgnTp,
        "amt_qty_tp", "1",
        "stex_tp", "1");
    JsonNode resp;
    try {
      resp = client.callTr("ka10065", body);
    } catch (Nt2KiwoomException e) {
      log.warn("[NT2 aux] ka10065 orgnTp={} 실패 — err={}", orgnTp, e.getMessage());
      return Map.of();
    }
    if (resp.path("return_code").asInt(-1) != 0) {
      return Map.of();
    }
    Map<String, Long> map = new LinkedHashMap<>();
    for (JsonNode row : resp.path("opmr_invsr_trde_upper")) {
      String raw = row.path("stk_cd").asText();
      if (raw.isBlank()) continue;
      long net = KiwoomNt2NumberParser.parseSignedLong(row.path("netslmt").asText());
      map.put(stripSuffix(raw), net);
    }
    return map;
  }

  private static String stripSuffix(String raw) {
    if (raw.endsWith("_AL") || raw.endsWith("_NX")) {
      return raw.substring(0, raw.length() - 3);
    }
    return raw;
  }

  /** 장중 여부 — 09:00~20:00 (NXT 슬롯 포함). */
  private static boolean isMarketHours() {
    LocalTime now = LocalTime.now();
    return now.isAfter(LocalTime.of(9, 0)) && now.isBefore(LocalTime.of(20, 0));
  }

  private static String resolveStexTp() {
    LocalTime now = LocalTime.now();
    return (now.isAfter(LocalTime.of(9, 0)) && now.isBefore(LocalTime.of(15, 30)))
        ? "1" : "2";
  }

  /**
   * ka10063 — 당일 장중 외인+기관 누적 흐름 컨테이너.
   *
   * @param foreignNetQty 외인 순매수 수량 (주, 음수=순매도, null=미수집)
   * @param instNetQty    기관 순매수 수량 (주, null=미수집)
   * @param flowDirection ACCUMULATING / DISTRIBUTING / MIXED / UNKNOWN(부분 미수집)
   * @param foreignNetBillion 외인 순매수 환산 (억원, 미수집 시 0.0)
   * @param instNetBillion    기관 순매수 환산 (억원, 미수집 시 0.0)
   */
  public record IntradayFlow(
      Long foreignNetQty,
      Long instNetQty,
      String flowDirection,
      double foreignNetBillion,
      double instNetBillion) {

    public static IntradayFlow empty() {
      return new IntradayFlow(0L, 0L, "MIXED", 0.0, 0.0);
    }
  }
}
