// ka10054 VI 발동 이력 (종목이 아닌 시장 조건 조회 TR)
// 사용 TR: ka10054
// 출처: KiwoomDataService.java:346-432 (원본 verbatim 발췌 — 클래스 컨텍스트 없이 그대로 옮김)
// 공통 의존: KiwoomApiClient.call(apiId, body) / parsePrefixedLong / resolveStexTp


  // ────────────────────────────────────────────────────────────────
  //  VI 발동 이력 (ka10054)
  // ────────────────────────────────────────────────────────────────

  /**
   * VI 발동 이력 수집 (ka10054).
   *
   * <p>문서 기준 ka10054는 종목 코드가 아닌 시장 조건으로 리스트를 조회하는 TR.
   * 요청 파라미터: mrkt_tp(0:전체), bf_mkrt_tp(0:전체), motn_tp(0:전체), motn_drc(0:전체).
   * 응답 리스트에서 해당 종목 코드를 필터링하여 ViHistory를 생성.
   */
  private ViHistory collectViHistory(String code) {
    // [FIX 2026-04-17] 키움 ka10054 필수 파라미터 cascade 정정 (서버 응답 cascade로 재발견):
    //   - skip_stk: "000000000"=제외 종목 없음
    //   - trde_qty_tp / min_trde_qty / max_trde_qty: 거래량 필터 비활성
    //   - trde_prica_tp / min_trde_prica / max_trde_prica: 거래대금 필터 비활성
    //   - motn_drc: 발동방향(상승/하락) — 이미 "0"=전체
    Map<String, Object> params = new LinkedHashMap<>();
    params.put("mrkt_tp", "0");           // 전체 시장
    params.put("bf_mkrt_tp", "0");        // 전체 (정규+시간외)
    params.put("motn_tp", "0");           // 전체 (정적+동적)
    params.put("motn_drc", "0");          // 전체 (상승+하락)
    params.put("skip_stk", "000000000");  // 제외 종목 없음
    params.put("trde_qty_tp", "0");       // 거래량 필터 미적용
    params.put("min_trde_qty", "0");      // 최소 거래량 0
    params.put("max_trde_qty", "0");      // 최대 거래량 0 (제한 없음)
    params.put("trde_prica_tp", "0");     // 거래대금 필터 미적용
    params.put("min_trde_prica", "0");    // 최소 거래대금 0
    params.put("max_trde_prica", "0");    // 최대 거래대금 0 (제한 없음)
    params.put("stex_tp", resolveStexTp()); // 거래소 구분 — 시간대별 KRX/NXT 동적 결정
    JsonNode res = kiwoomApiClient.call("ka10054", params);
    if (res == null) {
      return null;
    }
    try {
      // 응답 배열에서 해당 종목 필터링
      JsonNode list = res.path("vi_list");
      if (!list.isArray()) {
        return new ViHistory(0, null, false);
      }

      int count = 0;
      String lastClearTime = null;
      boolean hasActive = false;

      for (JsonNode row : list) {
        String rowCode = row.path("stk_cd").asText("");
        if (!code.equals(rowCode)) {
          continue;
        }
        count++;
        String viType = row.path("viaplc_tp").asText("");     // 정적/동적/동시
        String releaseTime = row.path("virelis_time").asText(""); // VI해제시각
        int viCount = row.path("vimotn_cnt").asInt(0);        // VI발동횟수

        if (viCount > 0 && releaseTime.isBlank()) {
          hasActive = true;
        }
        if (lastClearTime == null && !releaseTime.isBlank() && releaseTime.length() >= 4) {
          lastClearTime = releaseTime.substring(0, 2) + ":" + releaseTime.substring(2, 4);
        }
        if (viCount > 0) {
          count = viCount; // 서버 제공 횟수 우선 사용
        }
      }
      return new ViHistory(count, lastClearTime, hasActive);
    } catch (Exception e) {
      log.debug("[키움VI] 파싱 실패 — 종목={} err={}", code, e.getMessage());
      return null;
    }
  }

  // ────────────────────────────────────────────────────────────────
  //  Phase 2: IntegrationDto 병렬 수집 (ka10001)
  // ────────────────────────────────────────────────────────────────

  /**
   * 키움 ka10001로 IntegrationDto 병렬 수집.
   *
   * <p>오류 정책 (2026-04-17): {@code enabled=false}일 때만 {@code null} 반환(호출부 네이버 폴백).
   * 활성 상태에서 종목 단위 실패는 {@link IntegrationDto#empty()}로 채워 맵에 보존하고
   * {@code log.warn}으로 경고. 전체 호출 실패·토큰 갱신 실패는 예외 전파.
   *
   * @param codes 종목 코드 목록
   * @return 종목코드 → IntegrationDto 맵. enabled=false이면 null 반환
   */
