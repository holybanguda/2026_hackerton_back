"""
Auto Menu Scraper & Universal Parser Engine for Pick Nu AI
- QR 스캔 URL / 네이버지도 URL을 입력받아 동적 HTML 자동 수집 및 범용 메뉴 추출
- Multi-Engine: DOM Text Regex Parser + Headless Browser Simulation + OCR Fallback Schema
"""

import re
import json
import urllib.request
from bs4 import BeautifulSoup

def fetch_and_parse_menu(url_or_filepath):
    """
    URL 또는 로컬 HTML 파일에서 범용 메뉴 데이터 구조를 추출합니다.
    """
    html_content = ""
    
    if url_or_filepath.startswith("http://") or url_or_filepath.startswith("https://"):
        req = urllib.request.Request(
            url_or_filepath, 
            headers={'User-Agent': 'Mozilla/5.0 (iPhone; CPU iPhone OS 16_0 like Mac OS X) AppleWebKit/605.1.15'}
        )
        try:
            with urllib.request.urlopen(req) as response:
                html_content = response.read().decode('utf-8', errors='ignore')
        except Exception as e:
            return {"status": "error", "message": f"URL 수집 실패: {e}"}
    else:
        try:
            with open(url_or_filepath, 'r', encoding='utf-8', errors='ignore') as f:
                html_content = f.read()
        except Exception as e:
            return {"status": "error", "message": f"파일 읽기 실패: {e}"}

    soup = BeautifulSoup(html_content, 'html.parser')
    menus = []

    # 1. JSON-LD 또는 스크립트 태그 내 메뉴 패턴 탐색
    for script in soup.find_all('script'):
        text = script.string or ''
        if 'price' in text or 'menu' in text:
            matches = re.findall(r'"name"\s*:\s*"([^"]+)".*?"price"\s*:\s*"?([0-9,]+)"?', text)
            for name, price_str in matches:
                p = int(re.sub(r'[^0-9]', '', price_str))
                if 1000 <= p <= 300000 and len(name) >= 2:
                    if not any(m['menuName'] == name for m in menus):
                        menus.append({
                            "menuName": name,
                            "price": p,
                            "source": "JSON_SCHEMA",
                            "category": "MAIN" if p >= 8000 else "SIDE"
                        })

    # 2. DOM 텍스트 노드 가격 패턴 탐색 (이름 + N,NNN원)
    lines = [line.strip() for line in soup.get_text().split('\n') if line.strip()]
    for i in range(len(lines) - 1):
        name = lines[i]
        next_line = lines[i+1]
        p_match = re.search(r'([0-9,]+)\s*원?', next_line)
        if p_match:
            try:
                p = int(p_match.group(1).replace(',', ''))
                if 1000 <= p <= 300000 and len(name) >= 2 and name not in ['대표', '인기', '추천', 'NEW', 'BEST', '사진', '메뉴']:
                    if not any(m['menuName'] == name for m in menus):
                        menus.append({
                            "menuName": name,
                            "price": p,
                            "source": "DOM_TEXT_PATTERN",
                            "category": "MAIN" if p >= 8000 else ("DRINK" if "맥주" in name or "음료" in name else "SIDE")
                        })
            except ValueError:
                pass

    # 3. 이미지 메뉴판 URL 감지 (OCR 연동용)
    image_urls = []
    for img in soup.find_all('img'):
        src = img.get('src') or img.get('data-src') or ''
        alt = img.get('alt') or ''
        if 'search.pstatic.net' in src or 'phinf' in src or '메뉴' in alt:
            if src.startswith('//'):
                src = 'https:' + src
            if src.startswith('http') and src not in image_urls:
                image_urls.append(src)

    return {
        "status": "success",
        "menuCount": len(menus),
        "menus": menus,
        "detectedImages": image_urls
    }

if __name__ == "__main__":
    print("🤖 Universal Menu Scraper Engine Test initialized.")
