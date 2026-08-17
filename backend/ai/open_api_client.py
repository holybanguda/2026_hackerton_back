import os
import requests
from typing import Optional, Dict

class FoodSafetyApiClient:
    """
    식품의약품안전처 식품영양성분 공공 API 연동 클라이언트 (REST API)
    (공공데이터포털 data.go.kr API Key 활용)
    """
    def __init__(self):
        # 환경변수 또는 기본 서비스키
        self.service_key = os.getenv("FOOD_SAFETY_API_KEY", "YOUR_PUBLIC_DATA_API_KEY")
        self.base_url = "http://apis.data.go.kr/1471000/FoodNtrIrdntInfoService1/getFoodNtrItdntList1"

    def fetch_food_nutrition(self, food_name: str) -> Optional[Dict]:
        """
        식당 메뉴명을 받아 식약처 공공 API에서 1회 제공량(g), 칼로리(kcal) 실시간 조회
        """
        if not self.service_key or self.service_key == "YOUR_PUBLIC_DATA_API_KEY":
            return None

        params = {
            "serviceKey": self.service_key,
            "desc_kor": food_name,  # 음식명 검색
            "pageNo": 1,
            "numOfRows": 1,
            "type": "json"
        }

        try:
            response = requests.get(self.base_url, params=params, timeout=2.5)
            if response.status_code == 200:
                data = response.json()
                items = data.get("body", {}).get("items", [])
                if items:
                    item = items[0]
                    # 1회 제공량(g) 및 칼로리 수치 추출
                    serving_wt_str = str(item.get("SERVING_WT", "200") or "200")
                    cal_str = str(item.get("NUTR_CONT1", "0") or "0")

                    import re
                    serving_wt = float(re.sub(r'[^0-9.]', '', serving_wt_str) or 200)
                    calories = float(re.sub(r'[^0-9.]', '', cal_str) or 0)
                    
                    # 포만감 지수(portionIndex) 자동 환산
                    portion = 1.0
                    if serving_wt >= 300:
                        portion = 1.5
                    elif serving_wt <= 100:
                        portion = 0.5

                    return {
                        "foodName": item.get("DESC_KOR", food_name),
                        "referenceGram": serving_wt,
                        "calories": calories,
                        "portionIndex": portion,
                        "source": "식품의약품안전처 공공 API"
                    }
        except Exception as e:
            print(f"[API Info] 식약처 API 호출 스킵 (로컬 DB 우선 사용): {e}")

        return None

# 전역 싱글톤 API 클라이언트
api_client = FoodSafetyApiClient()
