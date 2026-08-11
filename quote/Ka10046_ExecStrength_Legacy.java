// ka10046 체결강도 추이 (응답 키 3단 fallback 예시)
// 사용 TR: ka10046
// 출처: KiwoomDataService.java:205-266 (원본 verbatim 발췌 — 클래스 컨텍스트 없이 그대로 옮김)
// 공통 의존: KiwoomApiClient.call(apiId, body) / parsePrefixedLong / resolveStexTp

  // ────────────────────────────────────────────────────────────────
  //  체결강도 (ka10046)
  // ────────────────────────────────────────────────────────────────

  /**
   * 체결강도 추이 수집 (ka10046).
   *
   * <p>문서(p.133-136) 기준 응답 필드:
   * cntr_tm(시간), cntr_str(체결강도), cntr_str_5min, cntr_str_20min, cntr_str_60min.
   * 이전 추정(stk_ccls_strt_array / ccls_str)도 fallback으로 유지.
   */
  private ExecStrength collectExecStrength(String code) {
    JsonNode res = kiwoomApiClient.call("ka10046", Map.of("stk_cd", code));
    if (res == null) {
      return null;
    }
    try {
      // [FIX 2026-05-01] 실측 응답 키 cntr_str_tm 1순위 — 기존 두 키는 호환성 fallback 으로 보존
      JsonNode rows = res.path("cntr_str_tm");
      if (!rows.isArray() || rows.isEmpty()) {
        rows = res.path("stk_ccls_strt_array");
        if (!rows.isArray() || rows.isEmpty()) {
          rows = res.path("cntr_str_array");
          if (!rows.isArray() || rows.isEmpty()) {
            return null;
          }
        }
      }

      // 최신 → 과거 순 정렬된 배열 가정
      // 문서 필드: cntr_str, 이전 추정: ccls_str
      double current = firstNonZero(rows.get(0), "cntr_str", "ccls_str");

      // 5분봉 추이: 최근 3개
      String trend5min = buildTrend(rows, 3);
      // 60분봉 추이: 최근 12개 (5분봉 × 12 = 60분)
      String trend60min = buildTrend(rows, 12);

      // 14:00 이후 100 이상 전환: 최신값이 100 초과인지 확인 (간략화)
      boolean afternoonReversal = current > 100.0;

      return new ExecStrength(current, trend5min, trend60min, afternoonReversal);
    } catch (Exception e) {
      log.debug("[키움체결강도] 파싱 실패 — 종목={} err={}", code, e.getMessage());
      return null;
    }
  }

  private String buildTrend(JsonNode rows, int count) {
    List<String> values = new ArrayList<>();
    int limit = Math.min(count, rows.size());
    // 역순(과거→최신)으로 출력
    for (int i = limit - 1; i >= 0; i--) {
      values.add(String.format("%.1f", firstNonZero(rows.get(i), "cntr_str", "ccls_str")));
    }
    return String.join("→", values);
  }

  // ────────────────────────────────────────────────────────────────
  //  공매도 잔고 (ka10014)
  // ────────────────────────────────────────────────────────────────

