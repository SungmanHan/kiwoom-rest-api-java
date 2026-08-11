# theme — 테마 그룹 · 구성종목

`/api/dostk/thme`. 구현: [`Nt2KiwoomThemeProvider.java`](Nt2KiwoomThemeProvider.java)

| TR | 용도 | LIST 키 |
|---|---|---|
| **ka90001** | 테마그룹별 조회 | `thema_grp` |
| **ka90002** | 테마 구성종목 | `thema_comp_stk` |

호출 구조는 **2단계**입니다: ka90001로 테마 목록 → 관심 테마마다 ka90002로 구성종목.
테마가 10개면 총 11회 호출입니다. Rate Limiter(200ms)에서 2초 넘게 걸립니다.

---

## ka90001 — 테마 목록

```java
Map.of("qry_tp","1", "stk_cd","", "date_tp","1",
       "thema_nm","", "flu_pl_amt_tp","3",
       "stex_tp", stexTp)      // 1=KRX / 3=통합
```

| 필드 | 의미 |
|---|---|
| `thema_grp_cd` | 테마 코드 — **ka90002 입력값** |
| `thema_nm` | 테마명 |
| `flu_rt` | 테마 등락률 |
| `stk_num` | 편입 종목 수 |
| `rising_stk_num` | 상승 종목 수 |
| `dt_prft_rt` | 기간 수익률 |
| `main_stk` | 대표 종목 |

`rising_stk_num / stk_num` = **상승비율**. 테마가 실제로 같이 움직이는지 보는 지표로 씁니다.

---

## ka90002 — 구성종목

```java
Map.of("date_tp","1", "thema_grp_cd", themeCode, "stex_tp", stexTp)
```

| 필드 | 의미 |
|---|---|
| `stk_cd` | 종목코드 — **`_AL` 접미사 제거 필요** |
| `stk_nm` | 종목명 |
| `cur_prc` | 현재가 |
| `flu_rt` | 등락률 |
| `acc_trde_qty` | 누적 거래량 |

### 거래대금 필드가 없다

`cur_prc × acc_trde_qty` 로 **환산**해야 합니다. 정확한 거래대금이 아니라 근사치입니다 (평균단가가 아니라 현재가 기준이므로).

정렬 기준으로 쓰기엔 충분하지만, **절대 금액 게이트로 쓰면 오차가 큽니다.** 정확한 값이 필요하면 ka10030의 `trde_amt`를 쓰세요.

---

## 시간대 주의

테마 데이터는 **KRX 정규장 기준**입니다. 저녁 NXT 시간대에 조회하면 KRX **종가 시점 값이 동결**돼 나옵니다.

애프터마켓에 테마 등락률을 보고 판단하면, 실제로는 몇 시간 전 데이터입니다. 알고 쓰는 것과 모르고 쓰는 건 다릅니다.

---

## 대안 소스

네이버 금융 front-api(`sectors/all`, `group/stockList`)로도 같은 데이터를 받을 수 있습니다.

| | 키움 (ka90001/2) | 네이버 front-api |
|---|---|---|
| 테마 세분도 | 굵음 | **더 세분화** |
| 거래소 | KRX + NXT 선택 | KRX만 |
| 안정성 | 공식 API | 비공식 — 구조 변경 리스크 |

저희는 네이버를 기본으로 쓰고 키움을 롤백 경로로 두고 있습니다. **런타임 토글로 전환 가능하게** 만들어 두면 한쪽이 깨져도 버팁니다.

```java
"KRX".equals(settings.getString("nt2.theme.exchange", "SOR")) ? "1" : "3"
```

---

## 실제 응답 예시

### ka90001 — 테마 목록 (`date_tp=1`)

```jsonc
{
  "thema_grp": [                     // ← 100행
    {
      "thema_grp_cd": "452",         // ← ka90002 입력값
      "thema_nm": "SNS(Social Network Service)",
      "stk_num": "3",                // 편입 종목 수
      "rising_stk_num": "1",         // 상승 종목 수 → 상승비율 1/3
      "fall_stk_num": "2",
      "flu_rt": "+1.01", "flu_sig": "2",
      "dt_prft_rt": "+1.01",         // 기간 수익률
      "main_stk": "NAVER, 카카오"     // ← 콤마 구분 문자열. 배열이 아님
    }
  ],
  "return_code": 0
}
```

`main_stk`는 **종목명 콤마 나열 문자열**입니다. 종목코드가 아니라 이름이라 코드 매핑에 바로 못 씁니다.

### ka90002 — 구성종목 (`thema_grp_cd=215`)

```jsonc
{
  "flu_rt": "-1.01",                 // ← 테마 전체 등락률 (root 직속)
  "dt_prft_rt": "-3.91",
  "thema_comp_stk": [                // ← 8행
    {
      "stk_cd": "001740", "stk_nm": "SK네트웍스",
      "cur_prc": "+14170",
      "pred_pre": "+3270",
      "flu_rt": "+30.00",            // ← 상한가
      "acc_trde_qty": "56474937",
      "sel_bid": "0", "sel_req": "0",   // ← 상한가라 매도호가가 0
      "buy_bid": "+14170", "buy_req": "1266518",
      "dt_prft_rt_n": "+8.92"
    }
  ],
  "return_code": 0
}
```

**거래대금 필드가 없습니다** → `cur_prc × acc_trde_qty` 로 환산해야 합니다 (위 종목은 약 8,003억).

상한가 종목은 `sel_bid`/`sel_req`가 **0**입니다. 매도벽 계산에서 0으로 나누지 않게 가드하세요.
