package com.nexttheme.stock.nt2.chart;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexttheme.stock.nt2.KiwoomNt2Client;
import com.nexttheme.stock.nt2.settings.Nt2AppSettingsService;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * NT2 4단계 일봉(ka10081) 호출 + 정렬 정정 (PR #5). 설계서 §3.2.3.
 *
 * <p>ka10081 단일 호출로 600 row 반환(실측, 2026-07-26 정정) — {@code stk_dt_pole_chart_qry} LIST. 응답은 시간 *내림차순*
 * (rows[0]=최신) 가정이며, 만약 오름차순이면 {@link Collections#reverse(List)} 안전장치를 적용한다.
 *
 * <p>설계서 §3.1: 위치는 stock-collector 권고이지만 {@link Nt2DailyCandle} 가 stock-filter 에 위치
 * — 모듈 의존성(stock-collector → stock-filter X) 충돌로 본 PR 에서는 provider 도 stock-filter 에 배치.
 * (PR #2 의 {@code Nt2MarketValueProvider} 와 동일 패턴.)
 *
 * <p>가격 필드는 부호({@code +/-}) 제거 후 long 파싱 — `cur_prc`, `open_pric`, `high_pric`, `low_pric` 모두.
 * 거래대금({@code trde_prica}) 은 백만원 단위 → 원 환산 (× 1,000,000).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KiwoomNt2DailyChartProvider {

  private static final String TR_CODE = "ka10081";
  private static final String LIST_KEY = "stk_dt_pole_chart_qry";
  private static final String EXCHANGE_KEY = "nt2.chart.exchange";

  private final KiwoomNt2Client client;
  private final Nt2AppSettingsService nt2Settings;

  /** 거래소 선택 — KRX(기본)=plain, SOR 통합={@code itemCode+"_AL"}. */
  private String resolveReqCode(String itemCode) {
    return "SOR".equals(nt2Settings.getString(EXCHANGE_KEY, "KRX"))
        ? itemCode + "_AL"
        : itemCode;
  }

  /**
   * 종목 일봉 600일치 조회 — 시간 내림차순 보장.
   *
   * @param itemCode 종목코드 (6자리)
   * @param baseDate 기준일 (YYYYMMDD)
   * @return rows[0]=최신, rows[N-1]=과거. 빈 응답 시 {@link List#of()}
   * @throws com.nexttheme.stock.nt2.Nt2KiwoomException 키움 호출 실패 시
   */
  public List<Nt2DailyCandle> fetchDaily(String itemCode, LocalDate baseDate) {
    // [거래소 메커니즘] ka10081 은 stex_tp 없음 — stk_cd 접미사로만 거래소 선택:
    //   plain(=KRX) / "_NX"(NXT) / "_AL"(SOR 통합).
    //   nt2.chart.exchange=SOR 시 itemCode+"_AL" → NXT 포함 통합 일봉.
    //   키움 PDF p201 / [[reference_kiwoom_exchange_mechanism]].
    final String reqCode = resolveReqCode(itemCode);
    Map<String, Object> body = Map.of(
        "stk_cd", reqCode,
        "base_dt", baseDate.format(DateTimeFormatter.BASIC_ISO_DATE),
        "upd_stkpc_tp", "1");

    JsonNode resp = client.callTr(TR_CODE, body);
    JsonNode rows = resp.path(LIST_KEY);
    if (rows.isMissingNode() || !rows.isArray() || rows.isEmpty()) {
      log.warn("[NT2] ka10081 빈 응답 — code={} baseDate={}", itemCode, baseDate);
      if (reqCode.endsWith("_AL")) {
        log.warn("[NT2 probe] ka10081 SOR(_AL) 빈 응답 — code={}", itemCode);
      }
      return List.of();
    }

    List<Nt2DailyCandle> candles = new ArrayList<>(rows.size());
    for (JsonNode row : rows) {
      parseRow(row).ifPresent(candles::add);
    }
    return ensureDescending(candles, itemCode);
  }

  private static Optional<Nt2DailyCandle> parseRow(JsonNode row) {
    try {
      String dt = row.path("dt").asText();
      if (dt.isBlank()) return Optional.empty();
      return Optional.of(new Nt2DailyCandle(
          LocalDate.parse(dt, DateTimeFormatter.BASIC_ISO_DATE),
          parseLongClean(row.path("open_pric").asText()),
          parseLongClean(row.path("high_pric").asText()),
          parseLongClean(row.path("low_pric").asText()),
          parseLongClean(row.path("cur_prc").asText()),
          parseLongClean(row.path("trde_qty").asText()),
          parseLongClean(row.path("trde_prica").asText()) * 1_000_000L));
    } catch (NumberFormatException | DateTimeParseException e) {
      log.warn("[NT2] ka10081 row 파싱 실패 — err={}", e.getMessage());
      return Optional.empty();
    }
  }

  /** 정렬 안전장치 — 첫 row date 가 마지막 row date 보다 과거면 reverse. */
  private static List<Nt2DailyCandle> ensureDescending(List<Nt2DailyCandle> candles, String itemCode) {
    if (candles.size() < 2) return candles;
    LocalDate first = candles.getFirst().date();
    LocalDate last = candles.getLast().date();
    if (first.isBefore(last)) {
      log.warn("[NT2] ka10081 정렬 오름차순 감지 — code={} first={} last={} reverse 적용",
          itemCode, first, last);
      return new ArrayList<>(candles.reversed());
    }
    return candles;
  }

  /** 부호 접두({@code +/-}) 제거 후 long 파싱. 빈/null → 0. */
  private static long parseLongClean(String raw) {
    if (raw == null || raw.isBlank()) return 0L;
    String s = raw.trim();
    if (!s.isEmpty() && (s.charAt(0) == '+' || s.charAt(0) == '-')) {
      s = s.substring(1);
    }
    return Long.parseLong(s);
  }
}
