// ka10004 호가 총잔량 집계 (필드명 fallback 패턴 예시)
// 사용 TR: ka10004
// 출처: KiwoomDataService.java:164-203 (원본 verbatim 발췌 — 클래스 컨텍스트 없이 그대로 옮김)
// 공통 의존: KiwoomApiClient.call(apiId, body) / parsePrefixedLong / resolveStexTp

  // ────────────────────────────────────────────────────────────────
  //  호가잔량 (ka10004)
  // ────────────────────────────────────────────────────────────────

  /**
   * 호가잔량 10단 수집 (ka10004).
   *
   * <p>문서(p.24-26) 기준 필드명:
   * 매수 총잔량=buy_req_tot_qty, 매도 총잔량=sell_req_tot_qty,
   * 매수 1차선 잔량=buy_req1_qty, 매도 1차선 잔량=sell_req1_qty.
   * 실측 전까지 이전 추정 필드명도 fallback으로 유지.
   */
  private OrderBook collectOrderBook(String code) {
    // [거래소 메커니즘] ka10004 는 stex_tp 없음 — 거래소는 stk_cd 접미사로만(KRX plain / _NX / _AL 통합).
    //   현재 plain=KRX-only. 통합 원하면 code+"_AL". [[reference_kiwoom_exchange_mechanism]]
    JsonNode res = kiwoomApiClient.call("ka10004", Map.of("stk_cd", code));
    if (res == null) {
      return null;
    }
    try {
      long bidTotal = parseLongFallback(res, "buy_req_tot_qty", "tot_buy_req");
      long askTotal = parseLongFallback(res, "sell_req_tot_qty", "tot_sel_req");
      double ratio = askTotal > 0 ? (double) bidTotal / askTotal : 0.0;

      // 상위 3호가 집중도: 매수 상위3 / 매수 총잔량
      long top3Bid = parseLongFallback(res, "buy_req1_qty", "buy_req1")
          + parseLongFallback(res, "buy_req2_qty", "buy_req2")
          + parseLongFallback(res, "buy_req3_qty", "buy_req3");
      double top3Conc = bidTotal > 0 ? (double) top3Bid / bidTotal * 100.0 : 0.0;

      String signal = ratio >= 1.2 ? "BUY_DOMINANT"
          : ratio <= 0.8 ? "SELL_DOMINANT"
          : "BALANCED";

      return new OrderBook(bidTotal, askTotal, ratio, top3Conc, signal);
    } catch (Exception e) {
      log.debug("[키움호가] 파싱 실패 — 종목={} err={}", code, e.getMessage());
      return null;
    }
  }
