package com.nexttheme.stock.core.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 키움 REST API 연동 설정.
 *
 * <p>{@code application.yml} {@code app.kiwoom.*} 에 매핑된다.
 * {@code enabled=false}이면 키움 API 전체가 비활성화되고 호출부 네이버 폴백으로 동작한다.
 * 활성 상태에서 종목 단위 실패는 결과 맵에서 제외(silent drop + log.warn),
 * 전체 호출 실패·토큰 갱신 실패는 {@code null} 반환 후 {@code log.warn}/{@code log.error} 기록
 * (예외 전파 없음 — Chat 채널 알림은 향후 작업, 네이버 폴백 폐지 2026-04-17).
 */
@ConfigurationProperties(prefix = "app.kiwoom")
public record KiwoomProperties(
    boolean enabled,
    String baseUrl,
    String mockUrl,
    boolean useMock,
    int maxConcurrency,
    int requestDelayMs,
    int tokenRefreshMarginMinutes,
    int stockParallelism,
    @DefaultValue("5") int tokenMaintenanceBackoffMinutes
) {

  /** 종목 동시 처리 수 (0 이하이면 기본값 3). */
  public int effectiveStockParallelism() {
    return stockParallelism > 0 ? stockParallelism : 3;
  }

  /** 실제 호출에 사용할 URL (useMock=true 이면 mockUrl 반환). */
  public String activeUrl() {
    return useMock ? mockUrl : baseUrl;
  }
}
