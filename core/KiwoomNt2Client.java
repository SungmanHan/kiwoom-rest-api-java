package com.nexttheme.stock.nt2;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexttheme.stock.collector.KiwoomApiClient;
import com.nexttheme.stock.nt2.domain.Nt2KiwoomResponseLog;
import com.nexttheme.stock.nt2.repository.Nt2KiwoomResponseLogRepository;
import com.nexttheme.stock.nt2.settings.Nt2AppSettingsService;
import com.nexttheme.stock.nt2.slot.Nt2Slot;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * NT2 전용 키움 TR 호출 래퍼 (PR #2). 설계서 §3.2.4.
 *
 * <p>master {@link KiwoomApiClient} 의 인프라(토큰, Rate Limiter, TR URL 라우팅, 재시도)를 그대로 재활용하되,
 * NT2 응답을 {@code nt2_kiwoom_response_log} 테이블에 영속화한다 — {@code nt2.kiwoom-log.enabled=true} 시.
 *
 * <p>master {@code KiwoomApiClient#call(apiId, body)} 가 URL 라우팅을 내부 처리하므로 NT2 호출부에서 URL
 * 인자는 받지 않는다 (설계서 §3.2.4 의 url 인자는 reference 용 — 실제 라우팅은 master TR_PATH_MAP).
 *
 * <p>master 가 실패 시 null 반환 정책이므로 NT2 는 null → {@link Nt2KiwoomException} 으로 래핑하여
 * 호출부가 명시적 catch 하도록 한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KiwoomNt2Client {

  private final KiwoomApiClient masterClient;
  private final Nt2KiwoomResponseLogRepository logRepo;
  private final Nt2AppSettingsService settings;

  /**
   * 키움 TR 호출 + NT2 raw 응답 영속화.
   *
   * @param trCode TR 코드 (예: {@code "ka10030"})
   * @param body 요청 파라미터 Map (master 가 JSON 직렬화)
   * @return 응답 JSON
   * @throws Nt2KiwoomException master 호출 실패(null) 또는 예외
   */
  public JsonNode callTr(String trCode, Map<String, Object> body) {
    long startMs = System.currentTimeMillis();
    JsonNode resp;
    int returnCode = -1;
    try {
      resp = masterClient.call(trCode, body);
    } catch (RuntimeException e) {
      log.error("[NT2] 키움 호출 예외 — tr={} err={}", trCode, e.getMessage(), e);
      throw new Nt2KiwoomException("키움 호출 실패: " + trCode, e);
    }
    if (resp == null) {
      log.error("[NT2] 키움 호출 null 반환 — tr={} (master enabled=false 또는 모든 재시도 실패)", trCode);
      throw new Nt2KiwoomException("키움 호출 실패(null): " + trCode);
    }
    returnCode = resp.path("return_code").asInt(-1);

    if (settings.getBoolean("nt2.kiwoom-log.enabled", true) && shouldPersist(trCode, returnCode)) {
      try {
        persistResponseLog(trCode, body, resp, returnCode, System.currentTimeMillis() - startMs);
      } catch (Exception e) {
        log.warn("[NT2] 응답 로그 저장 실패 (무시) — tr={} reason={}", trCode, e.getMessage());
      }
    }
    return resp;
  }

  /**
   * 고volume TR(갭모니터 호가·체결강도)은 raw 적재 제외 — 7일 보존분의 74% 를 차지하고 소비자가 없다
   * (설계서 §1.4). 단 실패 응답은 제외 대상이어도 적재한다(장애 사후분석용, 실측 발생률 0%).
   */
  private boolean shouldPersist(String trCode, int returnCode) {
    if (returnCode != 0) return true;
    String csv = settings.getString("nt2.kiwoom-log.exclude-tr", DEFAULT_EXCLUDE_TR);
    if (csv.isBlank()) return true;
    return Arrays.stream(csv.split(","))
        .map(String::trim)
        .noneMatch(trCode::equalsIgnoreCase);
  }

  private static final String DEFAULT_EXCLUDE_TR = "ka10004,ka10046";

  private void persistResponseLog(String trCode, Map<String, Object> body, JsonNode resp,
                                  int returnCode, long durationMs) {
    Nt2KiwoomResponseLog entity = Nt2KiwoomResponseLog.builder()
        .date(LocalDate.now())
        .timeSlot(currentTimeSlot())
        .trCode(trCode)
        .requestParams(body.toString())
        .responseRaw(truncate(resp.toString()))
        .responseStatus(returnCode)
        .parsedCount(resp.size())
        .durationMs((int) durationMs)
        .collectedAt(LocalDateTime.now())
        .build();
    logRepo.save(entity);
  }

  /** 5MB cap (설계서 §5.1.3). 안전 마진으로 4.5MB 에서 자른다. */
  private static String truncate(String raw) {
    final int cap = 4_500_000;
    if (raw == null || raw.length() <= cap) return raw;
    return raw.substring(0, cap) + "...[truncated]";
  }

  /** 슬롯 자동 분류 — 호출 시각 기준. */
  private static String currentTimeSlot() {
    LocalTime now = LocalTime.now();
    if (now.isBefore(LocalTime.of(11, 0)))  return Nt2Slot.EXPLORE_AM.persistKey();
    if (now.isBefore(LocalTime.of(13, 0)))  return Nt2Slot.EXPLORE_LUNCH.persistKey();
    if (now.isBefore(LocalTime.of(14, 50))) return Nt2Slot.PREP.persistKey();
    return Nt2Slot.FINAL.persistKey();
  }
}
