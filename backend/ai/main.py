import json
import re
import os
import sys
import time
import base64
import random
import urllib.request
import urllib.parse
from typing import List, Optional, Dict, Any
from itertools import combinations
from collections import Counter

from fastapi import FastAPI, HTTPException, Query, File, UploadFile
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel, Field, ConfigDict
from openai import OpenAI
from bs4 import BeautifulSoup
from dotenv import load_dotenv
import cv2
import numpy as np
import asyncio
from playwright.async_api import async_playwright

# ai 디렉토리를 파이썬 모듈 검색 경로에 안전하게 추가
sys.path.append(os.path.dirname(os.path.abspath(__file__)))

# .env 파일 환경 변수 로드 (override=True로 OS 더미 환경변수 방지)
env_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), ".env")
load_dotenv(env_path, override=True)

# 1. EasyOCR 딥러닝 라이브러리
try:
    import easyocr
    reader_instance = easyocr.Reader(['ko', 'en'], gpu=False, verbose=False)
except Exception as e:
    print(f"[EasyOCR Init Notice] {e}")
    reader_instance = None

# 2. Pyzbar 범용 QR/바코드 라이브러리
try:
    from pyzbar.pyzbar import decode as zbar_decode
    has_pyzbar = True
except Exception as e:
    print(f"[Pyzbar Init Notice] {e}")
    has_pyzbar = False

from playwright.sync_api import sync_playwright

# Windows asyncio Proactor 정책 설정
if sys.platform == 'win32':
    asyncio.set_event_loop_policy(asyncio.WindowsProactorEventLoopPolicy())

# -------------------------------------------------------------------
# 0. [Playwright 동적 렌더링 크롤러] SPA / React / Vue 실시간 DOM 수집기
# -------------------------------------------------------------------
def _sync_scrape_worker(target_url: str) -> tuple[str, str]:
    print(f"[Playwright Sync Worker] Navigating to: {target_url}")
    final_url = target_url
    rendered_text = ""
    try:
        with sync_playwright() as p:
            browser = p.chromium.launch(headless=True)
            context = browser.new_context(
                user_agent="Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1",
                viewport={"width": 390, "height": 844}
            )
            page = context.new_page()
            page.goto(target_url, wait_until="domcontentloaded", timeout=12000)
            page.wait_for_timeout(2200) # SPA 비동기 렌더링 대기
            
            final_url = page.url
            rendered_text = page.inner_text("body")
            browser.close()
            print(f"[Playwright Sync Worker] Scraped {len(rendered_text)} chars from {final_url}")
            return final_url, rendered_text[:4000]
    except Exception as e:
        print(f"[Playwright Worker Fallback Exception] {e}")
        try:
            req = urllib.request.Request(target_url, headers={'User-Agent': 'Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X)'})
            with urllib.request.urlopen(req, timeout=6) as resp:
                final_url = resp.geturl()
                html = resp.read().decode('utf-8', errors='ignore')
                soup = BeautifulSoup(html, 'html.parser')
                rendered_text = soup.get_text(separator='\n', strip=True)[:4000]
        except Exception as e2:
            rendered_text = f"URL: {target_url}"
            
    return final_url, rendered_text

async def scrape_dynamic_web_content(target_url: str) -> tuple[str, str]:
    return await asyncio.to_thread(_sync_scrape_worker, target_url)

app = FastAPI(
    title="AI Smart Menu Master Orchestrator Pro",
    description="범용 QR + EasyOCR + GPT-4o-mini + 3대 냅색 프로필 추천 엔진",
    version="9.0.0"
)

# CORS 미들웨어 전면 허용
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# OpenAI Client
openai_api_key = os.getenv("OPENAI_API_KEY", "")
client = OpenAI(api_key=openai_api_key)

# -------------------------------------------------------------------
# 1. Request / Response DTO Schemas
# -------------------------------------------------------------------
class MenuItem(BaseModel):
    model_config = ConfigDict(extra="ignore")
    menuName: str = Field(..., example="직화 돼지갈비")
    price: int = Field(..., example=25000)
    desc: Optional[str] = Field("대표 메뉴", example="인기 메뉴")
    category: Optional[str] = Field("메인", example="메인")

