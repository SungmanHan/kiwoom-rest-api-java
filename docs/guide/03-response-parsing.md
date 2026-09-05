# 키움 REST API 응답 파싱 — 숫자가 전부 문자열로 온다

지난 글에서 앱키 발급과 Access Token으로 첫 API 응답을 받아봤다면, 이번엔 그 응답 안에 있는 **숫자를 실제로 써먹는 단계**입니다.

여기서부터 문제가 시작됩니다. 키움 REST API의 JSON 응답을 열어보면 가격도, 수량도, 순매수 금액도 전부 큰따옴표로 감싸진 **문자열**로 옵니다. `Long.parseLong()` 한 줄로 끝날 것 같지만, 실제로 돌려보면 `NumberFormatException`이 튀거나, 값은 파싱됐는데 부호가 반대로 나오거나, 0으로 계산한 값이 사실은 "값 없음"이었다는 걸 뒤늦게 알게 됩니다.

이번 글은 이 시리즈 저장소의 실제 파서 코드(`core/KiwoomNt2NumberParser.java`)와, 운영 중 실제로 겪은 파싱 함정 기록(`docs/PITFALLS.md`)을 근거로 씁니다.

## 이번 글에서 배울 것

- 키움 응답의 숫자 필드가 왜 항상 "문자열"인지, 그리고 왜 그걸 전제로 코드를 짜야 하는지
- 음수가 `-369770`이 아니라 `--369770`처럼 **대시(dash) 두 개**로 오는 이유와 안전한 처리법
- 필드마다 단위가 다르다는 것 (원 / 백만원 / 억원 / 천주)를 놓치면 생기는 자릿수 사고
- 빈 문자열(`""`)을 실수로 0으로 취급했을 때 벌어지는 일
- `KiwoomNt2NumberParser`의 부호 보존 파싱 규칙을 코드 단위로 뜯어보기

## 왜 굳이 전용 파서가 필요한가

REST API라고 하면 보통 `price: 71500`처럼 JSON 숫자 타입을 기대합니다. 하지만 키움 응답은 다음과 같은 형태로 옵니다.

```json
{ "netprps_qty": "--369770", "netprps_amt": "+424822", "trde_qty": "0" }
```

전부 문자열입니다. 게다가 필드에 따라 규칙이 또 다릅니다.

- 가격·거래량처럼 **항상 양수인 필드**는 부호를 신경 쓸 필요가 없습니다.
- 순매수·손익처럼 **부호 자체가 의미를 가지는 필드**는 부호를 절대 버리면 안 됩니다.

`docs/PITFALLS.md`는 이 두 그룹을 이렇게 정리하고 있습니다.

| 구분 | 필드 예시 |
|---|---|
| 부호를 살려야 하는 필드 | `netprps_qty`, `netprps_amt`, `netslmt`, `evltv_prft`, `tdy_rlzt_pl` 등 수급·손익 계열 |
| 부호를 버려도 되는 필드 | `cur_prc`, `open_pric`, `high_pric`, `trde_qty` 등 가격·거래량 |

두 그룹을 같은 파서 하나로 처리하려고 하면, 언젠가 한쪽 필드에서 부호가 사라지거나 반대로 뒤집히는 버그가 납니다. 그래서 이 저장소는 **범용 파서와 부호 보존 파서를 처음부터 분리**해 두고 있습니다. 이번 글에서 보는 `KiwoomNt2NumberParser`가 후자입니다.

## 실제 코드 살펴보기

`core/KiwoomNt2NumberParser.java` 전체입니다.

```java
package com.nexttheme.stock.nt2.aux;

/**
 * NT2 보조 TR 응답 파서 — Kiwoom 부호 표기 정규화 (PR #6).
 *
 * <p>Kiwoom 응답은 {@code +/-} 접두를 가질 수 있고, 이중 dash {@code --} 는 음수 표기 변형이다.
 * 기존 {@code parseLongClean} (chart) 은 부호를 *전부 strip* 하지만, 5-A 보조 TR (netprps_qty / netslmt /
 * netprps_amt) 는 *부호가 의미를 가지므로* 보존해야 한다.
 */
public final class KiwoomNt2NumberParser {

  private KiwoomNt2NumberParser() {}

  /**
   * 부호 보존 long 파싱 — 빈/null → 0.
   * <ul>
   *   <li>{@code "+27885"} → 27885</li>
   *   <li>{@code "-27885"} → -27885</li>
   *   <li>{@code "--27885"} → -27885 (Kiwoom 이중 dash)</li>
   * </ul>
   */
  public static long parseSignedLong(String raw) {
    if (raw == null || raw.isBlank()) {
      return 0L;
    }
    String s = raw.trim();
    if (s.startsWith("--")) {
      String digits = s.substring(2);
      return digits.isBlank() ? 0L : -Long.parseLong(digits);
    }
    if (s.startsWith("+")) {
      String digits = s.substring(1);
      return digits.isBlank() ? 0L : Long.parseLong(digits);
    }
    return Long.parseLong(s);
  }

  /** 키움 응답 종목코드 'A' 접두사 제거. "A005930" → "005930". 이미 bare이면 그대로. */
  public static String stripItemCodePrefix(String raw) {
    if (raw == null || raw.isBlank()) return raw;
    return raw.startsWith("A") ? raw.substring(1) : raw;
  }

  /** 부호 보존 double 파싱. */
  public static double parseSignedDouble(String raw) {
    if (raw == null || raw.isBlank()) {
      return 0.0;
    }
    String s = raw.trim();
    if (s.startsWith("--")) {
      String digits = s.substring(2);
      return digits.isBlank() ? 0.0 : -Double.parseDouble(digits);
    }
    if (s.startsWith("+")) {
      String digits = s.substring(1);
      return digits.isBlank() ? 0.0 : Double.parseDouble(digits);
    }
    return Double.parseDouble(s);
  }
}
```

