package com.nexttheme.stock.nt2.marketvalue;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexttheme.stock.nt2.KiwoomNt2Client;
import com.nexttheme.stock.nt2.domain.Nt2UniverseSnapshot;
import com.nexttheme.stock.nt2.repository.Nt2UniverseSnapshotRepository;
import com.nexttheme.stock.nt2.settings.Nt2AppSettingsService;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * NT2 1단계 거래대금 universe 수집 (PR #2). 설계서 §3.2.3.
 *
 * <p>키움 ka10030 (sort_tp=3 거래대금 정렬, mang_stk_incls=16 복합 제외, trde_prica_tp=5000 → 500억+ 게이트)
 * 호출 → 슬롯별 하한 필터링 → {@code nt2_universe_snapshot} 영속화.
 *
 * <p>응답 LIST 키는 {@code tdy_trde_qty_upper}. {@code stk_cd} 의 {@code _AL} 접미 정정 필수
 * (stex_tp=3 통합 호출).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class Nt2MarketValueProvider {

  private static final String TR_CODE = "ka10030";
  private static final String LIST_KEY = "tdy_trde_qty_upper";
  private static final String PRICE_TP_KEY = "nt2.universe.price-tp";

  private final KiwoomNt2Client client;
  private final Nt2MarketValueProperties props;
  private final Nt2UniverseSnapshotRepository repo;
  private final Nt2AppSettingsService nt2Settings;

  /**
   * 1단계 universe 수집.
   *
   * @param date 수집 기준일 (DB 적재용)
   * @param timeSlot {@code "10:05" | "12:05" | "14:35" | "15:05"}
   * @return universe (빈 응답 시 빈 리스트)
   * @throws com.nexttheme.stock.nt2.Nt2KiwoomException 키움 호출 실패 시
   */
  public List<Nt2UniverseStock> fetchUniverse(LocalDate date, String timeSlot) {
    JsonNode resp = client.callTr(TR_CODE, buildRequestBody());
    JsonNode rows = resp.path(LIST_KEY);
    if (rows.isMissingNode() || !rows.isArray() || rows.isEmpty()) {
      log.warn("[NT2] ka10030 빈 응답 — date={} slot={}", date, timeSlot);
      return List.of();
    }

    List<Nt2UniverseStock> universe = parseUniverse(rows, timeSlot);
    if (!universe.isEmpty()) {
      persistSnapshot(date, timeSlot, universe);
    }
    log.info("[NT2] universe 수집 — date={} slot={} count={} (raw rows={})",
        date, timeSlot, universe.size(), rows.size());
    return universe;
  }

  /** ka10030 요청 body — 설계서 §1.1 v1.1 (mang_stk_incls=16). */
  private Map<String, Object> buildRequestBody() {
    String pricTp = nt2Settings.getString(PRICE_TP_KEY, "7");
    log.debug("[NT2] ka10030 pric_tp={}", pricTp);
    return Map.of(
        "mrkt_tp", "000",
        "sort_tp", "3",
        "mang_stk_incls", "16",
        "crd_tp", "0",
        "trde_qty_tp", "0",
        "pric_tp", pricTp,
        "trde_prica_tp", "5000",  // 500억(trde_prica_tp=5000) 게이트
        "mrkt_open_tp", "0",      // 전체 조회 (장중=1 → 전체=0)
        // [거래소 메커니즘] ka10030 은 랭킹 TR — stex_tp 파라미터 지원. "3"=KRX+NXT 통합.
        //   ⚠️ 비대칭 주의: universe 는 통합으로 종목 선정하나, 호가(ka10004)·차트(ka10081/80)는
        //   단일종목 TR(stex_tp 없음·stk_cd 접미사 방식)이라 현재 KRX-only → 소스 불일치.
        //   통일 설계 대기. [[reference_kiwoom_exchange_mechanism]]
        "stex_tp", "3");
  }

  private List<Nt2UniverseStock> parseUniverse(JsonNode rows, String timeSlot) {
    // trde_amt 단위 = 백만원 (실측 확인: 삼성전자 11,526,863 백만원 ≈ 11.5조원)
    // 300억 = 30,000 백만원 → resolveMinTradeValueBillion * 100
    long minBaekManWon = props.resolveMinTradeValueBillion(timeSlot) * 100L;
    int universeSize = props.getUniverseSize();
    List<Nt2UniverseStock> result = new ArrayList<>(universeSize);
    int rank = 0;
    for (JsonNode row : rows) {
      rank++;
      if (rank > universeSize) break;
      parseRow(row, rank, minBaekManWon).ifPresent(result::add);
    }
    return result;
  }

  private Optional<Nt2UniverseStock> parseRow(JsonNode row, int rank, long minBaekManWon) {
    try {
      long tradeAmt = parseLongClean(row.path("trde_amt").asText()); // 백만원 단위
      if (tradeAmt < minBaekManWon) return Optional.empty();
      String code = row.path("stk_cd").asText().replaceAll("_AL$", "");
      return Optional.of(new Nt2UniverseStock(
          code,
          row.path("stk_nm").asText(),
          parseLongClean(row.path("cur_prc").asText()),
          parseDoubleClean(row.path("flu_rt").asText()),
          parseLongClean(row.path("trde_qty").asText()),
          tradeAmt,
          parseDoubleClean(row.path("trde_tern_rt").asText()),
          rank));
    } catch (NumberFormatException e) {
      log.warn("[NT2] ka10030 row 파싱 실패 — rank={} err={}", rank, e.getMessage());
      return Optional.empty();
    }
  }

  private void persistSnapshot(LocalDate date, String timeSlot, List<Nt2UniverseStock> universe) {
    LocalDateTime now = LocalDateTime.now();
    List<Nt2UniverseSnapshot> entities = universe.stream()
        .map(u -> Nt2UniverseSnapshot.builder()
            .date(date)
            .timeSlot(timeSlot)
            .itemCode(u.itemCode())
            .itemName(u.itemName())
            .rank(u.rank())
            .currentPrice(u.currentPrice())
            .tradingValueWon(u.tradingValueWon())
            .changeRate(u.changeRate())
            .turnoverRate(u.turnoverRate())
            .volume(u.volume())
            .createdAt(now)
            .build())
        .toList();
    repo.saveAll(entities);
  }

  /** 부호 접두({@code +/-}) 제거 후 long 파싱. 빈 값은 0. */
  private static long parseLongClean(String raw) {
    if (raw == null || raw.isBlank()) return 0L;
    String s = raw.trim();
    if (!s.isEmpty() && (s.charAt(0) == '+' || s.charAt(0) == '-')) s = s.substring(1);
    return s.isEmpty() ? 0L : Long.parseLong(s);
  }

  /** 부호 유지 double 파싱. 빈 값은 0.0. */
  private static double parseDoubleClean(String raw) {
    if (raw == null || raw.isBlank()) return 0.0;
    return Double.parseDouble(raw.trim());
  }
}
