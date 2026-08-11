// ka10051 시장 전체(KOSPI+KOSDAQ) 투자자 순매수
// 사용 TR: ka10051
// 출처: KiwoomDataService.java:1015-1160 (원본 verbatim 발췌 — 클래스 컨텍스트 없이 그대로 옮김)
// 공통 의존: KiwoomApiClient.call(apiId, body) / parsePrefixedLong / resolveStexTp

   * 와 동일하게 처리). 설계서 §6 Q5 — 키움 점검 시 graceful degradation.
   *
   * @param baseDt 기준일 — ka10051 {@code base_dt} 파라미터 (시간대별 결정은 호출자 책임)
   * @return KOSPI+KOSDAQ 합산 금액 (원 단위) — KOSPI 또는 KOSDAQ 실패 시 {@code null}
   */
  public MarketWideInvestorSupply fetchMarketWideInvestorSupply(LocalDate baseDt) {
    if (!kiwoomProperties.enabled()) {
      return null;
    }
    // 키움 점검 중에는 ka10051 호출 자체를 skip → silent fail (점검 페이지 fetch 노이즈 차단).
    if (kiwoomApiClient.isMaintenanceActive()) {
      log.warn("[ka10051] 키움 점검 중 — silent skip (supply 섹션 omit)");
      return null;
    }
    Map<String, Long> kospi  = callKa10051("0", "001", baseDt);
    Map<String, Long> kosdaq = callKa10051("1", "101", baseDt);
    if (kospi == null || kosdaq == null) {
      log.warn("[ka10051] 시장 전체 수급 부분 실패 — kospi={} kosdaq={} (supply 섹션 omit)",
          kospi != null, kosdaq != null);
      return null;
    }
    // amt_qty_tp="0" 응답 = 억원 단위 → ×100,000,000 원 환산
    return new MarketWideInvestorSupply(
        toWon(kospi.get("frgnr") + kosdaq.get("frgnr")),
        toWon(kospi.get("orgn")  + kosdaq.get("orgn")),
        toWon(kospi.get("ind")   + kosdaq.get("ind")),
        baseDt,
        LocalDateTime.now());
  }

  /** 억원 → 원 환산 (×100,000,000). ka10051 amt_qty_tp="0" 응답 단위 정규화. */
  private static long toWon(long valueBillionWon) {
    return valueBillionWon * 100_000_000L;
  }

  /**
   * ka10051 단일 시장 호출 + 종합 row 추출.
   *
   * <p>응답 edge case 일관 null fallback:
   * <ul>
   *   <li>HTTP 실패 → null</li>
   *   <li>{@code inds_netprps} 빈 배열 → null</li>
   *   <li>종합 row({@code inds_cd}={@code summaryCode}) 부재 → null</li>
   *   <li>{@code frgnr_netprps}/{@code orgn_netprps}/{@code ind_netprps} 필드 부재 → null</li>
   * </ul>
   *
   * @param mrktTp      "0"=KOSPI / "1"=KOSDAQ
   * @param summaryCode 종합 row 식별 코드 ("001"=KOSPI / "101"=KOSDAQ)
   * @param baseDt      기준일 (ka10051 {@code base_dt} 파라미터)
   * @return {frgnr, orgn, ind} 키 보유 Map (금액, 억원 단위 정수) — 실패 시 null
   */
  private Map<String, Long> callKa10051(String mrktTp, String summaryCode, LocalDate baseDt) {
    String baseDtStr = baseDt.format(DateTimeFormatter.BASIC_ISO_DATE);
    JsonNode res = kiwoomApiClient.call("ka10051",
        Map.of("mrkt_tp", mrktTp,
               "amt_qty_tp", "0",          // 0=금액 (단위: 억원, probe 검증 2026-05-27)
               "base_dt", baseDtStr,        // 시간대별 기준일 — 호출자가 결정
               "stex_tp", "3"));           // KRX+NXT 통합 — inds_cd suffix(_NX/_AL) findSummaryRow에서 prefix 매칭
    if (res == null) {
      log.warn("[ka10051] 호출 실패 — mrkt_tp={} (HTTP 실패 or 토큰)", mrktTp);
      return null;
    }
    JsonNode rows = res.path("inds_netprps");
    if (!rows.isArray() || rows.isEmpty()) {
      log.warn("[ka10051] 응답 빈 배열 — mrkt_tp={} return_code={}",
          mrktTp, res.path("return_code").asText(""));
      return null;
    }
    JsonNode summary = findSummaryRow(rows, summaryCode);
    if (summary == null) {
      log.warn("[ka10051] 종합 row 부재 — mrkt_tp={} summary_code={} rows={}",
          mrktTp, summaryCode, rows.size());
      return null;
    }
    return parseKa10051SummaryRow(summary, mrktTp, summaryCode);
  }

  /**
   * ka10051 응답 배열에서 종합 row 검색.
   *
   * <p>stex_tp별 inds_cd suffix 규칙 (Kiwoom 미문서화 동작, 2026-05-28 probe 확인):
   * <ul>
   *   <li>stex_tp=1 (KRX): {@code 001} — suffix 없음</li>
   *   <li>stex_tp=2 (NXT): {@code 001_NX}</li>
   *   <li>stex_tp=3 (통합): {@code 001_AL}</li>
   * </ul>
   * prefix 매칭({@code equals} 또는 {@code startsWith(code + "_")})으로 stex_tp 무관 탐색.
   */
  private static JsonNode findSummaryRow(JsonNode rows, String summaryCode) {
    String prefix = summaryCode + "_";
    for (JsonNode row : rows) {
      String inds = row.path("inds_cd").asText("");
      if (inds.equals(summaryCode) || inds.startsWith(prefix)) {
        return row;
      }
    }
    return null;
  }

  /** ka10051 종합 row → {frgnr, orgn, ind} 금액 Map (억원 단위). 필드 부재 또는 파싱 실패 시 null. */
  private Map<String, Long> parseKa10051SummaryRow(JsonNode row, String mrktTp, String summaryCode) {
    String frgnrRaw = row.path("frgnr_netprps").asText(null);
    String orgnRaw  = row.path("orgn_netprps").asText(null);
    String indRaw   = row.path("ind_netprps").asText(null);
    if (frgnrRaw == null || orgnRaw == null || indRaw == null) {
      log.warn("[ka10051] 필수 필드 부재 — mrkt_tp={} summary_code={} frgnr={} orgn={} ind={}",
          mrktTp, summaryCode, frgnrRaw != null, orgnRaw != null, indRaw != null);
      return null;
    }
    // Q6: amt_qty_tp="0" 응답은 정수 가정 (probe 검증). 소수점 감지 시 warn — 추후 응답 형식 변경 조기 탐지.
    if (frgnrRaw.contains(".") || orgnRaw.contains(".") || indRaw.contains(".")) {
      log.warn("[ka10051] 응답에 소수점 감지 (정수 가정 위배) — mrkt_tp={} frgnr={} orgn={} ind={}",
          mrktTp, frgnrRaw, orgnRaw, indRaw);
    }
    long frgnr = parsePrefixedLong(frgnrRaw);
    long orgn  = parsePrefixedLong(orgnRaw);
    long ind   = parsePrefixedLong(indRaw);
    log.info("[ka10051] mrkt_tp={} summary={} frgnr={}억 orgn={}억 ind={}억",
        mrktTp, summaryCode, frgnr, orgn, ind);
    return Map.of("frgnr", frgnr, "orgn", orgn, "ind", ind);
  }

  /**
   * 키움 거래소 구분(stex_tp) 시간대별 결정.
   *
   * <p>키움 API 거래소 코드:
   * <ul>
   *   <li>{@code "1"} — KRX 한국거래소 (정규장 09:00~15:30)</li>
   *   <li>{@code "2"} — NXT 넥스트레이드 (프리마켓 08:00~09:00, 애프터마켓 15:30~20:00)</li>
   *   <li>{@code "3"} — KRX+NXT 통합</li>
   * </ul>
   *
   * <p>정규장 시간대(09:00~15:30)에는 KRX 데이터를, 그 외 시간(NXT 단독 운영 시간)에는
   * NXT 데이터를 조회한다. {@code 15:30} 마감 시점은 KRX 종료로 간주(NXT 분기).
   *
   * <p>KST 명시 — JVM default zone 의존 방지 (Audit-F3, 2026-05-27).
   *
   * @return stex_tp 코드 ({@code "1"} KRX 또는 {@code "2"} NXT)
   */
  /**
   * 현재 시각이 KRX 정규장(09:00~15:30 KST) 이내인지 판정.
   *
   * <p>stex_tp("1"/"2") 와 dmst_stex_tp("KRX"/"NXT") 는 값 표현이 다르나 시간 경계는 동일 →
   * 두 계열 모두 이 메서드를 SSoT 로 사용한다.
   * KST 명시 — JVM default zone 의존 방지 (Audit-F3, 2026-05-27).
   */
