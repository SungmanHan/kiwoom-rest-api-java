# spec — 키움증권 공식 API 스펙

> ⚠️ **이 폴더의 자료는 키움증권이 제작·배포하는 공식 문서입니다.** 저작권은 키움증권에 있으며, 개발 편의를 위해 사본을 함께 두었습니다. 최신본은 반드시 **공식 페이지**에서 확인하세요.
>
> **공식 API 가이드** → https://openapi.kiwoom.com/guide/apiguide

저장소의 나머지 폴더는 이 스펙을 읽고 구현한 뒤 **실제로 깨져 가며 알게 된 것들**입니다. 스펙과 실제 동작이 다른 지점은 [`../docs/PITFALLS.md`](../docs/PITFALLS.md)에 실측 날짜와 함께 정리돼 있습니다.

---

## 파일

| 파일 | 내용 |
|---|---|
| `kiwoom-rest-api-spec.json` | **338개 TR** 기계판독 스펙 (2.4MB) |
| `키움 REST API 문서.pdf` | 공식 PDF 원본 (24MB) |

---

## `kiwoom-rest-api-spec.json` 구조

TR 코드를 키로 하는 flat 맵입니다. 코드 생성·검증에 쓰기 좋습니다.

```json
{
  "ka10004": {
    "apiId":       "ka10004",
    "apiNm":       "주식호가요청",
    "method":      "POST",
    "domain":      "https://api.kiwoom.com",
    "mockDomain":  "https://mockapi.kiwoom.com",
    "url":         "/api/dostk/mrkcond",
    "format":      "JSON",
    "contentType": "application/json;charset=UTF-8",
    "description": "시세정보를 확인할 수 있습니다",
    "requestIo":  [ { "itemId": "stk_cd", "itemNm": "종목코드", "type": "String", "length": "20", "desc": "..." }, ... ],
    "responseIo": [ { "itemId": "bid_req_base_tm", "itemNm": "호가잔량기준시간", ... }, ... ],
    "requestExample":  "{\n    \"stk_cd\": \"005930\"\n}",
    "responseExample": "{\r\n\t\"bid_req_base_tm\":\"162000\", ... }"
  },
  ...
}
```

### 쓸 만한 용도

**TR → URL 매핑 테이블 자동 생성** — [`core/KiwoomApiClient.java`](../core/KiwoomApiClient.java)의 `TR_PATH_MAP`을 손으로 관리할 필요가 없습니다.

```bash
python3 -c "
import json
d = json.load(open('spec/kiwoom-rest-api-spec.json'))
for tr, v in sorted(d.items()):
    print(f'Map.entry(\"{tr}\", \"{v[\"url\"]}\"),')
"
```

**응답 필드 존재 여부 검증** — 파싱 코드가 참조하는 키가 스펙에 실재하는지 확인:

```bash
python3 -c "
import json
d = json.load(open('spec/kiwoom-rest-api-spec.json'))
print({i['itemId'] for i in d['ka10001']['responseIo']})
"
```

**엔드포인트별 TR 목록**:

```bash
python3 -c "
import json, collections
d = json.load(open('spec/kiwoom-rest-api-spec.json'))
g = collections.defaultdict(list)
for tr, v in d.items(): g[v['url']].append(tr)
for url, trs in sorted(g.items()): print(f'{url:28} {len(trs):3}  {\" \".join(sorted(trs)[:8])}')
"
```

---

## ⚠️ 스펙과 실제가 다른 지점

스펙을 그대로 믿으면 안 되는 케이스를 실제로 겪었습니다. 전체는 [`../docs/PITFALLS.md`](../docs/PITFALLS.md)에 있고, 대표적인 것만:

| 항목 | 스펙 | 실제 |
|---|---|---|
| 차트 수정주가 | `updn_tp` | **미지원**. `upd_stkpc_tp="1"` 을 써야 함 |
| ka10054 필수 파라미터 | 일부만 명시 | 캐스케이드로 **12개**까지 늘어남 |
| ka10004 거래소 선택 | (언급 없음) | `stex_tp` 무시. **`stk_cd` 접미사**로만 (`_NX`/`_AL`) |
| ka10051 `inds_cd` | `001` | `stex_tp`에 따라 `001_NX` / `001_AL` **접미사가 붙음** (미문서화) |
| Rate Limit | (명시 없음) | COM API 기준 **초당 5회** 가정이 필요 |
| 음수 표기 | (언급 없음) | dash **2개** (`"--369770"`) |
| ka90004 당일 조회 | 정상 응답 | 장 마감 전에는 **전 필드 빈 문자열** 행 |

스펙에 없다고 안 되는 게 아니고, 스펙에 있다고 되는 것도 아닙니다. **실제 응답을 찍어 보는 게 유일한 확인 방법**입니다.
