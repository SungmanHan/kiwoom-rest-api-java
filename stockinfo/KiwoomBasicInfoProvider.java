package com.nexttheme.stock.nt2.aux;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexttheme.stock.nt2.KiwoomNt2Client;
import com.nexttheme.stock.nt2.Nt2KiwoomException;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * NT2 ka10001 종목 기본정보 조회 (PR #6 — 5-A 보조). 설계서 §1.1.
 *
 * <p>종목당 1회 호출 (Rate Limiter 200ms 직렬화) — 시총 / 52주 최고 / 외인 소진률 / 유통주식수.
 *
 * <p>master {@code KiwoomApiClient} 의 TR_PATH_MAP 에 {@code ka10001 → /api/dostk/stkinfo} 등록됨 — 그대로 사용.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KiwoomBasicInfoProvider {

  private final KiwoomNt2Client client;

  /** ka10001 응답 — 시총·52주최고·외인소진률·유통주식수. */
  public record BasicInfo(
      long marketCap,
      long high52w,
      long currentPrice,
      double foreignRate,
      long distributionShares) {}

  /**
   * 단일 종목 기본정보 조회.
   *
   * @param itemCode 6자리 종목코드
   * @return 응답 — 실패 시 empty
   */
  public Optional<BasicInfo> fetch(String itemCode) {
    JsonNode resp;
    try {
      resp = client.callTr("ka10001", Map.of("stk_cd", itemCode));
    } catch (Nt2KiwoomException e) {
      log.warn("[NT2 aux] ka10001 호출 실패 — code={} err={}", itemCode, e.getMessage());
      return Optional.empty();
    }
    if (resp.path("return_code").asInt(-1) != 0) {
      return Optional.empty();
    }
    return Optional.of(new BasicInfo(
        KiwoomNt2NumberParser.parseSignedLong(resp.path("mac").asText()) * 100_000_000L,
        KiwoomNt2NumberParser.parseSignedLong(resp.path("250hgst").asText()),
        KiwoomNt2NumberParser.parseSignedLong(resp.path("cur_prc").asText()),
        KiwoomNt2NumberParser.parseSignedDouble(resp.path("for_exh_rt").asText()),
        KiwoomNt2NumberParser.parseSignedLong(resp.path("dstr_stk").asText()) * 1000L));
  }
}