class RecommendedItem(BaseModel):
    model_config = ConfigDict(extra="ignore")
    menuName: str
    price: int
    count: int = 1
    totalItemPrice: int
    desc: Optional[str] = ""
    category: Optional[str] = "메인"

class RecommendRequest(BaseModel):
    model_config = ConfigDict(extra="ignore")
    menuList: List[MenuItem] = Field(...)
    peopleCount: int = Field(2)
    budget: int = Field(60000)
    meetingType: Optional[str] = Field("친구 모임")
    excludedFoods: Optional[List[str]] = Field([])
    bigEaterCount: Optional[int] = Field(0)
    spicyLevel: Optional[int] = Field(3)
    dietCount: Optional[int] = Field(0)
    todayPreference: Optional[str] = Field("")
    excludedCombos: Optional[List[List[str]]] = Field([])
    presetProfile: Optional[str] = Field("balance") # balance, signature, value

class ReRecommendRequest(RecommendRequest):
    model_config = ConfigDict(extra="ignore")
    elapsedMinutes: Optional[int] = Field(45)
    budgetDelta: Optional[int] = Field(0)

class RecommendResponse(BaseModel):
    model_config = ConfigDict(extra="ignore")
    recommendedItems: List[RecommendedItem]
    recommendedMenus: List[str]
    totalPrice: int
    reason: str
    engineType: str
    profile: str


# -------------------------------------------------------------------
# 2. [범용 다중 엔진 QR 디텍터] Multi-Engine Universal QR Pipeline
# -------------------------------------------------------------------
from PIL import Image
import io

def universal_decode_qr(img_bytes: bytes) -> Optional[str]:
    try:
        # 0차: PIL Image 직접 디코딩 (가장 안정적)
        if has_pyzbar:
            try:
                pil_img = Image.open(io.BytesIO(img_bytes))
                pil_decoded = zbar_decode(pil_img)
                if pil_decoded:
                    url = pil_decoded[0].data.decode('utf-8', errors='ignore').strip()
                    if url.startswith("http"):
                        print(f"[Universal QR 0차: Pyzbar PIL] {url}")
                        return url
            except Exception as e_pil:
                pass

        nparr = np.frombuffer(img_bytes, np.uint8)
        img = cv2.imdecode(nparr, cv2.IMREAD_COLOR)
        if img is None:
            return None

        # 1차: pyzbar 원본 및 그레이스케일 스캔
        if has_pyzbar:
            decoded = zbar_decode(img)
            if decoded:
                url = decoded[0].data.decode('utf-8', errors='ignore').strip()
                if url.startswith("http"):
                    print(f"[Universal QR 1차: Pyzbar Raw] {url}")
                    return url

            gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
            decoded_gray = zbar_decode(gray)
            if decoded_gray:
                url = decoded_gray[0].data.decode('utf-8', errors='ignore').strip()
                if url.startswith("http"):
                    print(f"[Universal QR 1차: Pyzbar Gray] {url}")
                    return url

            # CLAHE (대비 향상) 적용 후 스캔
            clahe = cv2.createCLAHE(clipLimit=2.0, tileGridSize=(8,8))
            enhanced = clahe.apply(gray)
            decoded_clahe = zbar_decode(enhanced)
            if decoded_clahe:
                url = decoded_clahe[0].data.decode('utf-8', errors='ignore').strip()
                if url.startswith("http"):
                    print(f"[Universal QR 1차: Pyzbar CLAHE] {url}")
                    return url

            # 4방향 회전 스캔 (90도, 180도, 270도)
            for angle in [cv2.ROTATE_90_CLOCKWISE, cv2.ROTATE_180, cv2.ROTATE_90_COUNTERCLOCKWISE]:
                rotated = cv2.rotate(gray, angle)
                decoded_rot = zbar_decode(rotated)
                if decoded_rot:
                    url = decoded_rot[0].data.decode('utf-8', errors='ignore').strip()
                    if url.startswith("http"):
                        print(f"[Universal QR 1차: Pyzbar Rotated] {url}")
                        return url

        # 2차: OpenCV QRCodeDetector 다각도 스캔
        qr_detector = cv2.QRCodeDetector()
        for frame in [img, cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)]:
            data, bbox, _ = qr_detector.detectAndDecode(frame)
            if data and data.strip().startswith("http"):
                print(f"[Universal QR 2차: OpenCV] {data.strip()}")
                return data.strip()

    except Exception as e:
        print(f"[Universal QR Exception] {e}")

    return None


