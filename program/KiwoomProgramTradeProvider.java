package com.nexttheme.stock.nt2.aux;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexttheme.stock.collector.KiwoomDataService;
import com.nexttheme.stock.nt2.KiwoomNt2Client;
import com.nexttheme.stock.nt2.Nt2KiwoomException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * NT2 프로그램매매 TR 통합 조회. 설계서 §5~§8.
 *
 * <p>ka90003: 시장 프로그램 순매수 상위 50 → NT1 {@link KiwoomDataService#collectMarketProgramRank()} 위임
 * ka90004: 종목별 프로그램매매 현황 → NT1 {@link KiwoomDataService#collectProgramCurrentMap()} 위임
 * ka90013: 종목 일별 프로그램매매 추이 (연속 매수일 계산) — NT2 단독 종목별 호출
 *
 * <p>E-2/E-4 회귀 수정 (2026-05-07):
 * <ul>
 *   <li>ka90004: 종목당 호출(100회, 전부 실패) → NT1 시장 단위 LIST API 위임(2회, 약 60초 절감)</li>
 *   <li>ka90003: trde_upper_tp 반대 + 응답 키 오류 → NT1 정상 메서드 위임</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KiwoomProgramTradeProvider {

  private final KiwoomDataService kiwoomDataService;
  private final KiwoomNt2Client client;

  /**
   * ka90003 — 시장 프로그램 순매수 TOP50 (코드 → 랭크).
   * NT1 {@code collectMarketProgramRank()} 위임 — KOSPI+KOSDAQ 2회 호출.
   *
   * @return 코드 → 랭크(1~50). 50위 밖 종목은 Map에 없음.
   */
  public Map<String, Integer> fetchProgramRankMap() {
    return kiwoomDataService.collectMarketProgramRank();
  }

  /**
   * ka90004 — 종목별 프로그램 순매수 금액 (코드 → 원).
   * NT1 {@code collectProgramCurrentMap()} 위임 — KOSPI+KOSDAQ 2회 호출.
   *
   * @return 코드 → 순매수 금액(원, 양수=순매수). 데이터 없으면 Map에 없음.
   */
  public Map<String, Long> fetchProgramNetAmtMap() {
    return kiwoomDataService.collectProgramCurrentMap();
  }

  /**
   * ka90013 — 일별 프로그램매매 추이에서 연속 순매수일 계산.
   * 최신 행부터 순매수 연속일 카운트.
   *
   * @param itemCode 6자리 종목코드
   * @return 연속 순매수일 수 (0 = 오늘 순매도 또는 데이터 없음)
   */
  public int fetchProgramConsecDays(String itemCode) {
    String date = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
    Map<String, Object> body = Map.of(
        "stk_cd", itemCode,
        "amt_qty_tp", "1",
        "date", date);
    try {
      JsonNode resp = client.callTr("ka90013", body);
      int consec = 0;
      for (JsonNode row : resp.path("prg_trde_stk_dt")) {
        long net = KiwoomNt2NumberParser.parseSignedLong(row.path("netprps_amt").asText("0"));
        if (net > 0) consec++;
        else break;
      }
      return consec;
    } catch (Nt2KiwoomException e) {
      log.warn("[NT2 supply] ka90013 실패 — code={} err={}", itemCode, e.getMessage());
      return 0;
    }
  }
}
