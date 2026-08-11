// ka10081/82/83 일·주·월봉 요청 파라미터 (upd_stkpc_tp 필수)
// 사용 TR: ka10081, ka10082, ka10083
// 출처: KiwoomDataService.java:677-700 (원본 verbatim 발췌 — 클래스 컨텍스트 없이 그대로 옮김)
// 공통 의존: KiwoomApiClient.call(apiId, body) / parsePrefixedLong / resolveStexTp

  private DetailRawData enrichOneFromKiwoom(String code) {
    String today = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE); // yyyyMMdd

    JsonNode basicRes  = kiwoomApiClient.call("ka10001", Map.of("stk_cd", code));
    // [FIX 2026-04-17] 키움 ka10059 필수 파라미터 정정:
    //   - dt: YYYYMMDD 기준일자
    //   - amt_qty_tp: "1"=금액(원) 단위 순매수
    //   - trde_tp: "0"=순매수
    //   - unit_tp: "1000"=천주 단위
    JsonNode supplyRes = kiwoomApiClient.call("ka10059",
        Map.of("stk_cd", code, "qry_tp", "1", "dt", today,
               "amt_qty_tp", "1", "trde_tp", "0", "unit_tp", "1000"));
    // [FIX 2026-04-17] 키움 ka10081/82/83 파라미터 정정: updn_tp(미지원) → upd_stkpc_tp("1"=수정주가)
    // [거래소 메커니즘] ka10081/82/83 은 stex_tp 없음 — stk_cd 접미사로만 거래소 선택(plain=KRX / _AL=통합).
    //   현재 plain=KRX-only. 통합 원하면 code+"_AL". [[reference_kiwoom_exchange_mechanism]]
    JsonNode dayRes    = kiwoomApiClient.call("ka10081",
        Map.of("stk_cd", code, "base_dt", today, "upd_stkpc_tp", "1"));
    JsonNode weekRes   = kiwoomApiClient.call("ka10082",
        Map.of("stk_cd", code, "base_dt", today, "upd_stkpc_tp", "1"));
    JsonNode monthRes  = kiwoomApiClient.call("ka10083",
        Map.of("stk_cd", code, "base_dt", today, "upd_stkpc_tp", "1"));
    // 분기재무 — 키움 미지원 → 네이버에서 수집
    DetailRawData naverQuarterly = naverApiService.collectDetailData(code);