# -------------------------------------------------------------------
# 3. [메뉴 인식 서포트] EasyOCR + 전처리 비전 스캐너
# -------------------------------------------------------------------
def scan_raw_text_with_easyocr(img_bytes: bytes) -> tuple[List[str], List[dict]]:
    if not reader_instance:
        return [], []

    try:
        nparr = np.frombuffer(img_bytes, np.uint8)
        img = cv2.imdecode(nparr, cv2.IMREAD_COLOR)
        if img is None:
            return [], []

        # 이미지 리사이즈 & 대비 개선
        h, w = img.shape[:2]
        if max(h, w) > 2000:
            scale = 2000 / max(h, w)
            img = cv2.resize(img, (int(w * scale), int(h * scale)), interpolation=cv2.INTER_AREA)

        results = reader_instance.readtext(img)
    except Exception as e:
        print(f"[EasyOCR Read Exception] {e}")
        return [], []

    raw_lines = []
    ocr_blocks = []
    for bbox, text, prob in results:
        t = text.strip()
        if prob >= 0.15 and len(t) >= 1:
            raw_lines.append(t)
            ocr_blocks.append({
                "text": t,
                "confidence": round(prob, 2),
                "y": (bbox[0][1] + bbox[2][1]) / 2.0
            })

    return raw_lines, ocr_blocks


