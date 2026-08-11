package com.nexttheme.stock.collector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexttheme.stock.core.config.KiwoomProperties;
import com.nexttheme.stock.core.logging.ApiFileLogger;
import com.nexttheme.stock.notification.TelegramService;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Optional;
import java.util.Spliterators;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.StreamSupport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * 키움 REST API 클라이언트 (토큰 관리 + TR별 URL 라우팅 + 글로벌 Rate Limiter).
 *
 * <p>주요 책임:
 * <ul>
 *   <li>TR별 URL 라우팅: {@code TR_PATH_MAP}으로 apiId → 올바른 API 경로 매핑
 *       (stkinfo/mrkcond/chart/rkinfo/shsa/frgnistt/sect/thme)</li>
 *   <li>Lazy 토큰 발급: 첫 call() 시 자동 발급, 만료 5분 전 갱신</li>
 *   <li>글로벌 Rate Limit: ReentrantLock 기반, 200ms 간격 = 초당 5회 제한</li>
 *   <li>Semaphore: max-concurrency 범위 내 동시 요청(in-flight) 제어</li>
 *   <li>에러 코드별 분기: 1687/1700 백오프 재시도, 8005/8010 토큰 재발급</li>
 *   <li>연속조회 헤더 패스스루 (루프 미구현 — Scope 2)</li>
 * </ul>
 *
 * <p>{@link KiwoomProperties#enabled()} = false이면 모든 call()이 null을 반환한다.
 * 활성 상태의 모든 호출 실패(HTTP·타임아웃·토큰 발급 실패 포함)는 {@code null} 반환으로 통일하며
 * 예외를 전파하지 않는다. {@link KiwoomDataService}가 결과 맵에서 제외하거나 빈 객체로 보존하여
 * 후속 단계로 흘려보낸다 (네이버 폴백 폐지, 2026-04-17). enabled=false 시에만 호출부에서 네이버 폴백.
 */
@Slf4j
@Component
public class KiwoomApiClient {

  private static final String TOKEN_URL = "/oauth2/token";
  private static final int MAX_RETRY = 3;
  private static final long[] BACKOFF_MS = {200L, 500L, 1000L};

  // 점검 페이지 정규식 패턴 (BUG-K4, 2026-05-23)
  private static final Pattern MAINT_PERIOD = Pattern.compile(
      "<h2>1\\.\\s*중단\\s*일시</h2>\\s*<p><b>([^<]+)</b>");
  private static final Pattern MAINT_REASON = Pattern.compile(
      "<h2>2\\.\\s*중단\\s*사유</h2>\\s*<p>([^<]+)</p>");
  private static final Pattern MAINT_CONTENT = Pattern.compile(
      "<h2>3\\.\\s*중단\\s*내용</h2>\\s*<p>([^<]+)</p>");

  /** TR 코드 → API URL 경로 매핑 (키움 REST API 문서 기준). */
  private static final Map<String, String> TR_PATH_MAP = Map.ofEntries(
      // 종목 정보: /api/dostk/stkinfo
      Map.entry("ka10001", "/api/dostk/stkinfo"),
      Map.entry("ka10003", "/api/dostk/stkinfo"),
      Map.entry("ka10015", "/api/dostk/stkinfo"),
      Map.entry("ka10016", "/api/dostk/stkinfo"),
      Map.entry("ka10054", "/api/dostk/stkinfo"),
      Map.entry("ka10059", "/api/dostk/stkinfo"),
      // 시세/호가: /api/dostk/mrkcond
      Map.entry("ka10004", "/api/dostk/mrkcond"),
      Map.entry("ka10046", "/api/dostk/mrkcond"),
      Map.entry("ka10047", "/api/dostk/mrkcond"),
      Map.entry("ka10063", "/api/dostk/mrkcond"),
      Map.entry("ka10086", "/api/dostk/mrkcond"),
      Map.entry("ka10087", "/api/dostk/mrkcond"),
      // 차트: /api/dostk/chart
      Map.entry("ka10079", "/api/dostk/chart"),
      Map.entry("ka10080", "/api/dostk/chart"),
      Map.entry("ka10081", "/api/dostk/chart"),
      Map.entry("ka10082", "/api/dostk/chart"),
      Map.entry("ka10083", "/api/dostk/chart"),
      Map.entry("ka10060", "/api/dostk/chart"),
      // 순위: /api/dostk/rkinfo
      Map.entry("ka10023", "/api/dostk/rkinfo"),
      Map.entry("ka10027", "/api/dostk/rkinfo"),
      Map.entry("ka10030", "/api/dostk/rkinfo"),
      Map.entry("ka10031", "/api/dostk/rkinfo"),
      Map.entry("ka10032", "/api/dostk/rkinfo"),
      Map.entry("ka10034", "/api/dostk/rkinfo"),
      Map.entry("ka10040", "/api/dostk/rkinfo"),
      Map.entry("ka10042", "/api/dostk/rkinfo"),
      Map.entry("ka10044", "/api/dostk/mrkcond"),
      Map.entry("ka10045", "/api/dostk/rkinfo"),
      Map.entry("ka10065", "/api/dostk/rkinfo"),
      Map.entry("ka00198", "/api/dostk/stkinfo"),
      // 외인/기관: /api/dostk/frgnistt
      Map.entry("ka10008", "/api/dostk/frgnistt"),
      Map.entry("ka10009", "/api/dostk/frgnistt"),
      // 공매도: /api/dostk/shsa
      Map.entry("ka10014", "/api/dostk/shsa"),
      // 업종/지수: /api/dostk/sect
      Map.entry("ka10051", "/api/dostk/sect"),
      Map.entry("ka20001", "/api/dostk/sect"),
      Map.entry("ka20003", "/api/dostk/sect"),
      // 테마: /api/dostk/thme
      Map.entry("ka90001", "/api/dostk/thme"),
      Map.entry("ka90002", "/api/dostk/thme"),
      // 프로그램매매 (시세): /api/dostk/mrkcond — 공식 문서 p.363(ka90008) / p.374(ka90013)
      Map.entry("ka90005", "/api/dostk/mrkcond"),
      Map.entry("ka90008", "/api/dostk/mrkcond"),
      Map.entry("ka90013", "/api/dostk/mrkcond"),
      // 프로그램매매 (종목정보): /api/dostk/stkinfo — p.351(ka90003) / p.353(ka90004)
      // DEFAULT_API_PATH와 동일하나 가독성·향후 라우팅 변경 대비 명시 (설계서 v1.3 §9.2)
      Map.entry("ka90003", "/api/dostk/stkinfo"),
      Map.entry("ka90004", "/api/dostk/stkinfo"),
      // 계좌 조회: /api/dostk/acnt (Scope 1 읽기 전용)
      Map.entry("kt00018", "/api/dostk/acnt"),
      Map.entry("kt00001", "/api/dostk/acnt"),
      Map.entry("kt00004", "/api/dostk/acnt"),
      Map.entry("kt00017", "/api/dostk/acnt"),
      Map.entry("kt00016", "/api/dostk/acnt"),  // 일별계좌수익률상세 (기간 손익, PR #929)
      // 계좌별 주문체결내역 (반자동 매수 체결 확인): /api/dostk/acnt
      Map.entry("kt00007", "/api/dostk/acnt"),
      Map.entry("kt00009", "/api/dostk/acnt"),
      // 주문 상태 전용 조회 (PR-1): /api/dostk/acnt
      Map.entry("ka10075", "/api/dostk/acnt"),  // 미체결요청
      Map.entry("ka10076", "/api/dostk/acnt"),  // 체결요청
      Map.entry("ka10077", "/api/dostk/acnt"),  // 당일실현손익상세
      // 주문 발주: /api/dostk/ordr
      Map.entry("kt10000", "/api/dostk/ordr"),
      Map.entry("kt10001", "/api/dostk/ordr"),
      Map.entry("kt10002", "/api/dostk/ordr"),
      Map.entry("kt10003", "/api/dostk/ordr")
  );

  /** 매핑에 없는 TR은 기본 경로 사용 (안전장치). */
  private static final String DEFAULT_API_PATH = "/api/dostk/stkinfo";

  /**
   * executeRequest .block() 백스톱 타임아웃 — ReadTimeoutHandler(10s)가 미발화할 경우
   * (WiFi 전환 등 네트워크 경로 변경 시 소켓이 TCP_ESTABLISHED로 남아 idle 미감지) 를 방어.
   * ReadTimeout(10s)보다 2초 여유를 둬 Netty 핸들러가 먼저 처리하도록 한다.
   */
  private static final Duration EXECUTE_BLOCK_TIMEOUT = Duration.ofSeconds(12);

  private final KiwoomProperties props;
  private final WebClient webClient;
  private final ObjectMapper objectMapper;
  private final Semaphore semaphore;
  private final AtomicReference<KiwoomToken> tokenRef = new AtomicReference<>();

  /** 글로벌 호출 간격 제어 — ReentrantLock으로 가상 스레드 carrier pinning 방지. */
  private final ReentrantLock rateLimitLock = new ReentrantLock();
  /** 토큰 갱신 직렬화 — synchronized 대신 ReentrantLock으로 blocking I/O carrier pinning 방지. */
  private final ReentrantLock tokenLock = new ReentrantLock();
  private volatile long lastCallEpochMs = 0;

  /**
   * 점검 응답 감지 시 토큰 발급 시도를 일시 차단할 만료 시각 (BUG-K2, 2026-05-23).
   *
   * <p>점검 종료까지 매번 3회 재시도하는 낭비를 막는다. 만료 후 자동으로 재시도 — 점검 종료를 자연 감지.
   * 기본 백오프: {@link KiwoomProperties#tokenMaintenanceBackoffMinutes()} (yml 기본 5분).
   */
  private final AtomicReference<LocalDateTime> tokenIssueBlockedUntil = new AtomicReference<>();

  /** 점검 페이지에서 추출한 마지막 안내 (텔레그램 알림 중복 방지용 키). */
  private final AtomicReference<String> lastMaintenanceNoticeKey = new AtomicReference<>();

  /** API 호출 이력 파일 기록 (P4) */
  @Autowired(required = false)
  private ApiFileLogger apiFileLogger;

  /** 점검 시작/종료 텔레그램 알림 (BUG-K3, 2026-05-23). null-safe — 테스트 환경 등에서 미주입 허용. */
  @Autowired(required = false)
  private TelegramService telegramService;

  public KiwoomApiClient(
      KiwoomProperties kiwoomProperties,
      @Qualifier("kiwoomWebClient") WebClient kiwoomWebClient,
      ObjectMapper objectMapper) {
    this.props = kiwoomProperties;
    this.webClient = kiwoomWebClient;
    this.objectMapper = objectMapper;
    this.semaphore = new Semaphore(kiwoomProperties.maxConcurrency());
  }

  /**
   * 키움 REST API 호출.
   *
   * <p>키움 비활성화({@code enabled=false}) 시 null 반환.
   * rate limit 초과(1687/1700) 시 지수 백오프 재시도.
   * 토큰 만료(8005/8010) 시 재발급 후 재시도.
   *
   * @param apiId TR 코드 (예: "ka10004")
   * @param body  요청 파라미터 Map
   * @return 응답 JSON (실패 시 null)
   */
  public JsonNode call(String apiId, Map<String, Object> body) {
    if (!props.enabled()) {
      return null;
    }
    return callWithRetry(apiId, body, null, MAX_RETRY);
  }

  /**
   * 연속조회 지원 call.
   *
   * @param apiId   TR 코드
   * @param body    요청 파라미터 Map
   * @param nextKey 연속조회 키 (null이면 첫 페이지)
   * @return 응답 JSON (실패 시 null)
   */
  public JsonNode callContinuous(String apiId, Map<String, Object> body, String nextKey) {
    if (!props.enabled()) {
      return null;
    }
    return callWithRetry(apiId, body, nextKey, MAX_RETRY);
  }

  /**
   * 키움 API 점검 중 여부 확인 (BUG-K5, 2026-05-23).
   *
   * <p>{@code tokenIssueBlockedUntil} 가 미래 시각이면 점검 진행 중으로 판정. 모든 키움 의존 호출지 진입부에서
   * 호출하여 작업 자체를 skip한다. 점검 종료 후엔 자동 false.
   *
   * @return true면 점검 중 (호출지는 즉시 skip 해야 함)
   */
  public boolean isMaintenanceActive() {
    LocalDateTime blocked = tokenIssueBlockedUntil.get();
    return blocked != null && LocalDateTime.now().isBefore(blocked);
  }

  /**
   * 점검 정보 조회 — 운영자 알림 메시지 빌드용.
   *
   * @return 점검 일시 문자열(period). 점검 아님이면 empty.
   */
  public Optional<String> currentMaintenancePeriod() {
    if (!isMaintenanceActive()) {
      return Optional.empty();
    }
    String key = lastMaintenanceNoticeKey.get();
    if (key == null) {
      return Optional.empty();
    }
    // signatureKey = "period|reason" 에서 period 만 추출
    int idx = key.indexOf('|');
    return Optional.of(idx > 0 ? key.substring(0, idx) : key);
  }

  private JsonNode callWithRetry(String apiId, Map<String, Object> body,
      String nextKey, int maxRetry) {
    for (int attempt = 0; attempt <= maxRetry; attempt++) {
      KiwoomToken token = resolveToken(apiId);
      if (token == null) return null;
      if (!tryAcquireSemaphore(apiId)) return null;
      long startMs = System.currentTimeMillis();
      try {
        acquireRateSlot();
        String responseBody = executeRequest(apiId, body, nextKey, token.accessToken());
        JsonNode root = objectMapper.readTree(responseBody);
        int returnCode = root.path("return_code").asInt(-1);
        String returnMsg = root.path("return_msg").asText("");
        if (returnCode == 0) {
          logRawDebug(apiId, body, root, responseBody);
          logKiwoomSuccess(apiId, System.currentTimeMillis() - startMs);
          return root;
        }
        if (shouldRetry(returnCode, returnMsg, apiId, attempt, maxRetry)) continue;
        log.warn("[키움] API 오류 return_code={} msg={} apiId={}", returnCode, returnMsg, apiId);
        logKiwoomError(apiId, System.currentTimeMillis() - startMs, "return_code=" + returnCode);
        return null;
      } catch (WebClientResponseException e) {
        log.warn("[키움] HTTP 오류 status={} apiId={}", e.getStatusCode(), apiId);
        logKiwoomError(apiId, System.currentTimeMillis() - startMs, "HTTP " + e.getStatusCode());
        return null;
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return null;
      } catch (Exception e) {
        log.warn("[키움] 호출 실패 apiId={} err={}", apiId, e.getMessage(), e);
        logKiwoomError(apiId, System.currentTimeMillis() - startMs, e.getMessage());
        return null;
      } finally {
        semaphore.release();
      }
    }
    return null;
  }

  private void logKiwoomSuccess(String apiId, long elapsedMs) {
    if (apiFileLogger != null) {
      apiFileLogger.log("kiwoom", apiId, 200, elapsedMs, "성공");
    }
  }

  private void logKiwoomError(String apiId, long elapsedMs, String reason) {
    if (apiFileLogger != null) {
      String detail = reason != null && reason.length() > 80 ? reason.substring(0, 80) : reason;
      apiFileLogger.log("kiwoom", apiId, 0, elapsedMs, detail != null ? detail : "오류");
    }
  }

  private KiwoomToken resolveToken(String apiId) {
    ensureToken();
    KiwoomToken token = tokenRef.get();
    if (token == null) {
      log.warn("[키움] 토큰 없음 — 호출 스킵: apiId={}", apiId);
    }
    return token;
  }

  private boolean tryAcquireSemaphore(String apiId) {
    try {
      semaphore.acquire();
      return true;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      log.warn("[키움] Semaphore 대기 인터럽트: apiId={}", apiId);
      return false;
    }
  }

  /** 성공(returnCode=0) 응답에 한해 DEBUG 레벨 RAW 로그 출력. 진단 시 logback에서 DEBUG 활성화. */
  private void logRawDebug(String apiId, Map<String, Object> body, JsonNode root, String responseBody) {
    if (!log.isDebugEnabled()) return;
    String preview = responseBody.length() > 1500
        ? responseBody.substring(0, 1500) + "..."
        : responseBody;
    log.debug("[키움 RAW] apiId={} req={} fieldNames={} body={}",
        apiId, body,
        StreamSupport.stream(Spliterators.spliteratorUnknownSize(root.fieldNames(), 0), false).toList(),
        preview);
  }

  /**
   * rate limit(1687/1700) 또는 토큰 무효(8005/8010) 시 재시도 가능 여부 판단.
   * true 반환 시 루프 재시도, false 반환 시 중단.
   * rate limit 재시도 시 Thread.sleep — InterruptedException 은 callWithRetry catch 로 전파.
   */
  private boolean shouldRetry(int returnCode, String returnMsg, String apiId,
      int attempt, int maxRetry) throws InterruptedException {
    if ((returnCode == 1687 || returnCode == 1700) && attempt < maxRetry) {
      long backoffMs = BACKOFF_MS[Math.min(attempt, BACKOFF_MS.length - 1)];
      log.warn("[키움] rate limit({}), {}ms 백오프. apiId={} attempt={}",
          returnCode, backoffMs, apiId, attempt);
      Thread.sleep(backoffMs);
      return true;
    }
    // return_code가 8005/8010으로 직접 오는 경우와 return_code=3 + msg 내 에러코드 포함 케이스 모두 처리
    boolean isTokenInvalid = returnCode == 8005 || returnCode == 8010
        || returnMsg.contains("8005") || returnMsg.contains("8010");
    if (isTokenInvalid && attempt < maxRetry) {
      log.warn("[키움] 토큰 무효(code={} msg={}), 재발급 후 재시도. apiId={}", returnCode, returnMsg, apiId);
      tokenRef.set(null);
      return true;
    }
    return false;
  }

  private String executeRequest(String apiId, Map<String, Object> body, String nextKey, String accessToken) {
    ObjectNode requestBody = objectMapper.valueToTree(body);
    String apiPath = resolveApiPath(apiId);

    return webClient.post()
        .uri(apiPath)
        .header("api-id", apiId)
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
        .header("cont-yn", nextKey != null ? "Y" : "N")
        .header("next-key", nextKey != null ? nextKey : "")
        .bodyValue(requestBody)
        .retrieve()
        .bodyToMono(String.class)
        .block(EXECUTE_BLOCK_TIMEOUT);
  }

  /** TR 코드 → API URL 경로 매핑. 미등록 TR은 기본 경로 + 경고 로그. */
  private static String resolveApiPath(String apiId) {
    String path = TR_PATH_MAP.get(apiId);
    if (path != null) {
      return path;
    }
    log.warn("[키움] TR 경로 미등록 — apiId={}, 기본 경로({}) 사용", apiId, DEFAULT_API_PATH);
    return DEFAULT_API_PATH;
  }

  /**
   * 글로벌 Rate Limiter — 모든 스레드 공유, 마지막 호출로부터 최소 requestDelayMs 간격 보장.
   *
   * <p>키움 REST API는 기존 COM API와 동일 백엔드를 공유하며,
   * COM API 기준 초당 5회 제한이 존재하므로 requestDelayMs=200 시 초당 5회로 제한된다.
   */
  private void acquireRateSlot() throws InterruptedException {
    int delayMs = props.requestDelayMs();
    if (delayMs <= 0) {
      return;
    }
    rateLimitLock.lock();
    try {
      long now = System.currentTimeMillis();
      long waitMs = delayMs - (now - lastCallEpochMs);
      if (waitMs > 0) {
        Thread.sleep(waitMs);
      }
      lastCallEpochMs = System.currentTimeMillis();
    } finally {
      rateLimitLock.unlock();
    }
  }

  // ────────────────────────────────────────────────────────────────
  //  Token Management (Lazy init + double-checked)
  // ────────────────────────────────────────────────────────────────

  private void ensureToken() {
    KiwoomToken current = tokenRef.get();
    if (current != null && !isExpiringSoon(current)) {
      return;
    }

    // BUG-K2 (2026-05-23): 점검 백오프 확인 — 만료 전까지 시도 자체 차단
    LocalDateTime blockedUntil = tokenIssueBlockedUntil.get();
    if (blockedUntil != null && LocalDateTime.now().isBefore(blockedUntil)) {
      log.debug("[키움] 토큰 발급 백오프 중 — until={}", blockedUntil);
      return;
    }

    tokenLock.lock();
    try {
      current = tokenRef.get();
      if (current != null && !isExpiringSoon(current)) {
        return;
      }
      KiwoomToken fresh = issueToken();
      if (fresh != null) {
        tokenRef.set(fresh);
        // 백오프 해제 + 점검 종료 알림
        if (tokenIssueBlockedUntil.getAndSet(null) != null) {
          notifyMaintenanceEnded();
        }
      }
    } finally {
      tokenLock.unlock();
    }
  }

  private boolean isExpiringSoon(KiwoomToken token) {
    return token.expiresAt().minusMinutes(props.tokenRefreshMarginMinutes())
        .isBefore(LocalDateTime.now());
  }

  private KiwoomToken issueToken() {
    String appKey = System.getenv("KIWOOM_APP_KEY");
    String secretKey = System.getenv("KIWOOM_SECRET_KEY");

    if (appKey == null || appKey.isBlank() || secretKey == null || secretKey.isBlank()) {
      log.warn("[키움] 토큰 발급 스킵 — KIWOOM_APP_KEY/SECRET_KEY 환경변수 미설정");
      return null;
    }

    for (int attempt = 0; attempt < MAX_RETRY; attempt++) {
      try {
        Map<String, String> tokenBody = Map.of(
            "appkey", appKey,
            "secretkey", secretKey,
            "grant_type", "client_credentials"
        );

        String responseBody = webClient.post()
            .uri(TOKEN_URL)
            .bodyValue(tokenBody)
            .retrieve()
            .bodyToMono(String.class)
            .block();

        // BUG-K1 (2026-05-23): HTML 응답(점검 페이지) 감지 → JsonParseException 회피
        if (isMaintenanceResponse(responseBody)) {
          handleMaintenanceDetected(responseBody);
          return null; // 추가 재시도 의미 없음 — 즉시 중단
        }

        JsonNode root = objectMapper.readTree(responseBody);
        String accessToken = root.path("token").asText(null);
        String expiresDt = root.path("expires_dt").asText(null);

        if (accessToken == null || accessToken.isBlank()) {
          log.warn("[키움] 토큰 발급 실패 — token 필드 없음. attempt={}", attempt);
          continue;
        }

        LocalDateTime expiresAt = parseExpiry(expiresDt);
        log.info("[키움] 토큰 발급 성공. expiresAt={}", expiresAt);
        return new KiwoomToken(accessToken, expiresAt);

      } catch (WebClientResponseException e) {
        // 응답 body에 appkey가 에코백될 수 있으므로 status만 기록
        log.warn("[키움] 토큰 발급 HTTP 오류 attempt={} status={}", attempt, e.getStatusCode());
      } catch (Exception e) {
        log.warn("[키움] 토큰 발급 오류 attempt={} err={}", attempt, e.getMessage(), e);
        if (attempt < MAX_RETRY - 1) {
          try {
            Thread.sleep(BACKOFF_MS[attempt]);
          } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return null;
          }
        }
      }
    }

    log.error("[키움] 토큰 발급 3회 실패 — 키움 API 비활성화 상태");
    return null;
  }

  private LocalDateTime parseExpiry(String expiresDt) {
    if (expiresDt == null || expiresDt.isBlank()) {
      return LocalDateTime.now().plusMinutes(30);
    }
    try {
      // 키움 API 응답 형식: "20261231235959"
      return LocalDateTime.parse(expiresDt, DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
    } catch (Exception e) {
      log.warn("[키움] expires_dt 파싱 실패 — 23시간 기본값 사용: {}", expiresDt);
      return LocalDateTime.now().plusMinutes(30);
    }
  }

  // ────────────────────────────────────────────────────────────────
  //  점검 감지·파싱·알림 헬퍼 (BUG-K1~K4, 2026-05-23)
  // ────────────────────────────────────────────────────────────────

  /**
   * HTML 응답 감지 — {@code <} 로 시작하면 JSON 아님 → 점검 또는 에러 페이지로 간주.
   *
   * <p>false positive: 502 Bad Gateway 등 HTML 에러 페이지도 점검으로 오판하나,
   * 5분 백오프 후 자동 재시도되므로 운영 영향 미미.
   */
  private static boolean isMaintenanceResponse(String body) {
    if (body == null) return false;
    String trimmed = body.trim();
    return !trimmed.isEmpty() && trimmed.charAt(0) == '<';
  }

  /**
   * 점검 응답 처리 — 점검 페이지 GET → 시각 파싱 → 텔레그램 알림.
   *
   * <p>백오프 활성 중에는 ensureToken() 진입부에서 early return하므로
   * issueToken() 자체가 호출되지 않음 — 이 메서드는 항상 첫 감지 컨텍스트에서만 호출됨.
   */
  private void handleMaintenanceDetected(String responseBody) {
    String headPreview = responseBody.trim().substring(0, Math.min(120, responseBody.length()));
    log.warn("[키움] 토큰 발급 — 점검 페이지 응답 감지 (HTML). 백오프 활성화. body_head={}", headPreview);

    MaintenanceNotice notice = fetchMaintenanceNotice();
    Duration backoff = Duration.ofMinutes(props.tokenMaintenanceBackoffMinutes());
    LocalDateTime blockedUntil = LocalDateTime.now().plus(backoff);
    tokenIssueBlockedUntil.set(blockedUntil);

    // 점검 안내 키 (period + reason) 가 동일하면 중복 알림 차단
    String noticeKey = notice != null ? notice.signatureKey() : "unknown";
    if (!noticeKey.equals(lastMaintenanceNoticeKey.getAndSet(noticeKey))) {
      notifyMaintenanceStarted(notice, blockedUntil);
    }
  }

  /** 점검 페이지 GET → 정규식으로 중단 일시·사유·내용 추출. 실패 시 null. */
  private MaintenanceNotice fetchMaintenanceNotice() {
    try {
      String html = webClient.get()
          .uri("http://api.kiwoom.com/start.html")
          .retrieve()
          .bodyToMono(String.class)
          .block(Duration.ofSeconds(3));
      if (html == null) return null;
      String period  = matchFirst(MAINT_PERIOD,  html);
      String reason  = matchFirst(MAINT_REASON,  html);
      String content = matchFirst(MAINT_CONTENT, html);
      if (period == null && reason == null && content == null) return null;
      return new MaintenanceNotice(period, reason, content);
    } catch (Exception e) {
      log.debug("[키움] 점검 페이지 파싱 실패 (계속 진행): {}", e.getMessage());
      return null;
    }
  }

  private static String matchFirst(Pattern p, String html) {
    Matcher m = p.matcher(html);
    return m.find() ? m.group(1).trim() : null;
  }

  private void notifyMaintenanceStarted(MaintenanceNotice notice, LocalDateTime blockedUntil) {
    if (telegramService == null) return;
    StringBuilder sb = new StringBuilder("🔧 <b>키움 API 점검 감지</b>\n");
    if (notice != null) {
      if (notice.period()  != null) sb.append("중단 일시: ").append(notice.period()).append('\n');
      if (notice.reason()  != null) sb.append("중단 사유: ").append(notice.reason()).append('\n');
      if (notice.content() != null) sb.append("중단 내용: ").append(notice.content()).append('\n');
    } else {
      sb.append("(점검 페이지 파싱 실패 — start.html 직접 확인 권장)\n");
    }
    sb.append("재시도 차단: ~")
      .append(blockedUntil.format(DateTimeFormatter.ofPattern("HH:mm")))
      .append("\n브리핑·스냅샷 등 키움 의존 흐름 전체 skip (점검 종료 후 자동 복귀)");
    telegramService.sendMessage(sb.toString());
  }

  private void notifyMaintenanceEnded() {
    if (telegramService == null) return;
    telegramService.sendMessage("✅ <b>키움 API 점검 종료</b>\n토큰 발급 정상 복귀");
    lastMaintenanceNoticeKey.set(null);
  }

  // ────────────────────────────────────────────────────────────────
  //  Internal records
  // ────────────────────────────────────────────────────────────────

  private record KiwoomToken(String accessToken, LocalDateTime expiresAt) {}

  /** 점검 안내 record — 동일 점검 식별용 signatureKey 제공. */
  private record MaintenanceNotice(String period, String reason, String content) {
    String signatureKey() {
      return (period != null ? period : "?") + "|" + (reason != null ? reason : "?");
    }
  }
}