전체 코드: https://github.com/SungmanHan/kiwoom-rest-api-java/tree/main/core

## 코드 한 부분씩 설명

**1. `null`/빈 문자열 가드가 제일 먼저 온다**

```java
if (raw == null || raw.isBlank()) {
  return 0L;
}
```

`Long.parseLong("")`는 예외를 던집니다. 그래서 파서 진입부에서부터 빈 문자열을 걸러냅니다. 다만 이 글 뒷부분에서 다루겠지만, 이 파서처럼 **빈 문자열을 0으로 확정해도 되는 필드인지는 별도로 확인해야 합니다.**

**2. `--`(이중 대시) 처리가 `+` 처리보다 먼저 온다**

```java
if (s.startsWith("--")) {
  String digits = s.substring(2);
  return digits.isBlank() ? 0L : -Long.parseLong(digits);
}
if (s.startsWith("+")) {
  ...
}
```

순서가 중요합니다. `--369770`을 `startsWith("-")`로만 걸러서 앞의 `-` 하나만 떼면 `-369770`이 아니라 `-"-369770"` 형태의 문자열이 `Long.parseLong`에 그대로 들어가면서 다시 예외가 납니다. `--`를 먼저 체크해서 두 글자를 통째로 잘라내야 정상적으로 음수 `-369770`을 얻습니다.

**3. `parseSignedDouble`은 `parseSignedLong`과 구조가 완전히 같다**

가격 계열 중 소수점이 있는 필드(등락률 등)를 위해 같은 규칙을 `double`로 반복하고 있습니다. 로직 자체는 복사-붙여넣기 수준이지만, 이 저장소는 `long`/`double` 두 갈래를 하나의 제네릭 메서드로 통합하지 않았습니다 — 파싱 실패 시 예외 스택에서 어떤 타입으로 실패했는지 바로 드러나는 편이 디버깅에 더 유리했기 때문입니다.

**4. `stripItemCodePrefix`는 숫자 파싱이 아니라 종목코드 정규화다**

이 메서드는 이름 그대로 숫자와 무관하지만 같은 파일에 있습니다. 계좌 조회 TR(`kt00018` 등)의 응답에서 종목코드가 `"A005930"`처럼 **접두사** `A`가 붙어서 오는 것을 떼는 용도입니다. (참고로 랭킹류 TR은 반대로 `"005930_NX"`, `"005930_AL"`처럼 **접미사**가 붙습니다 — 접두사와 접미사가 TR 종류에 따라 다르므로 한쪽만 처리하는 코드를 그대로 복사해 쓰면 다른 쪽에서 조용히 실패합니다.)

## 실제 응답 예시

`docs/PITFALLS.md`에 정리된 실측 예시입니다. 하드코딩된 값이 아니라 실제 필드에서 관측된 표기입니다.

| 원본 문자열 | 의미 |
|---|---|
| `"--369770"` | -369,770 |
| `"+424822"` | +424,822 |
| `"0"` | 0 |
| `""` | 값 없음 (0으로 볼지 null로 볼지는 필드마다 다름) |

`Long.parseLong("--369770")`을 그냥 실행하면 `NumberFormatException`이 발생합니다. 위 표의 세 번째·네 번째 줄이 특히 헷갈리는데, `"0"`(문자열로 명시된 0)과 `""`(빈 문자열)은 **다른 의미**입니다. 다음 절에서 자세히 다룹니다.

## 여기서 주의해야 할 점

**1. 빈 문자열은 0이 아니다**

`rank_chg`(관심순위 랭킹 TR `ka00198`) 같은 필드는 "변동 없음(0)"과 "값이 아예 없음"이 다른 상태인데, 파서가 이걸 전부 0으로 뭉개버리면 두 상태를 코드에서 구분할 방법이 사라집니다. 계측용으로 값을 남겨야 하는 필드라면 `long` 0이 아니라 `Integer` null로 받아두는 편이 안전합니다.

