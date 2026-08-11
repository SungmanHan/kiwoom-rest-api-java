package com.nexttheme.stock.nt2.aux;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexttheme.stock.nt2.KiwoomNt2Client;
import com.nexttheme.stock.nt2.Nt2KiwoomException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * NT2 ka00198 실시간종목조회순위(관심순위) 조회.
 * 설계서 {@code plan/design-nt2-attention-rank-ka00198-20260726.md} §3.
 *
 * <p>{@code qry_tp=4}(당일 누적)·{@code qry_tp=1}(1분) 각 1회 호출 — TOP20 고정,
 * {@code cont-yn: N} 로 연속조회 불가.
 *
 * <p>master {@code KiwoomApiClient.TR_PATH_MAP} 에 {@code ka00198 → /api/dostk/stkinfo} 등록됨.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KiwoomAttentionRankProvider {

  private final KiwoomNt2Client client;

  /** TOP20 원본 1행 — {@code nt2_attention_rank} 적재 + 종목코드(6자리, 접미사 없음) 매핑 겸용. */
  public record RawRow(
      int rank, String itemCode, String itemName, Integer rankChg, double baseCompChgr, String respTm) {}

  /**
   * ka00198 TOP20 원본 조회.
   *
   * <p>{@code rank_chg} 는 <b>qry_tp 무관하게 응답 그대로</b> 담는다 — 이 record 는 원본 로그
   * ({@code nt2_attention_rank}) 적재용이고, <b>ka00198 은 과거 조회가 불가능</b>(설계서 §9)해
   * 수집 시점에 버린 값은 영구 복구 불가이기 때문이다. 필드 부재·공백만 null(0 대체 금지, 설계서 §4.2).
   *
   * <p>⚠️ 파생 컬럼 {@code nt2_snapshot_stocks.attention_rank_chg} 의 소스는 여전히
   * <b>{@code qry_tp=1} 응답만</b>이다 — 창 선택은 호출부에서 한다
   * ({@code Nt2SnapshotOrchestrator#buildAttentionRankChgMap} 는 {@code attentionRaw1} 만 참조).
   *
   * <p>이력: 도입 시엔 {@code qry_tp=1} 외 전부 null 로 버렸고 근거는 "누적창은 전 행 0" 이었으나,
   * 2026-07-27 재측정에서 <b>재현되지 않았다</b> — {@code qry_tp=4} 도 {@code +1/-1/+2} 등을 반환하고
   * 즉시 재호출 시 값이 동일(난수 아님), 시간대가 바뀌면 값도 바뀐다. 최초 측정(2026-07-26 21:03·21:05)이
   * 두 호출 모두 {@code tm=210000} 인 동일 동결창이었던 것이 오판 원인으로 추정된다.
   * 누적창 {@code rank_chg} 의 <b>의미</b>는 여전히 미확정이라 파생·점수에는 쓰지 않는다(설계서 §9 갱신주기 미해명).
   *
   * @param qryTp {@code "4"}(당일 누적) 또는 {@code "1"}(1분)
   * @return 20건(고정) — 실패 시 빈 리스트
   */
  public List<RawRow> fetchRaw(String qryTp) {
    JsonNode resp;
    try {
      resp = client.callTr("ka00198", Map.of("qry_tp", qryTp));
    } catch (Nt2KiwoomException e) {
      log.warn("[NT2 aux] ka00198(qry_tp={}) 실패 — err={}", qryTp, e.getMessage());
      return List.of();
    }
    if (resp.path("return_code").asInt(-1) != 0) {
      return List.of();
    }
    List<RawRow> rows = new ArrayList<>();
    for (JsonNode row : resp.path("item_inq_rank")) {
      String itemCode = row.path("stk_cd").asText();
      if (itemCode.isBlank()) continue;
      rows.add(new RawRow(
          row.path("bigd_rank").asInt(0),
          itemCode,
          row.path("stk_nm").asText(),
          parseRankChg(row.path("rank_chg").asText()),
          KiwoomNt2NumberParser.parseSignedDouble(row.path("base_comp_chgr").asText()),
          row.path("tm").asText()));
    }
    return rows;
  }

  /**
   * {@code rank_chg} 파싱 — 부호는 값 문자열에 포함된다({@code "+1"}/{@code "-3"}).
   *
   * <p>공백은 <b>0 이 아니라 null</b>: 계측 컬럼이라 "변동 없음(0)" 과 "값 없음" 을 구분해야 한다
   * (설계서 §4.2). {@code KiwoomNt2NumberParser.parseSignedLong} 은 공백을 0 으로 만들므로 직접 가드한다.
   *
   * <p>⚠️ {@code rank_chg_sign} 은 쓰지 않는다 — 실측상 변동 없을 때 빈 문자열이라 부호원으로 쓸 수 없다.
   */
  private static Integer parseRankChg(String raw) {
    if (raw == null || raw.isBlank()) return null;
    return (int) KiwoomNt2NumberParser.parseSignedLong(raw);
  }
}
