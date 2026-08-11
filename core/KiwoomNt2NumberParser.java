package com.nexttheme.stock.nt2.aux;

/**
 * NT2 보조 TR 응답 파서 — Kiwoom 부호 표기 정규화 (PR #6).
 *
 * <p>Kiwoom 응답은 {@code +/-} 접두를 가질 수 있고, 이중 dash {@code --} 는 음수 표기 변형이다.
 * 기존 {@code parseLongClean} (chart) 은 부호를 *전부 strip* 하지만, 5-A 보조 TR (netprps_qty / netslmt /
 * netprps_amt) 는 *부호가 의미를 가지므로* 보존해야 한다.
 */
public final class KiwoomNt2NumberParser {

  private KiwoomNt2NumberParser() {}

  /**
   * 부호 보존 long 파싱 — 빈/null → 0.
   * <ul>
   *   <li>{@code "+27885"} → 27885</li>
   *   <li>{@code "-27885"} → -27885</li>
   *   <li>{@code "--27885"} → -27885 (Kiwoom 이중 dash)</li>
   * </ul>
   */
  public static long parseSignedLong(String raw) {
    if (raw == null || raw.isBlank()) {
      return 0L;
    }
    String s = raw.trim();
    if (s.startsWith("--")) {
      String digits = s.substring(2);
      return digits.isBlank() ? 0L : -Long.parseLong(digits);
    }
    if (s.startsWith("+")) {
      String digits = s.substring(1);
      return digits.isBlank() ? 0L : Long.parseLong(digits);
    }
    return Long.parseLong(s);
  }

  /** 키움 응답 종목코드 'A' 접두사 제거. "A005930" → "005930". 이미 bare이면 그대로. */
  public static String stripItemCodePrefix(String raw) {
    if (raw == null || raw.isBlank()) return raw;
    return raw.startsWith("A") ? raw.substring(1) : raw;
  }

  /** 부호 보존 double 파싱. */
  public static double parseSignedDouble(String raw) {
    if (raw == null || raw.isBlank()) {
      return 0.0;
    }
    String s = raw.trim();
    if (s.startsWith("--")) {
      String digits = s.substring(2);
      return digits.isBlank() ? 0.0 : -Double.parseDouble(digits);
    }
    if (s.startsWith("+")) {
      String digits = s.substring(1);
      return digits.isBlank() ? 0.0 : Double.parseDouble(digits);
    }
    return Double.parseDouble(s);
  }
}