같은 맥락에서 `rank_chg_sign` 필드는 부호 소스로 쓰지 않는 것이 좋습니다 — 변동이 없을 때 이 필드가 빈 문자열로 오기 때문에, 애초에 부호를 뽑아낼 수 없는 상황이 생깁니다. 부호는 `rank_chg` 값 문자열 자체(`"+1"`, `"-3"`)에 이미 들어 있습니다.

실측으로 확인된, 빈 문자열이 오는 자리 목록입니다.

| TR | 필드 | 언제 |
|---|---|---|
| ka10030 | `opmr_trde_qty`, `af_mkrt_trde_qty`, `bf_mkrt_trde_qty` | `mrkt_open_tp` 조건 밖 |
| ka90004 | 전 필드 | 장 마감 전 당일 조회 |
| ka00198 | `rank_chg_sign` | 순위 변동 없을 때 (항상) |
| ka10001 | `mac_wght` | 상시 |

이 필드들을 숫자 파서에 그대로 넣으면 `Long.parseLong("")`이 예외를 던집니다. 파서 진입부의 `isBlank()` 가드가 없으면 이 지점에서 바로 죽습니다.

**2. 음수는 대시 한 개가 아니라 두 개로 온다**

이미 코드에서 봤듯, `-369770`이 아니라 `--369770`으로 옵니다. `"-"` 하나만 처리하는 파서를 재사용하면 이 필드에서만 예외가 나거나, 처리 순서에 따라 부호가 엉뚱하게 붙습니다.

**3. 필드마다 단위가 다르다**

숫자를 무사히 파싱해도 끝이 아닙니다. 필드별 단위가 제각각이라, 파싱된 값을 그대로 비교/합산하면 자릿수가 어긋납니다.

| 필드 | 단위 | 환산 |
|---|---|---|
| `trde_amt` (ka10030) | 백만원 | ×1,000,000 → 원 |
| `trde_prica` (ka10081) | 백만원 | ×1,000,000 → 원 |
| `prm_netprps_amt` (ka90008/13) | 백만원 | ×1,000,000 → 원 |
| `frgnr_netprps` (ka10051, `amt_qty_tp=0`) | 억원 | ×100,000,000 → 원 |
| `mac` (ka10001 시가총액) | 억원 | ×100,000,000 → 원 |
| `dstr_stk` (ka10001 유통주식수) | 천주 | ×1,000 → 주 |
| 가격 전반 | 원 | — |

검증 팁: 삼성전자의 `trde_amt`가 `11,526,863`으로 온다면, 이게 원 단위라고 보면 1150만원짜리 거래대금이라는 말이 되어 이상합니다. 백만원 단위로 보면 11.5조원이 되어 실제 거래대금과 맞아떨어집니다. 새 필드를 다룰 때는 이렇게 실제로 알고 있는 종목·값 하나로 단위를 역산해보는 것이 제일 빠릅니다.

## 직접 해보기

`KiwoomNt2NumberParser`를 그대로 가져다 아래처럼 값을 넣어보면 규칙이 손에 잡힙니다.

```java
System.out.println(KiwoomNt2NumberParser.parseSignedLong("--369770")); // -369770
System.out.println(KiwoomNt2NumberParser.parseSignedLong("+424822"));  // 424822
System.out.println(KiwoomNt2NumberParser.parseSignedLong(""));         // 0
System.out.println(KiwoomNt2NumberParser.parseSignedLong(null));       // 0
```

여기서 한 걸음 더 나아가려면, 지금 다루려는 필드가 위의 "부호를 살려야 하는 필드" 목록에 있는지부터 확인하세요. 목록에 없는 가격·거래량 계열 필드라면 부호 보존 파서 대신 부호를 전부 버리는 범용 파서(`parseLongClean` 계열)를 쓰는 편이 맞습니다. 필드 성격과 파서 종류를 맞추는 게 이번 글의 핵심입니다.

## 이번 글 정리

- 키움 응답의 숫자 필드는 전부 문자열로 온다는 것을 전제로 코드를 짜야 합니다.
- 음수는 `-`가 아니라 `--`(이중 대시)로 표기되며, `--` 체크가 `+` 체크보다 먼저 와야 합니다.
- 빈 문자열(`""`)과 `"0"`은 다른 의미일 수 있습니다. 계측용 필드는 0이 아니라 null로 받아두세요.
- 필드마다 단위(원/백만원/억원/천주)가 다르므로, 실제 값 하나로 역산해서 단위를 확인하는 습관이 필요합니다.
- 부호를 살려야 하는 필드(수급·손익 계열)와 버려도 되는 필드(가격·거래량 계열)는 처음부터 파서를 분리해 두는 편이 안전합니다.

## 다음 글 예고

다음 편에서는 호출 계층으로 한 단계 올라가서 **Rate Limit·토큰 갱신·점검 감지**를 다룹니다. 초당 호출 횟수 제한에 걸렸을 때의 응답 코드, Access Token 만료 형식과 갱신 타이밍, 그리고 점검 중에 API가 JSON 대신 HTML을 돌려줄 때 이를 감지하는 방법을 실제 코드로 살펴보겠습니다.
