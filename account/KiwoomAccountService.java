package com.nexttheme.stock.collector;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexttheme.stock.core.account.AccountBalance;
import com.nexttheme.stock.core.account.AccountEvaluation;
import com.nexttheme.stock.core.account.AccountSnapshot;
import com.nexttheme.stock.core.account.AccountTodayStatus;
import com.nexttheme.stock.core.account.DepositDetail;
import com.nexttheme.stock.core.account.HoldingStock;
import com.nexttheme.stock.core.account.PeriodReturn;
import com.nexttheme.stock.nt2.aux.KiwoomNt2NumberParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 키움 계좌현황 조회 서비스 (읽기 전용, Scope 1).
 *
 * <p>4종 TR(kt00018·kt00001·kt00004·kt00017) 조합 → {@link AccountSnapshot} 반환.
 * DB 저장 없음. {@link KiwoomApiClient#call()} 재사용 (토큰·rate limit·재시도 포함).
 * kt00018 은 단일 call() 로 총계(AccountBalance) + 보유목록(List&lt;HoldingStock&gt;) 동시 파싱.
 *
 * <p>계좌번호는 appkey(토큰) 귀속 — 요청 body에 미포함.
 * dmst_stex_tp: 정규장(09:00~15:30)=KRX, 그 외(프리·애프터·야간)=NXT — {@link KiwoomDataService#isKrxRegularHours()} SSoT.
 * fetchHoldings(매도 수량 재확인)는 NXT 목록필터 리스크 방지를 위해 KRX 고정(설계서 §3.3).
 *
 * <p>보안: 운영 INFO 레벨에서 계좌 잔고 숫자 미출력. DEBUG 활성 시 kt접두 TR 마스킹 권고.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KiwoomAccountService {

    private final KiwoomApiClient kiwoomApiClient;

    /**
     * 계좌 현황 전체 조회 (kt00018 + kt00001 + kt00004 + kt00017) — 사이징 경로 기본 호출.
     *
     * <p>/positions 는 2026-07-31 제거. 반자동매수 사이징 경로(resolveBudget 등 3곳)는 이 무인자
     * 오버로드를 그대로 사용 — {@link #fetchBalance(boolean)}(false)로 위임되어 신규 TR 호출 없음.
     */
    public AccountSnapshot fetchBalance() {
        return fetchBalance(false);
    }

    /**
     * 계좌 현황 전체 조회. 4종 TR 중 1종 이상 null 시 부분 실패 카드 발송.
     * 전체 null 시 empty() 반환 — 호출부에서 별도 "전체 실패" 메시지 발송 책임.
     *
     * @param includeDisplayExtras true면 당일 실현손익 정정(§3)·기간손익(§5) 추가 조회.
     *   /balance 카드 전용 — 반자동매수 사이징 경로(resolveBudget 등 3곳)는 false로 호출해
     *   기존 API 호출 횟수·지연을 그대로 유지한다(2026-07-31 1단계 검수 반영).
     * @return AccountSnapshot (부분 실패 시 isPartialFailure()=true)
     */
    public AccountSnapshot fetchBalance(boolean includeDisplayExtras) {
        log.info("[계좌] fetchBalance 시작");
        String exchange = resolveAccountExchange();   // 이 조회에 실제 사용한 거래소(카드 라벨용)
        Kt00018Result kt00018 = fetchKt00018(exchange);
        DepositDetail deposit = fetchDeposit();
        AccountEvaluation eval = fetchEvaluation();
        AccountTodayStatus today = fetchTodayStatus();
        PeriodReturn month = null;
        PeriodReturn year = null;
        if (includeDisplayExtras) {
            eval = applyRealizedPnlIfTraded(eval, today);
            LocalDate now = LocalDate.now();
            month = fetchPeriodReturn(now.withDayOfMonth(1), now);
            year = fetchPeriodReturn(now.minusYears(1), now);
        }
        AccountSnapshot snapshot = new AccountSnapshot(
            kt00018.balance(), kt00018.holdings(), deposit, eval, today, exchange, month, year);
        if (snapshot.isPartialFailure()) {
            log.warn("[계좌] 일부 TR 조회 실패 — balance={} deposit={} eval={} today={}",
                kt00018.balance() != null, deposit != null, eval != null, today != null);
        }
        log.info("[계좌] fetchBalance 완료 — 보유 {}종목",
            kt00018.ok() ? kt00018.holdings().size() : 0);
        return snapshot;
    }

    /** kt00004.tdy_lspft 는 당일 매도 실현손익을 반영 못 함(2026-07-31 실측, 설계서 §1) —
      * 당일 매도가 있을 때만 ka10076+ka10077 로 재계산해 대체한다. */
    private AccountEvaluation applyRealizedPnlIfTraded(AccountEvaluation eval, AccountTodayStatus today) {
        if (eval == null) {
            return eval;
        }
        if (today == null) {
            // kt00017 자체 조회 실패 — 매도 유무를 확인할 수 없어 kt00004 원본을 그대로 쓰지만,
            // 그 값은 신뢰 불가(§1)일 수 있으므로 조용히 넘어가지 않고 로그를 남긴다(4단계 audit G1 반영).
            log.warn("[계좌] kt00017 조회 실패 — 당일 실현손익 재계산 여부 판단 불가, kt00004 원본값 그대로 표시");
            return eval;
        }
        if (today.sellAmt() <= 0) {
            return eval;   // 매도 없음 — kt00004.tdy_lspft(=0)가 이미 정확하다(§3.1)
        }
        long realized = fetchTodayRealizedPnl();
        return new AccountEvaluation(eval.prsmDpstAsetAmt(), realized, eval.tdyLspftRt(),
            eval.lspft(), eval.lspftRt());
    }

    /** ka10076(당일 체결목록)에서 종목 발견 → ka10077(종목별 net 실현손익) 순회 합산.
      * ⚠️ 이 메서드는 today.sellAmt()>0 확정 후에만 호출된다 — 반환값 0은 반드시 "조회 실패"이지
      * "매도 없음"일 수 없다(그 케이스는 상위에서 이미 걸러짐). 그래서 null 응답은 반드시 로그로 남긴다
      * (2단계 plan-eng-review §5.9 반영 — 그렇지 않으면 이 0이 §1의 원래 버그와 구분 불가). */
    private long fetchTodayRealizedPnl() {
        JsonNode fills = kiwoomApiClient.call("ka10076", Map.of("qry_tp", "0", "sell_tp", "0", "stex_tp", "0"));
        if (fills == null) {
            log.warn("[계좌] ka10076 조회 실패 — 당일 실현손익 0으로 표시되나 매도 없음이 아니라 조회 실패임");
            return 0L;
        }
        Set<String> codes = new LinkedHashSet<>();
        for (JsonNode f : fills.path("cntr")) {
            String code = f.path("stk_cd").asText(null);
            if (code != null && !code.isBlank()) codes.add(code);
        }
        if (codes.isEmpty()) {
            // sellAmt()>0 확정 후 호출인데 종목이 0개 — ka10076 응답 파싱 실패 가능성(4단계 audit G1 R1 반영).
            log.warn("[계좌] ka10076 응답에서 매도 종목 미발견(sellAmt>0인데 cntr 매칭 0건) — 당일 실현손익 0 표시, 조회 이상 의심");
            return 0L;
        }
        long total = 0L;
        for (String code : codes) {
            JsonNode r = kiwoomApiClient.call("ka10077", Map.of("stk_cd", code));
            if (r == null) {
                log.warn("[계좌] ka10077 조회 실패 — code={} 이 종목 실현손익 누락(과소집계 가능)", code);
                continue;
            }
            total += parseLong(r, "tdy_rlzt_pl");
        }
        return total;
    }

    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.BASIC_ISO_DATE;

    /** kt00016 — 기간 손익. 실패해도 null 반환(카드에서 해당 줄만 생략, §5.7). */
    private PeriodReturn fetchPeriodReturn(LocalDate from, LocalDate to) {
        JsonNode root = kiwoomApiClient.call("kt00016",
            Map.of("fr_dt", from.format(YYYYMMDD), "to_dt", to.format(YYYYMMDD)));
        if (root == null) return null;
        return new PeriodReturn(parseLong(root, "evltv_prft"), parseDouble(root, "prft_rt"),
            parseLong(root, "invt_bsamt"));
    }

    /**
     * kt00018 보유목록만 조회 — kt00001/kt00004/kt00017 3종 TR 생략(경량, 2026-07-09 audit M1).
     *
     * <p>{@link #fetchBalance()}는 잔고카드용으로 4종 TR을 전부 묶어 호출하나, 매도 직전
     * 실잔고(trde_able_qty) 재확인처럼 보유목록만 필요한 호출부는 이 메서드로 API 3콜을 절감한다.
     *
     * <p>"실보유 0"(빈 List 포함 present)과 조회 실패(Optional.empty)를 구분한다.
     * 조회 실패 시 예외가 아닌 {@code Optional.empty()}를 반환한다 — 호출부에서 실패 구분 필수.
     */
    public Optional<List<HoldingStock>> fetchHoldings() {
        Kt00018Result r = fetchKt00018("KRX");
        return r.ok() ? Optional.of(r.holdings()) : Optional.empty();
    }

    /**
     * kt00018 단일 호출 — 총계(AccountBalance) + 보유목록(List&lt;HoldingStock&gt;) 동시 파싱.
     * 2회 호출 비효율 방지.
     */
    private Kt00018Result fetchKt00018(String exchange) {
        JsonNode root = kiwoomApiClient.call("kt00018",
            Map.of("qry_tp", "1", "dmst_stex_tp", exchange));
        if (root == null) {
            return Kt00018Result.fail();
        }
        AccountBalance balance = new AccountBalance(
            parseLong(root, "tot_pur_amt"),
            parseLong(root, "tot_evlt_amt"),
            parseLong(root, "tot_evlt_pl"),
            parseDouble(root, "tot_prft_rt"),
            parseLong(root, "prsm_dpst_aset_amt")
        );
        JsonNode arr = root.path("acnt_evlt_remn_indv_tot");
        List<HoldingStock> holdings = new ArrayList<>();
        if (arr.isArray()) {
            for (JsonNode node : arr) {
                holdings.add(parseHoldingStock(node));
            }
        }
        return new Kt00018Result(true, balance, holdings);
    }

    private DepositDetail fetchDeposit() {
        JsonNode root = kiwoomApiClient.call("kt00001", Map.of("qry_tp", "3"));
        if (root == null) return null;
        return new DepositDetail(
            parseLong(root, "entr"),
            parseLong(root, "ord_alow_amt"),
            parseLong(root, "100stk_ord_alow_amt"),
            parseLong(root, "d2_entra"),
            parseLong(root, "pymn_alow_amt"),
            parseLong(root, "ch_uncla")   // 현금미수금 failsafe — 미수 미사용 계좌=0
        );
    }

    private AccountEvaluation fetchEvaluation() {
        JsonNode root = kiwoomApiClient.call("kt00004",
            Map.of("qry_tp", "0", "dmst_stex_tp", resolveAccountExchange()));
        if (root == null) return null;
        return new AccountEvaluation(
            parseLong(root, "prsm_dpst_aset_amt"),
            parseLong(root, "tdy_lspft"),
            parseDouble(root, "tdy_lspft_rt"),
            parseLong(root, "lspft"),
            parseDouble(root, "lspft_rt")
        );
    }

    private AccountTodayStatus fetchTodayStatus() {
        JsonNode root = kiwoomApiClient.call("kt00017", Map.of());
        if (root == null) return null;
        return new AccountTodayStatus(
            parseLong(root, "buy_amt"),
            parseLong(root, "sell_amt"),
            parseLong(root, "cmsn"),
            parseLong(root, "tax")
        );
    }

    private HoldingStock parseHoldingStock(JsonNode node) {
        return new HoldingStock(
            KiwoomNt2NumberParser.stripItemCodePrefix(node.path("stk_cd").asText()),
            node.path("stk_nm").asText(),
            parseLongNode(node, "rmnd_qty"),
            parseLongNode(node, "trde_able_qty"),
            parseLongNode(node, "pur_pric"),
            parseLongNode(node, "cur_prc"),
            parseLongNode(node, "evltv_prft"),
            parseDoubleNode(node, "prft_rt"),
            parseLongNode(node, "pur_amt"),
            parseLongNode(node, "evlt_amt"),
            parseDoubleNode(node, "poss_rt")
        );
    }

    private static long parseLong(JsonNode root, String key) {
        return KiwoomNt2NumberParser.parseSignedLong(root.path(key).asText("0"));
    }

    private static double parseDouble(JsonNode root, String key) {
        return KiwoomNt2NumberParser.parseSignedDouble(root.path(key).asText("0"));
    }

    private static long parseLongNode(JsonNode node, String key) {
        return KiwoomNt2NumberParser.parseSignedLong(node.path(key).asText("0"));
    }

    private static double parseDoubleNode(JsonNode node, String key) {
        return KiwoomNt2NumberParser.parseSignedDouble(node.path(key).asText("0"));
    }

    /** 정규장(09:00~15:30)=KRX, 그 외=NXT — 카드 표시용. */
    private static String resolveAccountExchange() {
        return KiwoomDataService.isKrxRegularHours() ? "KRX" : "NXT";
    }

    /** kt00018 파싱 결과 내부 캐리어 — 총계+목록 동시 반환용. ok=false 시 실패(빈 목록과 구분). */
    private record Kt00018Result(boolean ok, AccountBalance balance, List<HoldingStock> holdings) {
        static Kt00018Result fail() { return new Kt00018Result(false, null, List.of()); }
    }
}