# -------------------------------------------------------------------
# 4. [메뉴 인식 총책임] GPT-4o-mini Vision & Parsing Orchestrator
# -------------------------------------------------------------------
def orchestrate_menu_parsing_with_gpt(image_bytes: Optional[bytes] = None, web_raw_text: Optional[str] = None, store_name_hint: str = "") -> tuple[str, List[dict]]:
    store_name = store_name_hint or "스캔 매장"
    menus = []
    
    INVALID_PLATFORM_NAMES = [
        "토스 픽업주문", "토스 픽업", "토스오더", "토스플레이스", 
        "네이버 주문", "네이버 플레이스", "네이버지도", 
        "배달의민족", "쿠팡이츠", "요기요", "테이블링", "캐치테이블", "스캔 매장", "추천 매장"
    ]

    # 사용자가 직접 입력한 명확한 상호명이 있는 경우 우선 채택
    if store_name_hint and not store_name_hint.startswith("http") and not any(inv in store_name_hint for inv in INVALID_PLATFORM_NAMES):
        store_name = store_name_hint.strip()

    easyocr_lines = []
    if image_bytes:
        easyocr_lines, _ = scan_raw_text_with_easyocr(image_bytes)
        print(f"[EasyOCR Support] Scanned {len(easyocr_lines)} text tokens")
    
    ocr_summary = f"{store_name_hint}\n" + ("\n".join(easyocr_lines) if easyocr_lines else (web_raw_text or ""))

    system_prompt = """
    당신은 대한민국 최고 권위의 식당 메뉴판 전문 AI 초고속 정제 파서(Master Menu Parser)입니다.
    제공된 [실시간 크롤링 텍스트 / OCR 스캔 텍스트 / 이미지 원본]을 분석하여 다음 원칙에 따라 JSON으로만 응답하세요:
    
    [엄격한 매장명 및 메뉴 정제 원칙 - 100% 실시간 무상태 원칙]
    1. 'storeName': 
       - 입력된 텍스트/이미지 상단에 실제로 나타난 고유 브랜드 상호명을 그대로 추출하세요.
       - [절대 금지]: '토스 픽업주문', '토스오더', '토스플레이스', '네이버 주문', '배달의민족', '추천 매장', '스캔 매장', '주문내역' 등 서비스/플랫폼 명칭은 상호명으로 사용할 수 없습니다.
    2. 'menus': 실제 텍스트에 판매 중인 것으로 나타난 진짜 메뉴 목록 배열. (최대 40개 대표 메뉴)
       - [가장 중요 - 날조 절대 금지]: 반드시 제공된 텍스트/이미지에 실제로 적혀 있는 음식/음료 이름과 실제 가격만 추출하세요! 텍스트에 없는 가짜 메뉴는 절대 포함하지 마세요.
       - 'menuName': 오직 순수한 음식/음료 이름만 추출.
       - 'price': 숫자로 된 정확한 원화 판매 가격 (예: 1500, 3900, 11000).
       - 'category': '메인', '세트', '사이드', '음료', '디저트', '주류' 중 하나.
    3. 빠른 응답을 위해 반드시 유효한 경량 JSON 형식 {"storeName": string, "menus": [{"menuName": str, "price": int, "category": str}]} 으로만 응답하세요.
    """

    messages = [{"role": "system", "content": system_prompt}]
    user_content = []
    user_content.append({"type": "text", "text": f"[입력 데이터]\n{ocr_summary[:3500]}"})

    if image_bytes:
        base64_img = base64.b64encode(image_bytes).decode('utf-8')
        user_content.append({
            "type": "image_url",
            "image_url": {"url": f"data:image/jpeg;base64,{base64_img}"}
        })

    messages.append({"role": "user", "content": user_content})

    try:
        print(f"[GPT-4o-mini Master Parser] Orchestrating menu recognition for '{store_name}'...")
        response = client.chat.completions.create(
            model="gpt-4o-mini",
            messages=messages,
            response_format={"type": "json_object"},
            temperature=0.1,
            max_tokens=4000
        )
        content_str = response.choices[0].message.content
        try:
            res_json = json.loads(content_str)
        except Exception as json_err:
            print(f"[JSON Parse Retry Warning] {json_err}, attempting partial extraction...")
            import re
            m_match = re.search(r'\{.*\}', content_str, re.DOTALL)
            if m_match:
                res_json = json.loads(m_match.group(0))
            else:
                res_json = {"storeName": store_name, "menus": []}
        
        parsed_name = res_json.get("storeName", "").strip()
        if parsed_name and not parsed_name.startswith("http") and not any(inv in parsed_name for inv in INVALID_PLATFORM_NAMES):
            store_name = parsed_name
        elif store_name_hint and not store_name_hint.startswith("http") and not any(inv in store_name_hint for inv in INVALID_PLATFORM_NAMES):
            store_name = store_name_hint

        raw_menus = res_json.get("menus", [])
        for rm in raw_menus:
            if isinstance(rm, dict) and "menuName" in rm and "price" in rm:
                try:
                    p_val = int(rm["price"])
                except Exception:
                    continue
                m_name = rm["menuName"].strip()
                if 500 <= p_val <= 500000 and len(m_name) >= 2 and m_name != store_name:
                    if not any(m['menuName'] == m_name for m in menus):
                        menus.append({
                            "menuName": m_name,
                            "price": p_val,
                            "desc": rm.get("desc", f"{store_name} 대표 메뉴"),
                            "category": rm.get("category", "메인")
                        })
        print(f"[GPT-4o-mini Master Parser] Parsed {len(menus)} clean menus for '{store_name}'")
    except Exception as e:
        print(f"[GPT-4o-mini Parser Exception] {e}")

    return store_name, menus


