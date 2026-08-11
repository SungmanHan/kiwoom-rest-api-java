package com.nexttheme.stock.nt2.bonus.rule;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexttheme.stock.nt2.KiwoomNt2Client;
import com.nexttheme.stock.nt2.Nt2KiwoomException;
import com.nexttheme.stock.nt2.settings.Nt2AppSettingsService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * NT2 ka10004 주식호가 10단 조회 (PR #7 — 5-B 룰 2). 설계서 §1.1, §3.2.3.
 *
 * <p>매수/매도 1~5차 호가 + 잔량을 추출하여 {@link OrderBook} 으로 normalize.
 * 응답은 root 직속 60+ 필드 (LIST 없음) — 매수 1차는 {@code buy_fpr_*}, 2~5차는 {@code buy_{i}th_pre_*}.
 *
 * <p>위치: PR #5 {@code KiwoomNt2DailyChartProvider} 와 동일 패턴 — record 가 stock-filter 모듈에
 * 위치하므로 provider 도 stock-filter 에 배치하여 모듈 의존성 충돌(stock-collector → stock-filter X) 회피.
 *
 * <p>가격 부호({@code +/-})는 절대값으로 strip — 호가는 항상 양수.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KiwoomOrderBookProvider {

  private static final String TR_CODE = "ka10004";
  private static final int LEVEL_COUNT = 5;

  /** 거래소 토글 — nt2_app_settings. "KRX"(기본·현행) | "SOR"(KRX+NXT 통합, stk_cd+"_AL"). */
  private static final String EXCHANGE_KEY = "nt2.orderbook.exchange";

  private final KiwoomNt2Client client;
  private final Nt2AppSettingsService nt2Settings;

  /**
   * ka10004 호가창 응답. 매수 1~5차 + 매도 1~5차 + 총잔량.
   *
   * <p>{@code totalBidQty}/{@code totalAskQty} = 총매수/매도잔량(`tot_buy_req`/`tot_sel_req`),
   * 정규 총잔량이 0이면 시간외(`ovt_buy_req`/`ovt_sel_req`) 폴백 — 저녁 애프터마켓 대응(실측 2026-07-23).
   */
  public record OrderBook(
      String baseTime,
      List<BidLevel> bidLevels,
      List<BidLevel> askLevels,
      long totalBidQty,
      long totalAskQty) {}

  /** 호가 1단 — 가격 × 잔량 = 잔량금액. */
  public record BidLevel(int level, long price, long quantity) {
    public long amount() {
      return price * quantity;
    }
  }

  /**
   * 매도(ask) 1~5차 중 상위 3개 잔량금액(price×qty) 합산 — 매도벽(C2) 분석용.
   *
   * <p>{@link BidLevel#amount()} 재사용. ask 호가가 3개 미만이면 존재하는 만큼만 합산.
   */
  public static long askTop3Amount(List<BidLevel> askLevels) {
    if (askLevels == null || askLevels.isEmpty()) {
      return 0L;
    }
    return askLevels.stream()
        .limit(3)
        .mapToLong(BidLevel::amount)
        .sum();
  }

  /**
   * 단일 종목 호가창 조회.
   *
   * @param itemCode 6자리 종목코드
   * @return 응답 — return_code != 0 또는 키움 호출 실패 시 empty
   */
  public Optional<OrderBook> fetch(String itemCode) {
    // [거래소 메커니즘] ka10004 는 stex_tp 파라미터 없음 — 거래소 선택은 stk_cd 접미사로만:
    //   plain(=KRX) / "_NX"(NXT) / "_AL"(SOR 통합). 토글 nt2.orderbook.exchange 로 제어(기본 KRX).
    //   ⚠️ 토글 SOR 전환 = order_book tier 임계 재캘리브레이션 선결(통합 호가 깊이↑). 키움 PDF p24
    //     / [[reference_kiwoom_exchange_mechanism]]. 응답은 stk_cd 비의존 파싱이라 접미사 strip 불요.
    return fetchByReqCode(resolveReqCode(itemCode), itemCode);
  }

  /**
   * 거래소 통합(_AL=KRX+NXT) 강제 조회 — 전역 토글 무관.
   *
   * <p>반자동 매수 앵커 전용(저녁 NXT 매수벽 필요). 앵커는 {@code piggyPrice}(가격 레벨)만 쓰므로
   * grade tier 재캘리브레이션(통합 호가 깊이↑)과 무관하다. 선별 스코어링의 {@link #fetch}는 전역 토글 유지.
   */
  public Optional<OrderBook> fetchUnified(String itemCode) {
    return fetchByReqCode(itemCode + "_AL", itemCode);
  }

  /** reqCode(거래소 접미사 확정) 로 ka10004 조회 — return_code≠0·실패·파싱오류 시 empty. */
  private Optional<OrderBook> fetchByReqCode(String reqCode, String itemCode) {
    JsonNode resp;
    try {
      resp = client.callTr(TR_CODE, Map.of("stk_cd", reqCode));
    } catch (Nt2KiwoomException e) {
      log.warn("[NT2 rule] ka10004 호출 실패 — code={} err={}", reqCode, e.getMessage());
      return Optional.empty();
    }
    if (resp.path("return_code").asInt(-1) != 0) {
      // probe 진단: SOR(_AL) 모드에서 비정상 응답 시 warn — 정규장 _AL 작동 여부 판별용
      if (reqCode.endsWith("_AL")) {
        log.warn("[NT2 probe] ka10004 SOR(_AL) return_code 비정상 — code={}", reqCode);
      }
      return Optional.empty();
    }
    try {
      return Optional.of(new OrderBook(
          resp.path("bid_req_base_tm").asText(""),
          parseBidLevels(resp),
          parseAskLevels(resp),
          totalQty(resp, "tot_buy_req", "ovt_buy_req"),
          totalQty(resp, "tot_sel_req", "ovt_sel_req")));
    } catch (NumberFormatException e) {
      log.warn("[NT2 rule] ka10004 응답 파싱 실패 — code={} err={}", itemCode, e.getMessage());
      return Optional.empty();
    }
  }

  /** 토글 기반 거래소 코드 — "SOR" 이면 stk_cd 에 "_AL"(KRX+NXT 통합), 그 외 plain(KRX). */
  private String resolveReqCode(String itemCode) {
    return "SOR".equals(nt2Settings.getString(EXCHANGE_KEY, "KRX"))
        ? itemCode + "_AL"
        : itemCode;
  }

  /** 총잔량 — 정규(regularKey)가 양수면 그 값, 아니면 시간외(overtimeKey) 폴백. 부호·콤마 strip. */
  private static long totalQty(JsonNode resp, String regularKey, String overtimeKey) {
    long regular = Math.abs(Nt2KiwoomResponseParser.parseLongClean(resp.path(regularKey).asText()));
    if (regular > 0) {
      return regular;
    }
    return Math.abs(Nt2KiwoomResponseParser.parseLongClean(resp.path(overtimeKey).asText()));
  }

  private static List<BidLevel> parseBidLevels(JsonNode resp) {
    List<BidLevel> bids = new ArrayList<>(LEVEL_COUNT);
    bids.add(new BidLevel(1,
        Nt2KiwoomResponseParser.parseLongClean(resp.path("buy_fpr_bid").asText()),
        resp.path("buy_fpr_req").asLong(0L)));
    for (int i = 2; i <= LEVEL_COUNT; i++) {
      bids.add(new BidLevel(i,
          Nt2KiwoomResponseParser.parseLongClean(resp.path("buy_" + i + "th_pre_bid").asText()),
          resp.path("buy_" + i + "th_pre_req").asLong(0L)));
    }
    return bids;
  }

  private static List<BidLevel> parseAskLevels(JsonNode resp) {
    List<BidLevel> asks = new ArrayList<>(LEVEL_COUNT);
    asks.add(new BidLevel(1,
        Nt2KiwoomResponseParser.parseLongClean(resp.path("sel_fpr_bid").asText()),
        resp.path("sel_fpr_req").asLong(0L)));
    for (int i = 2; i <= LEVEL_COUNT; i++) {
      asks.add(new BidLevel(i,
          Nt2KiwoomResponseParser.parseLongClean(resp.path("sel_" + i + "th_pre_bid").asText()),
          resp.path("sel_" + i + "th_pre_req").asLong(0L)));
    }
    return asks;
  }
}
