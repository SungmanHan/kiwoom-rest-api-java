package com.nexttheme.stock.nt2.aux;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexttheme.stock.nt2.KiwoomNt2Client;
import com.nexttheme.stock.nt2.dto.Nt2ShortSelling;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * NT2 ka10014 공매도 ratio/trend 조회 (C-4). 설계서 §3-1.
 *
 * <p>최근 14일치 공매도 데이터 수집 — 공매도 비중(%) + 잔고 추세 + 숏커버 기대감 판정.
 * Rate Limiter 200ms 공유 (Stage 5-E 독립 루프에서 직렬 호출).
 *
 * <p>⚠️ 응답 {@code shrts_trnsn} 은 거래일이 아니라 <b>공매도가 발생한 날</b>만 담는다
 * (원시 응답 313건 실측, 2026-07-20~24). 따라서 {@code rows[0]} 이 당일이 아닐 수 있고
 * (T-1 이 다수, 최대 5일 stale), {@code rows[0]}↔{@code rows[1]} 도 인접 거래일이 아닐 수 있다
 * (16% 가 4일 간격). 추세는 <b>인접 거래일일 때만</b> 판정하고 그 외에는 STABLE(판단 보류)로 둔다.
 * 설계서: plan/design-nt2-short-selling-c4-phase2-redesign-20260725.md §2.5, §3.1
 *
 * <p>⚠️ <b>{@code short_ratio_pct} 수집률은 슬롯별로 본다.</b> 미수집(NULL) 대부분은 파싱 실패가
 * 아니라 Stage 5-E 가 유니버스 전체가 아닌 <b>후보 종목만</b> 조회하기 때문이다(2026-07-27 실측:
 * NULL 8건 전부 ka10014 호출 자체가 없었고, 호출된 11건은 전원 적재 성공).
 * 슬롯별 NULL 비율 baseline — FINAL 10~28% / EXPLORE_AM 25~62%.
 * <b>슬롯을 섞어 비교하면 정상 수집을 회귀로 오판한다.</b>
 *
 * <p>실패 시 {@link Nt2ShortSelling#empty()} 반환 — 오케스트레이터 흐름 중단 없음.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KiwoomShortSellingProvider {

  /** 조회 창 — 달력 7일은 연휴 시 rows&lt;2 로 추세 판정 불가. 거래일 확보를 위해 14일. */
  private static final int LOOKBACK_DAYS = 14;

  // 0.05%p 미만 변화는 노이즈로 보고 STABLE — float 정밀도 + 소수점 1자리 API 응답 고려
  private static final double TREND_EPSILON = 0.05;

  private final KiwoomNt2Client client;

  /**
   * ka10014 공매도 조회.
   *
   * @param itemCode 6자리 종목코드
   * @return 공매도 현황 — 실패 시 empty
   */
  public Nt2ShortSelling fetch(String itemCode) {
    try {
      String endDt  = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
      String strtDt = LocalDate.now().minusDays(LOOKBACK_DAYS)
          .format(DateTimeFormatter.BASIC_ISO_DATE);
      JsonNode resp = client.callTr("ka10014", Map.of(
          "stk_cd", itemCode,
          // tm_tp=1(기간별) — 스펙은 docs/api/kiwoom/shsa.md §Request Body.
          // ⚠️ "기간별"은 집계 반환이 아니라 기간 조회다. 2026-07-27 10:10 슬롯 첫 라이브 응답
          //    11건 전수 실측: shrts_trnsn 일자별 9행 + dt 내림차순(20260724 → 20260723),
          //    파싱 성공률 100%. 도입 시엔 미검증이었으나 형태는 tm_tp 생략 때와 동일하다.
          "tm_tp", "1",
          "strt_dt", strtDt,
          "end_dt", endDt));
      return parse(resp);
    } catch (Exception e) {
      log.warn("[NT2 short] ka10014 실패 — code={} err={}", itemCode, e.getMessage());
      return Nt2ShortSelling.empty();
    }
  }

  private static Nt2ShortSelling parse(JsonNode resp) {
    JsonNode rows = resp.path("shrts_trnsn");
    if (!rows.isArray() || rows.isEmpty()) return Nt2ShortSelling.empty();

    // trde_wght=공매도비중(%). ovr_shrts_qty는 기간 합산 누적량(단조증가)으로 trend 판단 불가
    // → 대안 B: 당일/전일 trde_wght 전일 대비 증감으로 trend 재정의 (2026-06-14)
    double shortRatio = parseWeight(rows.get(0));
    String trend = resolveTrend(rows, shortRatio);
    return new Nt2ShortSelling(shortRatio, trend, deriveCoverPotential(shortRatio, trend));
  }

  /**
   * rows[0]↔rows[1] 이 <b>인접 거래일일 때만</b> 추세를 판정한다.
   *
   * <p>응답이 공매도 발생일만 담으므로 두 행이 며칠 떨어져 있을 수 있고, 그 경우 "전일 대비"라는
   * 전제가 깨진다(실측 16%). 판정 불가 구간은 STABLE 로 보류해 잘못된 방향 라벨이 프롬프트로
   * 흘러가지 않게 한다.
   */
  private static String resolveTrend(JsonNode rows, double shortRatio) {
    if (rows.size() < 2) {
      log.debug("[NT2 short] 추세 보류 — 행 부족(rows={})", rows.size());
      return "STABLE";
    }
    Optional<LocalDate> latest = parseDate(rows.get(0));
    Optional<LocalDate> prev   = parseDate(rows.get(1));
    if (latest.isEmpty() || prev.isEmpty()) {
      log.debug("[NT2 short] 추세 보류 — dt 파싱 실패");
      return "STABLE";
    }
    if (!isAdjacentTradingDay(latest.get(), prev.get())) {
      log.debug("[NT2 short] 추세 보류 — 비인접 {}일 간격({} ← {})",
          ChronoUnit.DAYS.between(prev.get(), latest.get()), latest.get(), prev.get());
      return "STABLE";
    }
    return deriveTrend(shortRatio, parseWeight(rows.get(1)));
  }

  /**
   * 인접 거래일 여부 — <b>보수적 판정</b>.
   *
   * <p>휴장일 달력 없이 판정하므로 달력 1일(화~금)과 금→월(3일)만 인접으로 인정한다.
   * 공휴일이 낀 구간은 실제로 인접이어도 STABLE 로 떨어지지만, 잘못된 방향 라벨보다 낫다.
   * ⚠️ 달력일 차이만 쓰면 월요일이 전부 비인접(3일)으로 오판되므로 요일 조건이 필수다.
   *
   * <p><b>역순 가드</b>: {@code rows[0]}=최신 전제가 깨져 응답이 오름차순으로 오면 {@code days}
   * 가 음수가 되어 두 조건 모두 false → <b>전 종목이 조용히 STABLE 로 붕괴</b>한다. 그 경우
   * 원인을 알 수 없으므로 WARN 으로 남긴다(2026-07-27 실측에서는 내림차순 확인 —
   * {@code 20260724 → 20260723}, 11건 전수).
   */
  private static boolean isAdjacentTradingDay(LocalDate latest, LocalDate prev) {
    long days = ChronoUnit.DAYS.between(prev, latest);
    if (days <= 0) {
      log.warn("[NT2 short] ka10014 응답 역순 의심 — rows[0]={} rows[1]={} (days={})."
          + " rows[0]=최신 전제가 깨지면 전 종목 STABLE 로 붕괴한다", latest, prev, days);
      return false;
    }
    if (days == 1) return true;
    return days == 3 && latest.getDayOfWeek() == DayOfWeek.MONDAY;
  }

  private static double parseWeight(JsonNode row) {
    return KiwoomNt2NumberParser.parseSignedDouble(row.path("trde_wght").asText("0"));
  }

  /** dt(YYYYMMDD) 파싱 — 결측·형식 오류 시 empty (호출측에서 STABLE 처리). */
  private static Optional<LocalDate> parseDate(JsonNode row) {
    try {
      return Optional.of(
          LocalDate.parse(row.path("dt").asText(""), DateTimeFormatter.BASIC_ISO_DATE));
    } catch (DateTimeParseException e) {
      return Optional.empty();
    }
  }

  private static String deriveTrend(double currentRatio, double prevRatio) {
    double delta = currentRatio - prevRatio;
    if (delta > TREND_EPSILON) return "INCREASING";
    if (delta < -TREND_EPSILON) return "DECREASING";
    return "STABLE";
  }

  private static String deriveCoverPotential(double shortRatio, String trend) {
    if (shortRatio > 3.0 && "DECREASING".equals(trend)) return "HIGH";
    if (shortRatio > 1.5) return "MODERATE";
    return "LOW";
  }
}
