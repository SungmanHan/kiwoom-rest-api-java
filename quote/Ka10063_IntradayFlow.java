// ka10063 장중 투자자별 시장 순매수 (시장 2회 병합)
// 사용 TR: ka10063
// 출처: KiwoomDataService.java:943-993 (원본 verbatim 발췌 — 클래스 컨텍스트 없이 그대로 옮김)
// 공통 의존: KiwoomApiClient.call(apiId, body) / parsePrefixedLong / resolveStexTp


  /**
   * ka10063 단일 투자자 기준 시장 전체 순매수(주) Map 반환.
   * KOSPI(P00101) + KOSDAQ(P10102) 각 1회 호출 후 병합.
   *
   * @param invsr 투자자 구분 — "6"=외국인, "7"=기관계
   */
  private Map<String, Long> fetchMarketNetQty(String invsr) {
    Map<String, Long> result = new HashMap<>();
    result.putAll(fetchMarketNetQtyByMarket(invsr, "P00101")); // KOSPI
    result.putAll(fetchMarketNetQtyByMarket(invsr, "P10102")); // KOSDAQ
    return result;
  }

  /**
   * ka10063 단일 시장·단일 투자자 기준 순매수 수량(주) Map 반환.
   *
   * <p>amt_qty_tp=2(수량) → 응답 필드 netprps_qty(주 단위, 음수=순매도).
   * ka90008 응답 교차 검증: amt_qty_tp=1(금액) → netprps_amt(백만원), amt_qty_tp=2(수량) → netprps_qty(주).
   *
   * @param invsr   투자자 구분 — "6"=외국인, "7"=기관계
   * @param mrktTp  시장 구분 — "P00101"=KOSPI, "P10102"=KOSDAQ
   */
  private Map<String, Long> fetchMarketNetQtyByMarket(String invsr, String mrktTp) {
    JsonNode res = kiwoomApiClient.call("ka10063",
        Map.of("mrkt_tp", mrktTp,
               "amt_qty_tp", "2",          // 2=수량(주), 응답 필드=netprps_qty
               "invsr", invsr,              // 6=외국인, 7=기관계
               "frgn_all", "0",            // 외국인 전체 포함(0=ALL)
               "smtm_netprps_tp", "0",     // 동시순매수 전체
               "stex_tp", resolveStexTp()));
    if (res == null) return Map.of();
    try {
      JsonNode rows = res.path("opmr_invsr_trde");
      if (!rows.isArray() || rows.isEmpty()) return Map.of();

      Map<String, Long> map = new HashMap<>(rows.size());
      for (JsonNode row : rows) {
        String stk = row.path("stk_cd").asText("");
        if (stk.isEmpty()) continue;
        // [FIX 2026-05-01] NXT 시간대 호출 시 응답 stk_cd 가 "005930_NX" 형식 — 추적 종목 lookup 매칭 위해 접미사 제거
        if (stk.endsWith("_NX") || stk.endsWith("_AL")) stk = stk.substring(0, stk.length() - 3);
        // netprps_qty: 해당 투자자(invsr)의 순매수 수량(주). 음수(순매도)는"--N" 표기
        map.put(stk, parsePrefixedLong(row.path("netprps_qty").asText("0")));
      }
      return map;
    } catch (RuntimeException e) {
      log.warn("[키움장중] ka10063 파싱 실패 — invsr={}, mrktTp={}", invsr, mrktTp, e);
      return Map.of();
    }
  }
