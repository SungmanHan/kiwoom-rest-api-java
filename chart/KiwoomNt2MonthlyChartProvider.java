package com.nexttheme.stock.nt2.chart;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexttheme.stock.nt2.KiwoomNt2Client;
import com.nexttheme.stock.nt2.Nt2KiwoomException;
import com.nexttheme.stock.nt2.settings.Nt2AppSettingsService;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * NT2 월봉(ka10083) 종가 조회 (PR-E). 설계서 §4.2.
 *
 * <p>ka10083 단일 호출로 최대 24개월 종가(내림차순) 반환 — {@code stk_mn_pole_chart_qry} LIST.
 * NT1 실측 확인 응답 키: {@code stk_mn_pole_chart_qry} (primary = fallback).
 *
 * <p>월봉은 MA5/MA20 장기 추세 계산 전용 — close 값만 반환.
 * 파싱 실패·빈 응답 시 {@link Nt2KiwoomException} catch 후 {@code List.of()} 반환 (폴백 정책).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KiwoomNt2MonthlyChartProvider {

  private static final String TR_CODE = "ka10083";
  private static final String LIST_KEY = "stk_mth_pole_chart_qry";  // 실측 정정: mn → mth
  private static final int FETCH_MONTHS = 24;
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
   * 월봉 24개월 종가 조회 — 내림차순(rows[0]=최신) 반환.
   *
   * @param itemCode 종목코드 (6자리)
   * @param baseDate 기준일 (기준일 포함 이전 24개월)
   * @return 종가 리스트 (내림차순). 빈 응답·예외 시 {@link List#of()}
   */
  public List<Long> fetchClosePrices(String itemCode, LocalDate baseDate) {
    final String reqCode = resolveReqCode(itemCode);
    Map<String, Object> body = Map.of(
        "stk_cd", reqCode,
        "base_dt", baseDate.format(DateTimeFormatter.BASIC_ISO_DATE),
        "upd_stkpc_tp", "1");
    try {
      JsonNode resp = client.callTr(TR_CODE, body);
      JsonNode rows = resp.path(LIST_KEY);
      if (rows.isMissingNode() || !rows.isArray() || rows.isEmpty()) {
        log.warn("[NT2] ka10083 빈 응답 — code={}", itemCode);
        if (reqCode.endsWith("_AL")) {
          log.warn("[NT2 probe] ka10083 SOR(_AL) 빈 응답 — code={}", itemCode);
        }
        return List.of();
      }
      return parseCloses(rows);
    } catch (Nt2KiwoomException e) {
      log.warn("[NT2] ka10083 호출 실패 — code={}", itemCode, e);
      return List.of();
    }
  }

  private static List<Long> parseCloses(JsonNode rows) {
    List<Long> closes = new ArrayList<>();
    for (JsonNode row : rows) {
      if (closes.size() >= FETCH_MONTHS) break;
      String raw = row.path("cur_prc").asText();
      if (!raw.isBlank()) closes.add(parseLongClean(raw));
    }
    return closes;
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
