// 프로그램매매 4종 — 시간별/일별/시장랭크/종목현황
// 사용 TR: ka90008, ka90013, ka90003, ka90004
// 출처: KiwoomDataService.java:1390-1640 (원본 verbatim 발췌 — 클래스 컨텍스트 없이 그대로 옮김)
// 공통 의존: KiwoomApiClient.call(apiId, body) / parsePrefixedLong / resolveStexTp


  private static final long PROGRAM_AMOUNT_UNIT = 1_000_000L;  // 백만원 → 원
  private static final int PROGRAM_CONSEC_THRESHOLD_DAYS = 3;  // 연속 일수 임계 (참고용)

  /**
   * 키움 응답의 부호 접두 문자열을 long으로 파싱.
   * 음수 표기 {@code "--369770"}(2 dashes) / 양수 {@code "+424822"} / 단순 {@code "0"} 모두 처리.
   */
  static long parsePrefixedLong(String raw) {
    if (raw == null || raw.isBlank()) return 0L;
    String s = raw.trim();
    if (s.startsWith("--")) {
      try {
        return -Long.parseLong(s.substring(2));
      } catch (NumberFormatException e) {
        return 0L;
      }
    }
    if (s.startsWith("+")) s = s.substring(1);
    try {
      return Long.parseLong(s);
    } catch (NumberFormatException e) {
      return 0L;
    }
  }

  // ── ka90008: 종목시간별프로그램매매 ─────────────────────────────────

  /**
   * 종목별 장중 프로그램매매 스냅샷 병렬 수집 (ka90008).
   * 반환 {@link ProgramTrade}는 시각 기반 필드({@code todayNetAmount, last30minNetAmount,
   * hourlyTrend, flowDirection})만 채움. 나머지(consec/tenDay/marketRank)는 0/null.
   */
  public Map<String, ProgramTrade> collectProgramTimeParallel(List<String> codes) {
    if (!kiwoomProperties.enabled() || codes.isEmpty()) return Map.of();
    Map<String, ProgramTrade> result = new LinkedHashMap<>();
    Semaphore gate = stockSemaphore();
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<ProgramTrade>> futures = codes.stream()
          .map(code -> executor.submit(() -> withGate(gate, () -> collectProgramTimeOne(code))))
          .toList();
      for (int i = 0; i < codes.size(); i++) {
        try {
          ProgramTrade data = futures.get(i).get(ENRICH_TIMEOUT_SEC * 3L, TimeUnit.SECONDS);
          if (data != null) result.put(codes.get(i), data);
        } catch (TimeoutException | ExecutionException e) {
          futures.get(i).cancel(true);
          log.debug("[키움프로그램시간] 수집 실패 — 종목={}", codes.get(i));
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
    }
    log.info("[키움프로그램시간] 병렬 수집 완료 — {}/{}개", result.size(), codes.size());
    return result;
  }

  private ProgramTrade collectProgramTimeOne(String code) {
    String today = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
    JsonNode res = kiwoomApiClient.call("ka90008",
        Map.of("amt_qty_tp", "1", "stk_cd", code, "date", today));
    if (res == null) return null;
    JsonNode rows = res.path("stk_tm_prm_trde_trnsn");
    if (!rows.isArray() || rows.isEmpty()) return null;

    // 배열은 최신 → 과거 역순. 최신 틱이 당일 누적.
    long todayNet = parsePrefixedLong(rows.get(0).path("prm_netprps_amt").asText()) * PROGRAM_AMOUNT_UNIT;

    // [FIX 2026-04-20 #3] ka90008 1회 호출당 반환되는 틱은 약 20개(6~7초 × 20 ≈ 2분).
    // 연속조회 미구현(T-11 향후)이므로 실제로는 "최신 − 가장 과거" = 직전 ~2분 차분.
    // 필드명 last30min 은 호환성 유지(API 변경 파급 최소). 의미는 "직전 윈도 차분".
    int targetIdx = rows.size() - 1;
    long past = parsePrefixedLong(rows.get(targetIdx).path("prm_netprps_amt").asText()) * PROGRAM_AMOUNT_UNIT;
    long last30min = todayNet - past;

    // hourlyTrend: 30분 간격 샘플링 (tm HHMMSS 기준, 단순화 — 최신 / 중간 / 마지막 3포인트)
    String hourlyTrend = buildProgramHourlyTrend(rows);

    // flowDirection: 최근 10틱의 prm_netprps_amt_irds 부호 다수결
    String flowDirection = determineFlowDirection(rows);

    return new ProgramTrade(todayNet, last30min, hourlyTrend, flowDirection,
        0, 0L, null);
  }

  private static String buildProgramHourlyTrend(JsonNode rows) {
    int size = rows.size();
    int[] indices = {size - 1, Math.max(0, size / 2), 0};  // 과거 / 중간 / 최신
    List<String> parts = new ArrayList<>();
    for (int idx : indices) {
      JsonNode row = rows.get(idx);
      String tm = row.path("tm").asText("");
      long amt = parsePrefixedLong(row.path("prm_netprps_amt").asText()) * PROGRAM_AMOUNT_UNIT;
      String hhmm = tm.length() >= 4 ? tm.substring(0, 2) + ":" + tm.substring(2, 4) : tm;
      parts.add(String.format("%s(%+.1f억)", hhmm, amt / 100_000_000.0));
    }
    return String.join("→", parts);
  }

  private static String determineFlowDirection(JsonNode rows) {
    int positive = 0;
    int negative = 0;
    int limit = Math.min(10, rows.size());
    for (int i = 0; i < limit; i++) {
      long irds = parsePrefixedLong(rows.get(i).path("prm_netprps_amt_irds").asText());
      if (irds > 0) positive++;
      else if (irds < 0) negative++;
    }
    if (positive > negative * 2) return "ACCUMULATING";
    if (negative > positive * 2) return "DISTRIBUTING";
    return "MIXED";
  }

  // ── ka90013: 종목일별프로그램매매 ─────────────────────────────────

  /**
   * 종목별 일별 프로그램매매 추이 병렬 수집 (ka90013).
   * 반환 {@link ProgramTrade}는 일별 필드({@code consecutiveBuyDays, tenDayNetAmount})만 채움.
   */
  public Map<String, ProgramTrade> collectProgramDailyParallel(List<String> codes) {
    if (!kiwoomProperties.enabled() || codes.isEmpty()) return Map.of();
    Map<String, ProgramTrade> result = new LinkedHashMap<>();
    Semaphore gate = stockSemaphore();
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<ProgramTrade>> futures = codes.stream()
          .map(code -> executor.submit(() -> withGate(gate, () -> collectProgramDailyOne(code))))
          .toList();
      for (int i = 0; i < codes.size(); i++) {
        try {
          ProgramTrade data = futures.get(i).get(ENRICH_TIMEOUT_SEC * 3L, TimeUnit.SECONDS);
          if (data != null) result.put(codes.get(i), data);
        } catch (TimeoutException | ExecutionException e) {
          futures.get(i).cancel(true);
          log.debug("[키움프로그램일별] 수집 실패 — 종목={}", codes.get(i));
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
    }
    log.info("[키움프로그램일별] 병렬 수집 완료 — {}/{}개", result.size(), codes.size());
    return result;
  }

  private ProgramTrade collectProgramDailyOne(String code) {
    String today = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
    JsonNode res = kiwoomApiClient.call("ka90013",
        Map.of("amt_qty_tp", "1", "stk_cd", code, "date", today));
    if (res == null) return null;
    JsonNode rows = res.path("stk_daly_prm_trde_trnsn");
    if (!rows.isArray() || rows.isEmpty()) return null;

    // 최신(오늘)부터 역순으로 prm_netprps_amt 부호 동일 유지 일수
    long firstAmt = parsePrefixedLong(rows.get(0).path("prm_netprps_amt").asText());
    int sign = Long.compare(firstAmt, 0);  // +1 / 0 / -1
    int consec = 0;
    long tenDaySum = 0L;
    int limit = Math.min(10, rows.size());
    for (int i = 0; i < rows.size(); i++) {
      long amt = parsePrefixedLong(rows.get(i).path("prm_netprps_amt").asText());
      if (i < limit) tenDaySum += amt;
      if (sign == 0) break;
      int curSign = Long.compare(amt, 0);
      if (curSign == sign) consec++;
      else break;
    }
    int consecSigned = sign >= 0 ? consec : -consec;
    long tenDayNet = tenDaySum * PROGRAM_AMOUNT_UNIT;

    return new ProgramTrade(0L, 0L, "", "", consecSigned, tenDayNet, null);
  }

  // ── ka90003: 시장 프로그램 순매수 상위 50 (코스피+코스닥 2회 병합) ─────

  /**
   * 시장 프로그램 순매수 TOP 50 종목 랭크 조회 (ka90003).
   * 코스피(P00101) + 코스닥(P10102) 각각 호출 후 병합.
   *
   * @return 종목코드 → 시장별 순매수 랭킹 (1~50, 50위 밖이면 Map에 없음)
   */
  public Map<String, Integer> collectMarketProgramRank() {
    if (!kiwoomProperties.enabled()) return Map.of();
    Map<String, Integer> result = new HashMap<>();
    result.putAll(fetchMarketRank("P00101"));
    result.putAll(fetchMarketRank("P10102"));
    log.info("[키움프로그램랭크] 수집 완료 — 총 {}개 종목", result.size());
    return result;
  }

  private Map<String, Integer> fetchMarketRank(String mrktTp) {
    JsonNode res = kiwoomApiClient.call("ka90003", Map.of(
        "trde_upper_tp", "2",   // 2:순매수상위
        "amt_qty_tp", "1",      // 1:금액
        "mrkt_tp", mrktTp,
        "stex_tp", "1"));
    if (res == null) return Map.of();
    JsonNode rows = res.path("prm_netprps_upper_50");
    if (!rows.isArray() || rows.isEmpty()) return Map.of();

    Map<String, Integer> map = new HashMap<>();
    for (JsonNode row : rows) {
      String code = row.path("stk_cd").asText("");
      int rank = row.path("rank").asInt(0);
      if (!code.isBlank() && rank > 0) map.put(code, rank);
    }
    return map;
  }

  // ── ka90004: 종목별 프로그램매매 현황 (코스피+코스닥 2회 병합) ──────────

  /**
   * 전 종목 프로그램매매 일별 스냅샷 (ka90004).
   * 코스피(P00101) + 코스닥(P10102) 각각 호출 후 병합.
   *
   * @return 종목코드 → 당일 프로그램 순매수 금액 (원 단위, 백만원 × 1_000_000 환산)
   */
  public Map<String, Long> collectProgramCurrentMap() {
    if (!kiwoomProperties.enabled()) return Map.of();
    String today = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
    Map<String, Long> result = new HashMap<>();
    result.putAll(fetchProgramCurrent("P00101", today));
    result.putAll(fetchProgramCurrent("P10102", today));
    log.info("[키움프로그램현황] 수집 완료 — 총 {}개 종목", result.size());
    return result;
  }

  private Map<String, Long> fetchProgramCurrent(String mrktTp, String dt) {
    JsonNode res = kiwoomApiClient.call("ka90004", Map.of(
        "dt", dt, "mrkt_tp", mrktTp, "stex_tp", "1"));
    if (res == null) return Map.of();
    JsonNode rows = res.path("stk_prm_trde_prst");
    if (!rows.isArray() || rows.isEmpty()) return Map.of();

    // [FIX 2026-04-20 #1] 당일(`dt`=today) 호출 시 장 마감 전에는 모든 필드가 빈 문자열로 내려옴.
    // 첫 레코드 stk_cd 공란이면 "해당 일자 데이터 미제공"으로 판단하고 전체 Map 비움.
    if (rows.get(0).path("stk_cd").asText("").isBlank()) {
      log.info("[키움프로그램현황] 데이터 부재 — mrkt_tp={}, dt={} (장 마감 전 당일 호출 추정)",
          mrktTp, dt);
      return Map.of();
    }

    Map<String, Long> map = new HashMap<>();
    for (JsonNode row : rows) {
      String code = row.path("stk_cd").asText("");
      String netprpsRaw = row.path("netprps_prica").asText("");
      if (code.isBlank() || netprpsRaw.isBlank()) continue;
      long netprps = parsePrefixedLong(netprpsRaw) * PROGRAM_AMOUNT_UNIT;
      map.put(code, netprps);
    }
    return map;
  }
}
