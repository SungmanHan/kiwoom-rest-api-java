// 거래소 구분(stex_tp) 시간대 분기 — KRX/NXT SSoT
// 사용 TR: 전 TR 공통
// 출처: KiwoomDataService.java:1136-1168 (원본 verbatim 발췌)
// 공통 의존: KiwoomApiClient.call(apiId, body) / parsePrefixedLong / resolveStexTp


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
  public static boolean isKrxRegularHours() {
    LocalTime now = LocalTime.now(ZoneId.of("Asia/Seoul"));
    return !now.isBefore(LocalTime.of(9, 0)) && now.isBefore(LocalTime.of(15, 30));
  }

  private static String resolveStexTp() {
    return isKrxRegularHours() ? "1" : "2";
  }
