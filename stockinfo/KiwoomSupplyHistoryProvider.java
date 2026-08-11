package com.nexttheme.stock.nt2.aux;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexttheme.stock.nt2.KiwoomNt2Client;
import com.nexttheme.stock.nt2.dto.Nt2SupplyHistory;
import com.nexttheme.stock.nt2.dto.Nt2SupplyHistory.DayEntry;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * NT2 ka10059 종목별 투자자별 매매 현황 조회 (C-3). 설계서 §3-1.
 *
 * <p>최근 ~60일치 수급 히스토리 수집 — 5일 상세 + 60일 누적 + 연속 순매수 일수 산출.
 * Rate Limiter 200ms 공유 (Stage 5-D 독립 루프에서 직렬 호출).
 *
 * <p>URL: /api/dostk/stkinfo (KiwoomApiClient.TR_PATH_MAP 기등록).
 * 실패 시 {@link Nt2SupplyHistory#empty()} 반환 — 오케스트레이터 흐름 중단 없음.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KiwoomSupplyHistoryProvider {

  private final KiwoomNt2Client client;

  /**
   * ka10059 수급 히스토리 조회.
   *
   * @param itemCode 6자리 종목코드
   * @return 수급 히스토리 — 실패 시 empty
   */
  public Nt2SupplyHistory fetch(String itemCode) {
    try {
      String today = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
      JsonNode resp = client.callTr("ka10059", Map.of(
          "stk_cd", itemCode,
          "qry_tp", "1",
          "dt", today,
          "amt_qty_tp", "1",
          "trde_tp", "0",
          "unit_tp", "1000"
      ));
      return parse(resp);
    } catch (Exception e) {
      log.warn("[NT2 supply] ka10059 실패 — code={} err={}", itemCode, e.getMessage());
      return Nt2SupplyHistory.empty();
    }
  }

  private Nt2SupplyHistory parse(JsonNode resp) {
    List<DayEntry> days = collectDays(resp);
    Collections.reverse(days);  // API 응답: 최신→과거 → reverse → 오래된→최신

    long fTotal = days.stream().mapToLong(DayEntry::foreignNet).sum();
    long iTotal = days.stream().mapToLong(DayEntry::institutionNet).sum();
    long pTotal = days.stream().mapToLong(DayEntry::individualNet).sum();

    int consecForeigner   = calcConsec(days, DayEntry::foreignNet);
    int consecInstitution = calcConsec(days, DayEntry::institutionNet);

    List<DayEntry> recent5 = extractRecent5(days);

    return new Nt2SupplyHistory(recent5, fTotal, iTotal, pTotal,
        consecForeigner, consecInstitution);
  }

  private static List<DayEntry> collectDays(JsonNode resp) {
    List<DayEntry> days = new ArrayList<>();
    for (JsonNode row : resp.path("stk_invsr_orgn")) {
      days.add(new DayEntry(
          row.path("dt").asText(),
          KiwoomNt2NumberParser.parseSignedLong(row.path("frgnr_invsr").asText("0")),
          KiwoomNt2NumberParser.parseSignedLong(row.path("orgn").asText("0")),
          KiwoomNt2NumberParser.parseSignedLong(row.path("ind_invsr").asText("0"))
      ));
    }
    return days;
  }

  /**
   * 최신 5일 추출 — days는 오래된→최신 순.
   * limit(5) 금지: 오래된 5일 반환 버그 방지.
   */
  private static List<DayEntry> extractRecent5(List<DayEntry> days) {
    if (days.size() <= 5) return new ArrayList<>(days);
    return new ArrayList<>(days.subList(days.size() - 5, days.size()));
  }

  /**
   * 연속 순매수 일수 — days 끝(최신일)부터 역산.
   *
   * @param days   오래된→최신 순 리스트
   * @param getter 순매수 값 추출 함수
   */
  private static int calcConsec(List<DayEntry> days,
      Function<DayEntry, Long> getter) {
    int consec = 0;
    for (int i = days.size() - 1; i >= 0; i--) {
      if (getter.apply(days.get(i)) > 0) consec++;
      else break;
    }
    return consec;
  }
}
