package com.nexttheme.stock.nt2;

/**
 * NT2 키움 호출 실패 예외 (PR #2). 설계서 §3.2.6.
 *
 * <p>master {@code KiwoomApiClient.call()} 의 null 반환 또는 내부 예외를 NT2 도메인 예외로 래핑한다.
 * Orchestrator 는 이 예외를 catch 하여 {@code nt2_job_history} FAILURE 적재 + 슬롯 종료 처리한다.
 */
public class Nt2KiwoomException extends RuntimeException {

  public Nt2KiwoomException(String message, Throwable cause) {
    super(message, cause);
  }

  public Nt2KiwoomException(String message) {
    super(message);
  }
}
