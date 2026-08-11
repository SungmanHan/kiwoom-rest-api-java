// ka10100 NXT 상장 여부 확인 (응답 키가 camelCase인 예외 TR)
// 사용 TR: ka10100
// 출처: KiwoomDataService.java:537-557 (원본 verbatim 발췌)
// 공통 의존: KiwoomApiClient.call(apiId, body) / parsePrefixedLong / resolveStexTp


  /**
   * ka10100 종목정보 조회 — NXT 상장 여부만 확인하는 경량 호출.
   *
   * <p>응답 키 {@code nxtEnable}(camelCase, 이 TR 특유 — 대부분의 다른 TR은 snake_case)는
   * 2026-07-25 라이브 실측 확정: 흥구석유(024060, 507615 원 장애 종목)={@code "N"},
   * 삼성전자(005930)·현대힘스(460930)={@code "Y"}. `docs/api/kiwoom/raw/ka10100_*.json` 원본 보존.
   *
   * @return {@code true}=NXT 상장 확인, {@code false}=NXT 미상장 확인,
   *         {@code null}=조회 실패(미확인 — 미상장 확정과 구분되는 별도 상태)
   */
  public Boolean isNxtEnabled(String itemCode) {
    JsonNode res = kiwoomApiClient.call("ka10100", Map.of("stk_cd", itemCode));
    if (res == null) {
      log.warn("[ka10100] 응답 없음 — 종목={} (미확인=null, 미상장 확정과 구분)", itemCode);
      return null;
    }
    String flag = res.path("nxtEnable").asText("");
    return "Y".equalsIgnoreCase(flag);
  }

