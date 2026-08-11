// ka10014 공매도 추이 (구버전 파싱 — shsa 제공자와 비교용)
// 사용 TR: ka10014
// 출처: KiwoomDataService.java:262-310 (원본 verbatim 발췌 — 클래스 컨텍스트 없이 그대로 옮김)
// 공통 의존: KiwoomApiClient.call(apiId, body) / parsePrefixedLong / resolveStexTp


  // ────────────────────────────────────────────────────────────────
  //  공매도 잔고 (ka10014)
  // ────────────────────────────────────────────────────────────────

  private ShortSelling collectShortSelling(String code) {
    // [FIX 2026-04-17] 키움 ka10014 필수 파라미터 strt_dt/end_dt 추가 (최근 7일)
    String today = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
    String weekAgo = LocalDate.now().minusDays(7).format(DateTimeFormatter.BASIC_ISO_DATE);
    JsonNode res = kiwoomApiClient.call("ka10014",
        Map.of("stk_cd", code, "strt_dt", weekAgo, "end_dt", today));
    if (res == null) {
      return null;
    }
    try {
      // [FIX 2026-05-01] 실측 응답 — root 직속 short_sell_rate/_blnc/bef_*_blnc 는 존재하지 않음.
      // shrts_trnsn 배열 첫 row(최신) = 당일, 두 번째 row = 직전일.
      JsonNode rows = res.path("shrts_trnsn");
      if (!rows.isArray() || rows.isEmpty()) {
        return null;
      }
      JsonNode latest = rows.get(0);
      // trde_wght = 공매도 거래 비중(%) — 부호 prefix(+7.23) 포함. parseDouble 가 + 제거.
      double shortRatio = parseDouble(latest, "trde_wght");
      // ovr_shrts_qty = 누적 공매도량 (음수 표기 "--N" 가능성 대비 parsePrefixedLong)
      long shortBalance = parsePrefixedLong(latest.path("ovr_shrts_qty").asText("0"));
      long prevBalance = rows.size() > 1
          ? parsePrefixedLong(rows.get(1).path("ovr_shrts_qty").asText("0"))
          : shortBalance;

      String trend = shortBalance > prevBalance ? "INCREASING"
          : shortBalance < prevBalance ? "DECREASING"
          : "STABLE";

      // 공매도 잔고가 높으면서 감소 추세 → 숏커버 가능성 HIGH
      String coverPotential = (shortRatio > 3.0 && "DECREASING".equals(trend)) ? "HIGH"
          : shortRatio > 1.5 ? "MODERATE"
          : "LOW";

      return new ShortSelling(shortRatio, trend, coverPotential);
    } catch (Exception e) {
      log.debug("[키움공매도] 파싱 실패 — 종목={} err={}", code, e.getMessage());
      return null;
    }
  }

  // ────────────────────────────────────────────────────────────────
  //  거래원 (ka10040)
  // ────────────────────────────────────────────────────────────────