# -------------------------------------------------------------------
# 5. [수학 냅색 추천 서포트] 3대 프로필 & 예산 상한제 냅색 엔진
# -------------------------------------------------------------------
def custom_recommendation_engine_support(req: RecommendRequest, profile: str = "balance") -> tuple[List[MenuItem], int, List[dict]]:
    people_count = max(1, req.peopleCount or 1)
    effective_budget = req.budget if req.budget > 0 else (people_count * 35000)

    # 1. 알레르기 및 제외 음식 필터링
    valid_menus = [
        m for m in req.menuList 
        if m.price > 0 and not any(ex in m.menuName for ex in (req.excludedFoods or []))
    ]
    if not valid_menus:
        valid_menus = [m for m in req.menuList if m.price > 0]
    if not valid_menus:
        return [], 0, []

    # 2. 프로필에 따른 메뉴 필터링 및 가중치
    if profile == "signature":
        # 가격 높은 순 (대표/시그니처 위주)
        valid_menus.sort(key=lambda x: x.price, reverse=True)
    elif profile == "value":
        # 가성비 순 (합리적 가격 위주)
        valid_menus.sort(key=lambda x: x.price)
    else:
        # 밸런스형 (골고루 섞기)
        random.seed(int(time.time() * 1000) % 10000)
        random.shuffle(valid_menus)

    # 적정 인원수 대비 목표 디쉬 수:
    # 2인 = 2~3개, 3인 = 3~4개, 4인 = 4~5개 (대식가/다이어트 보정)
    target_dish_count = max(1, people_count + (req.bigEaterCount or 0) - (req.dietCount or 0))
    min_k = max(1, target_dish_count)
    max_k = min(len(valid_menus), target_dish_count + 1)

    candidate_pool = valid_menus[:min(len(valid_menus), 16)]
    candidates = []
    seen_combos = set()
    
    # 이전 추천 조합 제외 목록 정규화
    excluded_keys = set()
    for ex in (req.excludedCombos or []):
        excluded_keys.add(tuple(sorted(ex)))

    for k in range(min_k, max_k + 1):
        for combo in combinations(candidate_pool, k):
            combo_names = tuple(sorted([m.menuName for m in combo]))
            if combo_names in seen_combos or combo_names in excluded_keys:
                continue
            seen_combos.add(combo_names)

            tot_price = sum(m.price for m in combo)
            # 💡 [핵심]: 예산은 '초과 불가능한 상한선(Cap)'
            if tot_price <= effective_budget:
                # 목적 함수: 인원수 목표 개수에 근접하면서, 적정 금액 범위(예산의 50%~95%) 내에 위치
                dish_diff = abs(len(combo) - target_dish_count)
                budget_ratio = tot_price / effective_budget
                
                # 너무 적거나(30% 미만) 예산 초과(>100%) 방지
                score = 100.0 - (dish_diff * 20.0)
                if 0.5 <= budget_ratio <= 0.95:
                    score += 25.0 # 최적 만족도 보너스
                elif budget_ratio < 0.5:
                    score -= (0.5 - budget_ratio) * 40.0
                
                candidates.append({
                    "items": list(combo),
                    "totalPrice": tot_price,
                    "score": score
                })

    candidates.sort(key=lambda c: c["score"], reverse=True)

    if candidates:
        best = candidates[0]
        return best["items"], best["totalPrice"], candidates[:3]

    # Fallback: 적정 개수만큼 반환
    fallback_combo = valid_menus[:min(target_dish_count, len(valid_menus))]
    fallback_price = sum(m.price for m in fallback_combo)
    return fallback_combo, fallback_price, [{"items": fallback_combo, "totalPrice": fallback_price, "score": 50.0}]


