// ka10001 현재가 병렬 폴링 (시간외 _AL 접미사 opt-in)
// 사용 TR: ka10001
// 출처: KiwoomDataService.java:1183-1298 (원본 verbatim 발췌 — 클래스 컨텍스트 없이 그대로 옮김)
// 공통 의존: KiwoomApiClient.call(apiId, body) / parsePrefixedLong / resolveStexTp

  public Map<String, PollingDto> pollCurrentPriceParallel(List<String> codes) {
    return pollCurrentPriceParallel(codes, false);
  }

  /**
   * 키움 ka10001으로 현재가 병렬 폴링 — 시간외 접미사 opt-in.
   *
   * <p>{@code afterHoursSuffix=true} + KRX 정규장 외 시간대 → {@code stk_cd}에 {@code _AL}
   * (SOR 통합) 접미사를 부착해 NXT 포함 실거래가를 수집. 접미사는 배치 진입 시 1회 결정.
   *
   * @param codes           종목 코드 목록
   * @param afterHoursSuffix true 시 시간외 구간에서 {@code _AL} 접미사 사용 (갭모니터 전용)
   * @return 종목코드(plain) → PollingDto. enabled=false이면 null 반환
   */
  public Map<String, PollingDto> pollCurrentPriceParallel(List<String> codes, boolean afterHoursSuffix) {
    if (!kiwoomProperties.enabled() || codes.isEmpty()) {
      return null;
    }
    // 배치 진입 시 1회 결정 — N VT 간 시간 경계 불일치 방지
    final String sfx = afterHoursSuffix && !resolveStexTp().equals("1") ? "_AL" : "";
    Map<String, PollingDto> result = new LinkedHashMap<>();
    Semaphore gate = stockSemaphore();

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<PollingDto>> futures = codes.stream()
          .map(code -> executor.submit(() -> withGate(gate, () -> pollCurrentPriceOne(code, sfx))))
          .toList();
      for (int i = 0; i < codes.size(); i++) {
        String code = codes.get(i);
        try {
          PollingDto dto = futures.get(i).get(ENRICH_TIMEOUT_SEC * 3L, TimeUnit.SECONDS);
          // key 는 반드시 plain code (suffix 없음) — suffix 코드로 저장 시 lookup 전부 실패
          if (dto != null) result.put(code, dto);
        } catch (TimeoutException e) {
          futures.get(i).cancel(true);
          log.debug("[키움폴링] 현재가 조회 타임아웃 — 종목={}", code);
        } catch (ExecutionException e) {
          log.debug("[키움폴링] 현재가 조회 실패 — 종목={}", code);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
    }
    return result;
  }

  /** 기존 단인자 — {@code pollCurrentPriceOne(code, "")} 위임으로 교체. */
  private PollingDto pollCurrentPriceOne(String code) {
    return pollCurrentPriceOne(code, "");
  }

  /**
   * ka10001 단일 종목 현재가 조회.
   *
   * @param code 종목코드 (plain)
   * @param sfx  거래소 접미사 — {@code ""} plain(KRX) / {@code "_AL"} SOR 통합(NXT 포함)
   */
  private PollingDto pollCurrentPriceOne(String code, String sfx) {
    final String reqCode = code + sfx;   // sfx=""이면 plain, "_AL"이면 시간외
    log.debug("[ka10001] 요청 — {} (sfx=\"{}\")", code, sfx);
    JsonNode res = kiwoomApiClient.call("ka10001", Map.of("stk_cd", reqCode));
    if (res == null) return null;
    try {
      long   currentPrice = Math.abs(parseLong(res, "cur_prc"));
      long   basePrice    = Math.abs(parseLong(res, "base_pric"));
      long   closePrice   = basePrice > 0 ? basePrice : currentPrice;
      double changeRate   = parseDouble(res, "flu_rt");
      long   volume       = parseLongFallback(res, "trde_qty", "acc_trde_qty");
      String itemName     = fieldText(res, "stk_nm", "stk_kor_nm", code);
      boolean afterHours  = !sfx.isEmpty();   // sfx="_AL"이면 시간외

      return PollingDto.builder()
          // 기본 — itemCode 는 반드시 plain code (suffix 없음)
          .itemCode(code).itemName(itemName)
          // 가격
          .currentPrice(currentPrice).basePrice(basePrice).closePrice(closePrice)
          .openPrice (parseAbsLongNullable(res, "open_pric"))
          .highPrice (parseAbsLongNullable(res, "high_pric"))
          .lowPrice  (parseAbsLongNullable(res, "low_pric"))
          .upperLimitPrice(parseAbsLongNullable(res, "upl_pric"))
          .lowerLimitPrice(parseAbsLongNullable(res, "lst_pric"))
          // 등락
          .changeRate(changeRate).changeFromClose(changeRate)
          .priceChange    (Math.abs(parseLongSafe(res, "pred_pre")))
          .priceChangeSign(parseIntSafe(res, "pre_sig"))
          // 거래량
          .accumulatedTradingVolume(volume)
          .volumeCompare(parseDoubleNullable(res, "trde_pre"))
          // 52주
          .high52w        (parseAbsLongNullable(res, "250hgst"))
          .low52w         (parseAbsLongNullable(res, "250lwst"))
          .high52wDate    (fieldTextSingle(res, "250hgst_pric_dt"))
          .low52wDate     (fieldTextSingle(res, "250lwst_pric_dt"))
          .high52wChangeRate(parseDoubleNullable(res, "250hgst_pric_pre_rt"))
          .low52wChangeRate (parseDoubleNullable(res, "250lwst_pric_pre_rt"))
          // 시총·주식
          .marketCap           (parseAbsLongNullable(res, "mac"))
          .floatingStock       (parseAbsLongNullable(res, "flo_stk"))
          .distributionStock   (parseAbsLongNullable(res, "dstr_stk"))
          .distributionRate    (parseDoubleNullable(res, "dstr_rt"))
          .foreignExhaustRate  (parseDoubleNullable(res, "for_exh_rt"))
          // 재무
          .per(parseDoubleNullable(res, "per"))
          .pbr(parseDoubleNullable(res, "pbr"))
          .roe(parseDoubleNullable(res, "roe"))
          .eps(parseDoubleNullable(res, "eps"))
          .bps(parseDoubleNullable(res, "bps"))
          .overMarketOpen(afterHours)   // sfx="_AL"이면 시간외 체결가 식별
          .build();
    } catch (Exception e) {
      log.debug("[키움폴링] 현재가 파싱 실패 — 종목={} err={}", code, e);
      return null;
    }
  }

  /** 절대값 변환 + 0이면 null 반환 (OHLC 미수집 방어) */
