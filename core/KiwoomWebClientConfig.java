// 키움 전용 WebClient 빈 — baseUrl(운영/모의 토글) + connect/read 타임아웃 이중 방어
// 출처: CollectorConfig.java:79-96 (원본 verbatim 발췌)

  @Bean("kiwoomWebClient")
  public WebClient kiwoomWebClient(
      KiwoomProperties kiwoomProperties,
      @Value("${app.api.connect-timeout-ms:3000}") int connectTimeoutMs,
      @Value("${app.api.response-timeout-ms:10000}") int responseTimeoutMs) {
    HttpClient httpClient = ResilientHttpClient.pooled("kiwoom", connectTimeoutMs, Duration.ofMillis(responseTimeoutMs))
        .doOnConnected(conn ->
            conn.addHandlerLast(new ReadTimeoutHandler(responseTimeoutMs, TimeUnit.MILLISECONDS)));

    return WebClient.builder()
        .baseUrl(kiwoomProperties.activeUrl())
        .clientConnector(new ReactorClientHttpConnector(httpClient))
        .defaultHeader("Content-Type", "application/json;charset=UTF-8")
        .defaultHeader("Accept", "application/json")
        .codecs(c -> c.defaultCodecs().maxInMemorySize(2 * 1024 * 1024))
        .filter(ApiLogFilter.create(logDir))
        .build();
  }