# -------------------------------------------------------------------
# 6. [추천 총책임] GPT-4o-mini Master Concierge & 정밀 객체 매핑
# -------------------------------------------------------------------
def orchestrate_recommendation_with_gpt(
    req: RecommendRequest, 
    custom_best_combo: List[MenuItem], 
    custom_tot_price: int, 
    profile: str = "balance"
) -> tuple[List[RecommendedItem], List[str], int, str]:

    # 원본 메뉴 매핑 테이블 구축 (메뉴명 -> MenuItem)
    menu_dict: Dict[str, MenuItem] = {m.menuName: m for m in req.menuList}
    
    # 냅색 선택 결과 수량 집계
    combo_counts = Counter([m.menuName for m in custom_best_combo])
    
    # 1. 냅색이 선택한 기본 객체 생성
    recommended_items: List[RecommendedItem] = []
    for m_name, count in combo_counts.items():
        orig_menu = menu_dict.get(m_name, MenuItem(menuName=m_name, price=15000))
        item_tot = orig_menu.price * count
        recommended_items.append(RecommendedItem(
            menuName=m_name,
            price=orig_menu.price,
            count=count,
            totalItemPrice=item_tot,
            desc=orig_menu.desc or "대표 인기 메뉴",
            category=orig_menu.category or "메인"
        ))

    calculated_tot_price = sum(item.totalItemPrice for item in recommended_items)
    formatted_menu_names = [f"{item.menuName} x {item.count}" if item.count > 1 else item.menuName for item in recommended_items]

    # 2. GPT-4o-mini에게 미식 사유 작성 및 최종 검수 요청
    system_prompt = f"""
    당신은 최고급 다이닝의 총괄 마스터 AI 미식 컨시어지(Master Gourmet Concierge)입니다.
    
    [입력 조건]
    - 일행: {req.peopleCount}명 ({req.meetingType or '모임'})
    - 총 예산 한도: {req.budget:,}원 (초과 불가 상한선)
    - 대식가: {req.bigEaterCount or 0}명, 다이어트: {req.dietCount or 0}명
    - 매운맛: {req.spicyLevel}/5, 선호도: '{req.todayPreference or '없음'}'
    
    [추천 조합 확정 내역]
    - 메뉴: {json.dumps([{'name': it.menuName, 'price': it.price, 'count': it.count} for it in recommended_items], ensure_ascii=False)}
    - 총 합산 금액: {calculated_tot_price:,}원
    
    [임무]
    손님들에게 신뢰와 만족감을 주는 센스 있고 매력적인 2문장의 맞춤 추천 사유('reason')를 작성하세요.
    반드시 JSON 형식 {{"reason": "..."}} 으로만 응답하세요.
    """

    try:
        response = client.chat.completions.create(
            model="gpt-4o-mini",
            messages=[{"role": "system", "content": system_prompt}],
            response_format={"type": "json_object"},
            temperature=0.7,
            max_tokens=250
        )
        res_json = json.loads(response.choices[0].message.content)
        final_reason = res_json.get("reason", f"{req.peopleCount}인의 예산({req.budget:,}원)과 {req.meetingType}에 꼭 맞춘 최적의 미식 조합입니다!")
    except Exception as e:
        print(f"[GPT-4o-mini Reason Exception] {e}")
        final_reason = f"{req.peopleCount}인의 예산({req.budget:,}원)과 {req.meetingType} 분위기에 어울리는 최상의 미식 조합입니다!"

    return recommended_items, formatted_menu_names, calculated_tot_price, final_reason


# -------------------------------------------------------------------
# 7. FastAPI Endpoints
# -------------------------------------------------------------------

@app.post("/ai/parse-image")
async def parse_image_endpoint(file: UploadFile = File(...)):
    contents = await file.read()
    print(f"[Universal Menu Parser] Processing image size: {len(contents)} bytes, filename: {file.filename}")

    # 1. 범용 다중 엔진 QR 코드 검사 (Pyzbar + OpenCV CLAHE + 회전)
    qr_url = universal_decode_qr(contents)
    if qr_url:
        print(f"[Universal QR Decoded Successfully] {qr_url}")
        # 🌐 Playwright 실시간 동적 렌더링 수집기 실행
        final_dest_url, live_scraped_text = await scrape_dynamic_web_content(qr_url)
        
        store_name, live_menus = orchestrate_menu_parsing_with_gpt(
            image_bytes=None, 
            web_raw_text=live_scraped_text, 
            store_name_hint=final_dest_url
        )
        if live_menus:
            return {
                "filename": file.filename,
                "storeName": store_name,
                "menuCount": len(live_menus),
                "menus": live_menus,
                "status": "success",
                "qrUrl": qr_url,
                "finalUrl": final_dest_url,
                "architecture": "PLAYWRIGHT_DYNAMIC_DOM_GPT_PARSER"
            }

    # 2. EasyOCR + GPT-4o-mini Vision 융합 스캔
    store_name, ocr_menus = orchestrate_menu_parsing_with_gpt(image_bytes=contents, store_name_hint=file.filename)

    return {
        "filename": file.filename,
        "storeName": store_name,
        "menuCount": len(ocr_menus),
        "menus": ocr_menus,
        "status": "success" if ocr_menus else "empty",
        "architecture": "GPT_4O_MINI_EASYOCR_VISION_SUPPORT"
    }


