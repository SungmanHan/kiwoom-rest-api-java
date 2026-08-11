// ka10080 분봉 병렬 수집 (Virtual Thread + Semaphore 게이트)
// 사용 TR: ka10080
// 출처: KiwoomDataService.java:850-906 (원본 verbatim 발췌 — 클래스 컨텍스트 없이 그대로 옮김)
// 공통 의존: KiwoomApiClient.call(apiId, body) / parsePrefixedLong / resolveStexTp

   * @param codes    종목 코드 목록
   * @param ticScope 분봉 단위 (1=1분봉, 5=5분봉)
   * @return 종목코드 → CandleDto 리스트. enabled=false이면 null 반환
   */
  public Map<String, List<CandleDto>> collectCandlesParallel(List<String> codes, int ticScope) {
    if (!kiwoomProperties.enabled() || codes.isEmpty()) {
      return null;
    }
    int parallelism = kiwoomProperties.effectiveStockParallelism();
    log.info("[키움분봉] {}분봉 병렬 수집 — {}개 종목 (동시 {}개)", ticScope, codes.size(), parallelism);
    long start = System.currentTimeMillis();

    Map<String, List<CandleDto>> result = new LinkedHashMap<>();
    Semaphore gate = stockSemaphore();

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<List<CandleDto>>> futures = codes.stream()
          .map(code -> executor.submit(() -> withGate(gate, () -> collectCandlesOne(code, ticScope))))
          .toList();

      for (int i = 0; i < codes.size(); i++) {
        String code = codes.get(i);
        try {
          List<CandleDto> candles = futures.get(i).get(ENRICH_TIMEOUT_SEC * 3L, TimeUnit.SECONDS);
          result.put(code, candles != null ? candles : List.of());
        } catch (TimeoutException | ExecutionException e) {
          futures.get(i).cancel(true);
          log.debug("[키움분봉] 수집 실패 — 종목={}", code);
          result.put(code, List.of());
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          result.put(code, List.of());
        }
      }
    }
    log.info("[키움분봉] {}분봉 완료 — {}개, {}ms", ticScope, result.size(),
        System.currentTimeMillis() - start);
    return result;
  }

  private List<CandleDto> collectCandlesOne(String code, int ticScope) {
    // [FIX 2026-04-17] 키움 ka10080 파라미터 정정: updn_tp(미지원) → upd_stkpc_tp("1"=수정주가)
    // [거래소 메커니즘] ka10080 은 stex_tp 없음 — stk_cd 접미사로만(plain=KRX / _AL=통합). 현재 KRX-only.
    //   [[reference_kiwoom_exchange_mechanism]]
    JsonNode res = kiwoomApiClient.call("ka10080",
        Map.of("stk_cd", code, "tic_scope", String.valueOf(ticScope),
               "upd_stkpc_tp", "1"));
    if (res == null) return null;
    // 문서 기준 응답 키: stk_min_pole_chart_qry, 이전 추정 키 fallback
    return parseOhlcvCandlesFallback(res, "stk_min_pole_chart_qry", "stk_mnt_pole_chart_qry");
  }

  // ────────────────────────────────────────────────────────────────
  //  Phase 2: 장중 기관/외인 실시간 (ka10063) — 14:31 · 15:06 전용
  // ────────────────────────────────────────────────────────────────

  /**
