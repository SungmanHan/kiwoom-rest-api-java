package com.nexttheme.stock.collector;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexttheme.stock.collector.dto.FillRow;
import com.nexttheme.stock.collector.dto.FillSide;
import com.nexttheme.stock.collector.dto.FillStatus;
import com.nexttheme.stock.collector.dto.OpenOrder;
import com.nexttheme.stock.collector.dto.OrderResult;
import com.nexttheme.stock.nt2.aux.KiwoomNt2NumberParser;
import com.nexttheme.stock.nt2.settings.Nt2OrderModeResolver;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 키움 주문 발주 클라이언트 (매수 kt10000 + 매도 kt10001 + 취소 kt10003 + 체결내역 kt00007).
 *
 * <p><b>발주 인프라 소유주</b> — 손절 Phase 1의 매도({@link #sell})가 {@link #place} 를 재사용해
 * buy() 와 동일한 킬스위치·dry-run 게이트를 물려받는다. 반자동 매수 PR 이 신설(설계서
 * {@code plan/design-nt2-semiauto-buy-20260709.md} §10), 조건부 손절 Phase 1 이 매도로 확장
 * (설계서 {@code plan/design-nt2-autostop-conditional-20260709.md} §13.1).
 *
 * <p><b>킬스위치 게이트</b> ({@link #killSwitchGate} 진입부 강제, 발주·취소 공용):
 * <ul>
 *   <li>{@link Nt2OrderModeResolver#isEnabled()}=false → 발주하지 않고 미접수 결과 반환.</li>
 *   <li>{@link Nt2OrderModeResolver#isDryRun()}=true → 실호출 없이 가상 ord_no 반환(Phase 0).</li>
 * </ul>
 *
 * <p><b>멱등</b>: kt10000/kt10003 은 비멱등 → 내부 재시도를 절대 하지 않는다(이중매수 방지는 호출자
 * {@code claim} 원자적 UPDATE 로 담당).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KiwoomOrderClient {

  private static final String TR_BUY = "kt10000";
  private static final String TR_SELL = "kt10001";
  private static final String TR_MODIFY = "kt10002";
  private static final String TR_CANCEL = "kt10003";
  private static final String TR_FILLS = "kt00007";
  private static final String TR_OPEN_ORDERS = "ka10075";
  private static final String DRY_RUN_PREFIX = "DRYRUN-";

  private final KiwoomApiClient apiClient;
  private final Nt2OrderModeResolver orderMode;

  /**
   * 매수 발주 (kt10000).
   *
   * @param stkCd       종목코드
   * @param qty         주문수량
   * @param ordUv       주문단가(지정가). {@code null}=시장가(ord_uv="")
   * @param trdeTp      매매구분 코드 (0=지정가 / 3=시장가 / 81=장마감후시간외)
   * @param dmstStexTp  거래소 (KRX/NXT/SOR)
   * @return 접수 결과 (ord_no). 비활성·오류 시 {@code accepted()==false}.
   */
  public OrderResult buy(String stkCd, int qty, Long ordUv, String trdeTp, String dmstStexTp) {
    return place(TR_BUY, stkCd, qty, ordUv, trdeTp, dmstStexTp);
  }

  /**
   * 매도 발주 (kt10001) — 조건부 손절 Phase 1 실행 전용. {@link #place} 재사용으로 킬스위치·dry-run
   * 게이트를 buy() 와 동일하게 물려받는다.
   *
   * @param stkCd       종목코드
   * @param qty         주문수량
   * @param ordUv       주문단가(지정가). {@code null}=시장가(ord_uv="")
   * @param trdeTp      매매구분 코드 (0=지정가 / 3=시장가 / 81=장마감후시간외)
   * @param dmstStexTp  거래소 (KRX/NXT/SOR)
   * @return 접수 결과 (ord_no). 비활성·오류 시 {@code accepted()==false}.
   */
  public OrderResult sell(String stkCd, int qty, Long ordUv, String trdeTp, String dmstStexTp) {
    return place(TR_SELL, stkCd, qty, ordUv, trdeTp, dmstStexTp);
  }

  /**
   * 정정 발주 (kt10002) — 원주문의 가격·수량 정정. 정정 접수 후 새 ord_no 발급.
   *
   * <p>정정은 내부적으로 "취소+재발주"이므로 원 ord_no 는 무효화됨 → 응답 ord_no 로 kt00007 재조회 필수.
   * 무재시도 원칙 동일(비멱등).
   *
   * @param origOrdNo   원주문번호(정정 대상 발주 ord_no)
   * @param stkCd       종목코드
   * @param mdyfQty     정정수량({@code mdfy_qty}). {@code "0"}=잔량 전부 정정
   * @param mdyfUv      정정단가({@code mdfy_uv}) — 스펙상 필수
   * @param dmstStexTp  거래소 (KRX/NXT/SOR)
   * @return 접수 결과 (정정주문 ord_no). 비활성·오류 시 {@code accepted()==false}.
   */
  public OrderResult modify(String origOrdNo, String stkCd, String mdyfQty,
      Long mdyfUv, String dmstStexTp) {
    Optional<OrderResult> gated = killSwitchGate(
        "kt10002 정정 origOrdNo=" + origOrdNo + " code=" + stkCd + " uv=" + mdyfUv);
    if (gated.isPresent()) {
      return gated.get();
    }
    return sendModify(origOrdNo, stkCd, mdyfQty, mdyfUv, dmstStexTp);
  }

  /**
   * 취소 발주 (kt10003) — 원주문 잔량 취소. 부분체결분은 브로커가 자동 보존.
   *
   * @param origOrdNo   원주문번호(취소 대상 발주 ord_no)
   * @param stkCd       종목코드
   * @param cnclQty     취소수량. {@code "0"}=잔량 전부 취소
   * @param dmstStexTp  거래소 (KRX/NXT/SOR)
   * @return 접수 결과 (취소주문 ord_no). 비활성·오류 시 {@code accepted()==false}.
   */
  public OrderResult cancel(String origOrdNo, String stkCd, String cnclQty, String dmstStexTp) {
    Optional<OrderResult> gated = killSwitchGate("kt10003 취소 origOrdNo=" + origOrdNo + " code=" + stkCd);
    if (gated.isPresent()) {
      return gated.get();
    }
    return sendCancel(origOrdNo, stkCd, cnclQty, dmstStexTp);
  }

  /**
   * 공통 발주 메서드 — 매수·(향후)매도 공유. 킬스위치 게이트 강제 + 무재시도.
   */
  private OrderResult place(String apiId, String stkCd, int qty, Long ordUv,
      String trdeTp, String dmstStexTp) {
    Optional<OrderResult> gated = killSwitchGate(
        apiId + " code=" + stkCd + " qty=" + qty + " uv=" + ordUv + " trde=" + trdeTp);
    if (gated.isPresent()) {
      return gated.get();
    }
    return sendOrder(apiId, stkCd, qty, ordUv, trdeTp, dmstStexTp);
  }

  /**
   * 킬스위치·dry-run 게이트 (발주·취소 공용). 실발주 진행 시 {@code empty}, 차단/가상 시 결과 반환.
   */
  private Optional<OrderResult> killSwitchGate(String logCtx) {
    if (!orderMode.isEnabled()) {
      log.warn("[주문] nt2.order.mode=OFF — 발주 차단. {}", logCtx);
      return Optional.of(OrderResult.failed(-1, null));
    }
    if (orderMode.isDryRun()) {
      String virtual = DRY_RUN_PREFIX + UUID.randomUUID().toString().substring(0, 8);
      log.info("[주문] DRY-RUN 가상 발주 — {} ordNo={}", logCtx, virtual);
      return Optional.of(OrderResult.dryRun(virtual));
    }
    return Optional.empty();
  }

  private OrderResult sendModify(String origOrdNo, String stkCd, String mdyfQty,
      Long mdyfUv, String dmstStexTp) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("dmst_stex_tp", dmstStexTp);
    body.put("orig_ord_no", origOrdNo);
    body.put("stk_cd", stkCd);
    body.put("mdfy_qty", mdyfQty);
    body.put("mdfy_uv", mdyfUv != null ? String.valueOf(mdyfUv) : "");

    JsonNode resp = apiClient.call(TR_MODIFY, body);   // 무재시도(비멱등)
    if (resp == null) {
      log.warn("[주문] 정정 응답 null — origOrdNo={} code={}", origOrdNo, stkCd);
      return OrderResult.failed(-1, null);
    }
    int rc = resp.path("return_code").asInt(-1);
    String ordNo = resp.path("ord_no").asText(null);
    String msg = resp.path("return_msg").asText("");
    if (rc != 0 || ordNo == null || ordNo.isBlank()) {
      log.warn("[주문] 정정 실패 — origOrdNo={} code={} rc={} msg={}", origOrdNo, stkCd, rc, msg);
      return OrderResult.failed(rc, msg);
    }
    log.info("[주문] 정정 접수 — origOrdNo={} code={} newOrdNo={} rc={}", origOrdNo, stkCd, ordNo, rc);
    return OrderResult.accepted(ordNo);
  }

  private OrderResult sendCancel(String origOrdNo, String stkCd, String cnclQty, String dmstStexTp) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("dmst_stex_tp", dmstStexTp);
    body.put("orig_ord_no", origOrdNo);
    body.put("stk_cd", stkCd);
    body.put("cncl_qty", cnclQty);

    JsonNode resp = apiClient.call(TR_CANCEL, body);   // 무재시도(비멱등)
    if (resp == null) {
      log.warn("[주문] 취소 응답 null — origOrdNo={} code={}", origOrdNo, stkCd);
      return OrderResult.failed(-1, null);
    }
    int rc = resp.path("return_code").asInt(-1);
    String ordNo = resp.path("ord_no").asText(null);
    String msg = resp.path("return_msg").asText("");
    if (rc != 0 || ordNo == null || ordNo.isBlank()) {
      log.warn("[주문] 취소 실패 — origOrdNo={} code={} rc={} msg={}", origOrdNo, stkCd, rc, msg);
      return OrderResult.failed(rc, msg);
    }
    log.info("[주문] 취소 접수 — origOrdNo={} code={} cnclOrdNo={} rc={}", origOrdNo, stkCd, ordNo, rc);
    return OrderResult.accepted(ordNo);
  }

  private OrderResult sendOrder(String apiId, String stkCd, int qty, Long ordUv,
      String trdeTp, String dmstStexTp) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("dmst_stex_tp", dmstStexTp);
    body.put("stk_cd", stkCd);
    body.put("ord_qty", String.valueOf(qty));
    body.put("ord_uv", ordUv != null ? String.valueOf(ordUv) : "");
    body.put("trde_tp", trdeTp);
    body.put("cond_uv", "");

    JsonNode resp = apiClient.call(apiId, body);   // 무재시도(비멱등) — call 내부 rate-limit 재시도만
    if (resp == null) {
      log.warn("[주문] 응답 null — api={} code={}", apiId, stkCd);
      return OrderResult.failed(-1, null);
    }
    int rc = resp.path("return_code").asInt(-1);
    String ordNo = resp.path("ord_no").asText(null);
    String msg = resp.path("return_msg").asText("");
    if (rc != 0 || ordNo == null || ordNo.isBlank()) {
      log.warn("[주문] 발주 실패 — api={} code={} rc={} msg={}", apiId, stkCd, rc, msg);
      return OrderResult.failed(rc, msg);
    }
    log.info("[주문] 발주 접수 — api={} code={} ordNo={} rc={}", apiId, stkCd, ordNo, rc);
    return OrderResult.accepted(ordNo);
  }

  /**
   * 체결내역 조회 (kt00007) — 발주 ord_no 로 체결 확인.
   *
   * <p>{@code qry_tp=4}(체결내역)·{@code sell_tp=0}(전체, Phase 1 매도 조회 지원으로 완화)·
   * {@code fr_ord_no=ordNo} 로 조회 → LIST {@code acnt_ord_cntr_prps_dtl} 에서 ord_no 매칭행의
   * cntr_qty·cntr_uv·ord_remnq 파싱. ord_no 가 이미 유일키라 매수(autobuy) 조회 회귀 없음.
   *
   * @return 체결 상태. 조회 실패·미체결 시 {@link FillStatus#none()}.
   */
  public FillStatus fills(String ordNo, String dmstStexTp) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("qry_tp", "4");
    body.put("stk_bond_tp", "0");
    body.put("sell_tp", "0");
    body.put("fr_ord_no", ordNo);
    body.put("dmst_stex_tp", dmstStexTp);

    JsonNode resp = apiClient.call(TR_FILLS, body);
    if (resp == null) {
      log.warn("[주문] 체결조회 응답 null — ordNo={}", ordNo);
      return FillStatus.none();
    }
    JsonNode list = resp.path("acnt_ord_cntr_prps_dtl");
    if (!list.isArray()) {
      return FillStatus.none();
    }
    for (JsonNode row : list) {
      if (ordNo.equals(row.path("ord_no").asText(null))) {
        return toFillStatus(row);
      }
    }
    return FillStatus.none();
  }

  /**
   * 계좌 미체결 주문 전체 조회 (ka10075) — PR-5 타임아웃 멱등에서 재발주 전 선확인 용도.
   *
   * <p>특정 종목 한정 조회: {@code all_stk_tp=1, stk_cd=itemCode}.<br>
   * 전체 조회: itemCode=null 로 호출 ({@code all_stk_tp=0}).
   *
   * @param itemCode 종목코드(null=전체), stex_tp=0(통합)
   * @return 미체결 주문 목록. 조회 실패 시 빈 리스트.
   */
  public List<OpenOrder> fetchOpenOrders(String itemCode) {
    Map<String, Object> body = new LinkedHashMap<>();
    if (itemCode != null && !itemCode.isBlank()) {
      body.put("all_stk_tp", "1");
      body.put("stk_cd", itemCode);
    } else {
      body.put("all_stk_tp", "0");
    }
    body.put("trde_tp", "0");   // 전체(매수+매도)
    body.put("stex_tp", "0");   // 통합

    JsonNode resp = apiClient.call(TR_OPEN_ORDERS, body);
    if (resp == null) {
      log.warn("[주문] 미체결조회 응답 null — itemCode={}", itemCode);
      return List.of();
    }
    JsonNode list = resp.path("oso");
    if (!list.isArray()) {
      return List.of();
    }
    List<OpenOrder> result = new ArrayList<>();
    for (JsonNode row : list) {
      result.add(toOpenOrder(row));
    }
    return result;
  }

  private static OpenOrder toOpenOrder(JsonNode row) {
    String rawCode = row.path("stk_cd").asText("");
    String itemCode = KiwoomNt2NumberParser.stripItemCodePrefix(rawCode);
    return new OpenOrder(
        row.path("ord_no").asText(""),
        itemCode,
        row.path("stk_nm").asText(""),
        row.path("ord_qty").asInt(0),
        row.path("ord_pric").asLong(0L),
        row.path("oso_qty").asInt(0),
        row.path("orig_ord_no").asText(""),
        row.path("trde_tp").asText(""),
        row.path("ord_stt").asText("")
    );
  }

  /**
   * 일자별 체결내역 조회 (kt00007) — {@code ord_dt}(주문일자) + {@code qry_tp=4}(체결) + {@code stk_cd} 조합.
   *
   * <p>실측 근거(2026-07-15): {@code ord_dt=20260714, stk_cd=000660, sell_tp=1} → return_code=0, 1건.
   * 당일 세션 한정인 ka10076/ka10077 과 달리 <b>과거 일자 조회 가능</b>.
   * {@code acnt_ord_cntr_prps_dtl} 배열 전체 반환 — 트런치 분할 주의(가중평균 필요).
   *
   * <p>⚠️ {@link FillSide#ALL} 은 매수·매도가 섞여 반환되므로 <b>평균가 산출에 쓰지 말 것</b>.
   * 방향별 집계가 필요하면 {@link FillSide#SELL}/{@link FillSide#BUY} 로 조회하거나
   * {@link FillRow#ioTpNm()} 을 {@link FillSide#matches(String)} 로 필터링한다.
   *
   * @param stkCd 종목코드
   * @param ordDt 주문일자 (YYYYMMDD)
   * @param side  조회 방향 ({@link FillSide#SELL}/{@link FillSide#BUY}/{@link FillSide#ALL})
   * @return 체결행 목록. 조회 실패·0건 시 빈 리스트.
   */
  public List<FillRow> fillsByDate(String stkCd, String ordDt, FillSide side) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("qry_tp", "4");
    body.put("stk_bond_tp", "1");
    body.put("sell_tp", side.code());
    body.put("ord_dt", ordDt);
    body.put("stk_cd", stkCd);
    body.put("dmst_stex_tp", "%");

    JsonNode resp = apiClient.call(TR_FILLS, body);
    if (resp == null) {
      log.warn("[주문] 일자별 체결조회 응답 null — stkCd={} ordDt={}", stkCd, ordDt);
      return List.of();
    }
    JsonNode list = resp.path("acnt_ord_cntr_prps_dtl");
    if (!list.isArray()) return List.of();

    List<FillRow> result = new ArrayList<>();
    for (JsonNode row : list) {
      result.add(toFillRow(row));
    }
    return result;
  }

  private static FillRow toFillRow(JsonNode row) {
    return new FillRow(
        row.path("ord_no").asText(""),
        row.path("cntr_uv").asLong(0L),
        row.path("cntr_qty").asInt(0),
        row.path("io_tp_nm").asText(""),
        row.path("ord_tm").asText(""),
        row.path("dmst_stex_tp").asText(""));
  }

  private static FillStatus toFillStatus(JsonNode row) {
    int cntrQty = row.path("cntr_qty").asInt(0);
    long cntrUv = row.path("cntr_uv").asLong(0L);
    int remnq = row.path("ord_remnq").asInt(-1);
    boolean full = remnq == 0 && cntrQty > 0;
    return new FillStatus(cntrQty, cntrUv, Math.max(remnq, 0), full);
  }
}