@app.get("/ai/parse-url")
async def parse_url_endpoint(url: str):
    if not url or not url.strip():
        raise HTTPException(status_code=400, detail="URL 또는 상호명이 입력되지 않았습니다.")

    input_str = url.strip()
    print(f"[Master Web Scraper] Processing Input: {input_str}")
    
    is_url = input_str.startswith("http://") or input_str.startswith("https://")

    if is_url:
        final_dest_url, raw_html_text = await scrape_dynamic_web_content(input_str)
        store_name, live_menus = orchestrate_menu_parsing_with_gpt(
            image_bytes=None, 
            web_raw_text=raw_html_text, 
            store_name_hint=final_dest_url
        )
    else:
        raw_html_text = f"사용자가 직접 입력한 식당/매장 이름: {input_str}"
        store_name, live_menus = orchestrate_menu_parsing_with_gpt(
            image_bytes=None, 
            web_raw_text=raw_html_text, 
            store_name_hint=input_str
        )

    return {
        "url": url,
        "storeName": store_name,
        "menuCount": len(live_menus),
        "menus": live_menus,
        "status": "success" if live_menus else "empty",
        "architecture": "PLAYWRIGHT_LIVE_DYNAMIC_SCRAPER"
    }


@app.post("/ai/recommend", response_model=RecommendResponse)
def recommend_menu(req: RecommendRequest, mode: str = Query("hybrid")):
    if not req.menuList:
        raise HTTPException(status_code=400, detail="메뉴 리스트가 비어있습니다.")

    profile = req.presetProfile or "balance"
    custom_items, custom_price, candidates = custom_recommendation_engine_support(req, profile=profile)
    
    rec_items, rec_names, final_price, final_reason = orchestrate_recommendation_with_gpt(
        req=req,
        custom_best_combo=custom_items,
        custom_tot_price=custom_price,
        profile=profile
    )

    return RecommendResponse(
        recommendedItems=rec_items,
        recommendedMenus=rec_names,
        totalPrice=final_price,
        reason=final_reason,
        engineType="GPT_CHIEF_CUSTOM_KNAPSACK_SUPPORT",
        profile=profile
    )


@app.put("/ai/re-recommend", response_model=RecommendResponse)
def re_recommend_menu(req: ReRecommendRequest, mode: str = Query("hybrid")):
    if not req.menuList:
        raise HTTPException(status_code=400, detail="메뉴 리스트가 비어있습니다.")

    # 다른 조합 추천 시 프로필 순환 (balance -> signature -> value)
    current_profile = req.presetProfile or "balance"
    profile_cycle = {"balance": "signature", "signature": "value", "value": "balance"}
    next_profile = profile_cycle.get(current_profile, "balance")

    custom_items, custom_price, candidates = custom_recommendation_engine_support(req, profile=next_profile)
    
    rec_items, rec_names, final_price, final_reason = orchestrate_recommendation_with_gpt(
        req=req,
        custom_best_combo=custom_items,
        custom_tot_price=custom_price,
        profile=next_profile
    )

    return RecommendResponse(
        recommendedItems=rec_items,
        recommendedMenus=rec_names,
        totalPrice=final_price,
        reason=final_reason,
        engineType="GPT_CHIEF_CUSTOM_KNAPSACK_SUPPORT",
        profile=next_profile
    )
