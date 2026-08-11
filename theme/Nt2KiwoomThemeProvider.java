package com.nexttheme.stock.nt2.theme;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexttheme.stock.nt2.KiwoomNt2Client;
import com.nexttheme.stock.nt2.settings.Nt2AppSettingsService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.IntStream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * NT2 키움 테마 데이터 수집기 (Nt2ThemeProvider 개명, §7). 설계서 §3.2.3.
 *
 * <p>키움 ka90001 (테마그룹별) → 3조건 필터 → 1차 통과 전체 반환 (limit 없음).
 * 각 테마마다 ka90002 (테마구성종목) → 거래대금 환산({@code cur_prc × acc_trde_qty}) 정렬 → 상위 4개 종목.
 * TOP6 확정은 {@link com.nexttheme.stock.nt2.theme.Nt2ThemeAnalyzer#rankByOverlap} 에서 수행.
 */
@Slf4j
@Qualifier("kiwoom")
@Service
@RequiredArgsConstructor
public class Nt2KiwoomThemeProvider implements Nt2ThemeSource {

  private static final String TR_KA90001 = "ka90001";
  private static final String TR_KA90002 = "ka90002";
  private static final String LIST_KEY_THEMES = "thema_grp";
  private static final String LIST_KEY_STOCKS = "thema_comp_stk";
  private static final String EXCHANGE_KEY = "nt2.theme.exchange";

  private final KiwoomNt2Client client;
  private final Nt2ThemeProperties props;
  private final Nt2AppSettingsService nt2Settings;

  @Override
  public List<Nt2DominantTheme> selectFilteredThemes(double minChangeRate, Set<String> carryInCodes) {
    JsonNode resp = client.callTr(TR_KA90001, buildKa90001Body(resolveStexTp()));
    JsonNode rows = resp.path(LIST_KEY_THEMES);
    if (rows.isMissingNode() || !rows.isArray() || rows.isEmpty()) {
      log.warn("[NT2] ka90001 빈 응답");
      return List.of();
    }

    List<Nt2DominantTheme> filtered = new ArrayList<>();
    for (JsonNode row : rows) {
      parseTheme(row, minChangeRate, carryInCodes).ifPresent(filtered::add);
    }
    return rankFiltered(filtered);
  }

  @Override
  public List<Nt2ThemeStock> fetchThemeStocks(String themeCode) {
    JsonNode resp = client.callTr(TR_KA90002, buildKa90002Body(themeCode, resolveStexTp()));
    JsonNode rows = resp.path(LIST_KEY_STOCKS);
    if (rows.isMissingNode() || !rows.isArray() || rows.isEmpty()) {
      log.warn("[NT2] ka90002 빈 응답 — themeCode={}", themeCode);
      return List.of();
    }

    List<Nt2ThemeStock> all = new ArrayList<>(rows.size());
    for (JsonNode row : rows) {
      parseStock(row).ifPresent(all::add);
    }
    return rankTopByTradeValue(all);
  }

  /** 거래소 선택 — SOR 통합(기본)=3, KRX=1. */
  private String resolveStexTp() {
    return "KRX".equals(nt2Settings.getString(EXCHANGE_KEY, "SOR")) ? "1" : "3";
  }

  private static Map<String, Object> buildKa90001Body(String stexTp) {
    return Map.of(
        "qry_tp", "1",
        "stk_cd", "",
        "date_tp", "1",
        "thema_nm", "",
        "flu_pl_amt_tp", "3",
        "stex_tp", stexTp);
  }

  private static Map<String, Object> buildKa90002Body(String themeCode, String stexTp) {
    return Map.of(
        "date_tp", "1",
        "thema_grp_cd", themeCode,
        "stex_tp", stexTp);
  }

  private Optional<Nt2DominantTheme> parseTheme(JsonNode row, double minChangeRate,
      Set<String> carryInCodes) {
    try {
      return isThemeValid(row, minChangeRate, carryInCodes)
          ? Optional.of(buildDominantTheme(row)) : Optional.empty();
    } catch (NumberFormatException e) {
      log.warn("[NT2] ka90001 row 파싱 실패 — err={}", e.getMessage(), e);
      return Optional.empty();
    }
  }

  private boolean isThemeValid(JsonNode row, double minChangeRate, Set<String> carryInCodes) {
    int stkNum = parseIntSafe(row.path("stk_num").asText());
    if (stkNum < props.getMinStockNum()) return false;
    if (matchesExcludePattern(row.path("thema_nm").asText())) return false;
    return parseDoubleSafe(row.path("flu_rt").asText()) > minChangeRate
        || carryInCodes.contains(row.path("thema_grp_cd").asText());
  }

  private Nt2DominantTheme buildDominantTheme(JsonNode row) {
    int stkNum = parseIntSafe(row.path("stk_num").asText());
    int rising = parseIntSafe(row.path("rising_stk_num").asText());
    double risingRatio = stkNum == 0 ? 0.0 : (double) rising / stkNum;
    return new Nt2DominantTheme(
        row.path("thema_grp_cd").asText(),
        row.path("thema_nm").asText(),
        0,
        parseDoubleSafe(row.path("flu_rt").asText()),
        stkNum,
        rising,
        risingRatio,
        parseDoubleSafe(row.path("dt_prft_rt").asText()),
        row.path("main_stk").asText())
        .withThemeSource("KIWOOM");
  }

  private boolean matchesExcludePattern(String name) {
    if (name == null || name.isBlank()) return false;
    return props.getExcludeNamePatterns().stream().anyMatch(name::contains);
  }

  /** 등락률 내림차순 정렬 전체 반환 (limit·rank 부여 없음). */
  private static List<Nt2DominantTheme> rankFiltered(List<Nt2DominantTheme> filtered) {
    return filtered.stream()
        .sorted(Comparator.comparingDouble(Nt2DominantTheme::changeRate).reversed())
        .toList();
  }

  private static Optional<Nt2ThemeStock> parseStock(JsonNode row) {
    try {
      long cur = parseLongClean(row.path("cur_prc").asText());
      long qty = parseLongClean(row.path("acc_trde_qty").asText());
      String code = row.path("stk_cd").asText().replaceAll("_AL$", "");
      return Optional.of(new Nt2ThemeStock(
          code,
          row.path("stk_nm").asText(),
          cur,
          parseDoubleSafe(row.path("flu_rt").asText()),
          qty,
          cur * qty,
          0,
          false));
    } catch (NumberFormatException e) {
      log.warn("[NT2] ka90002 row 파싱 실패 — err={}", e.getMessage(), e);
      return Optional.empty();
    }
  }

  private List<Nt2ThemeStock> rankTopByTradeValue(List<Nt2ThemeStock> stocks) {
    List<Nt2ThemeStock> top = stocks.stream()
        .sorted(Comparator.comparingLong(Nt2ThemeStock::approxTradingValue).reversed())
        .limit(props.getStocksPerTheme())
        .toList();
    return IntStream.range(0, top.size())
        .mapToObj(i -> withRank(top.get(i), i + 1))
        .toList();
  }

  private static Nt2ThemeStock withRank(Nt2ThemeStock s, int rank) {
    return new Nt2ThemeStock(
        s.itemCode(), s.itemName(), s.currentPrice(), s.changeRate(),
        s.accVolume(), s.approxTradingValue(), rank, rank == 1);
  }

  private static long parseLongClean(String raw) {
    if (raw == null || raw.isBlank()) return 0L;
    String s = raw.trim();
    if (!s.isEmpty() && (s.charAt(0) == '+' || s.charAt(0) == '-')) s = s.substring(1);
    return s.isEmpty() ? 0L : Long.parseLong(s);
  }

  private static double parseDoubleSafe(String raw) {
    if (raw == null || raw.isBlank()) return 0.0;
    return Double.parseDouble(raw.trim());
  }

  private static int parseIntSafe(String raw) {
    if (raw == null || raw.isBlank()) return 0;
    String s = raw.trim();
    if (!s.isEmpty() && (s.charAt(0) == '+' || s.charAt(0) == '-')) s = s.substring(1);
    return s.isEmpty() ? 0 : Integer.parseInt(s);
  }
}
