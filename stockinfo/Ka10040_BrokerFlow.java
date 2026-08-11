// ka10040 당일 주요 거래원 TOP5
// 사용 TR: ka10040
// 출처: KiwoomDataService.java:308-356 (원본 verbatim 발췌 — 클래스 컨텍스트 없이 그대로 옮김)
// 공통 의존: KiwoomApiClient.call(apiId, body) / parsePrefixedLong / resolveStexTp

  // ────────────────────────────────────────────────────────────────
  //  거래원 (ka10040)
  // ────────────────────────────────────────────────────────────────

  private BrokerFlow collectBrokerFlow(String code) {
    JsonNode res = kiwoomApiClient.call("ka10040", Map.of("stk_cd", code));
    if (res == null) {
      return null;
    }
    try {
      JsonNode buyArray = res.path("buy_array");
      if (!buyArray.isArray() || buyArray.isEmpty()) {
        return null;
      }

      JsonNode topBuyerNode = buyArray.get(0);
      String topBuyer = fieldText(topBuyerNode, "mbr_kor_name", "mbr_nm", "");
      long topBuyerAmount = parseLong(topBuyerNode, "buy_qty"); // 매수 수량(주) — 실측 후 금액 필드 확인 필요

      // 외국계 창구 판단: 이름에 "모건", "골드만", "씨티", "CS", "UBS", "메릴" 포함
      boolean foreignBrokerBuying = isForeignBroker(topBuyer);

      return new BrokerFlow(topBuyer, topBuyerAmount, foreignBrokerBuying);
    } catch (Exception e) {
      log.debug("[키움거래원] 파싱 실패 — 종목={} err={}", code, e.getMessage());
      return null;
    }
  }

  private boolean isForeignBroker(String name) {
    if (name == null || name.isBlank()) {
      return false;
    }
    return name.contains("모건") || name.contains("골드만") || name.contains("씨티")
        || name.contains("CS") || name.contains("UBS") || name.contains("메릴")
        || name.contains("JP") || name.contains("도이치") || name.contains("HSBC")
        || name.contains("BNP") || name.contains("노무라") || name.contains("맥쿼리");
  }

  // ────────────────────────────────────────────────────────────────
  //  VI 발동 이력 (ka10054)
  // ────────────────────────────────────────────────────────────────

  /**
   * VI 발동 이력 수집 (ka10054).
   *
   * <p>문서 기준 ka10054는 종목 코드가 아닌 시장 조건으로 리스트를 조회하는 TR.
   * 요청 파라미터: mrkt_tp(0:전체), bf_mkrt_tp(0:전체), motn_tp(0:전체), motn_drc(0:전체).
   * 응답 리스트에서 해당 종목 코드를 필터링하여 ViHistory를 생성.
