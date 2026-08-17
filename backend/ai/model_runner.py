import os
import re
import pickle
from typing import List, Tuple
from bs4 import BeautifulSoup
from kiwipiepy import Kiwi
from pydantic import BaseModel

class MenuItemWithCategory(BaseModel):
    menuName: str
    price: int
    category: str  # "MAIN", "SIDE", "DRINK", "ALCOHOL"

# 싱글톤 Kiwi 형태소 분석기
kiwi = Kiwi()

def kiwi_tokenizer(text: str):
    tokens = []
    for token in kiwi.tokenize(text):
        if token.tag in ('NNG', 'NNP', 'XR', 'SL'):
            tokens.append(token.form)
    return tokens if tokens else text.split()

class ModelRunner:
    _instance = None

    def __new__(cls):
        if cls._instance is None:
            cls._instance = super(ModelRunner, cls).__new__(cls)
            cls._instance._init_model()
        return cls._instance

    def _init_model(self):
        model_path = os.path.join(os.path.dirname(__file__), "menu_classifier.pkl")
        
        # 모델 파일이 없으면 자동으로 train_model 실행 후 로드
        if not os.path.exists(model_path):
            import subprocess
            print("menu_classifier.pkl 파일을 찾을 수 없어 자동 학습을 시작합니다...")
            train_script = os.path.join(os.path.dirname(__file__), "train_model.py")
            subprocess.run(["python", train_script], check=True)

        with open(model_path, "rb") as f:
            self.pipeline = pickle.load(f)
        print("Kiwi 초경량 한국어 ML 분류 모델 로드 완료 (0.005초 파싱 준비됨)")

    def predict_category(self, menu_name: str) -> str:
        """메뉴명 카테고리 예측 (MAIN, SIDE, DRINK, ALCOHOL)"""
        try:
            return self.pipeline.predict([menu_name])[0]
        except Exception:
            return "MAIN"

    def parse_menu_ml(self, raw_content: str) -> List[MenuItemWithCategory]:
        """
        Kiwi 형태소 분석기 + 정규식 + ML 카테고리 분류를 결합한 0.005초 자체 초경량 파서
        """
        soup = BeautifulSoup(raw_content, "html.parser") if ("<html" in raw_content.lower() or "<div" in raw_content.lower()) else None
        text_content = soup.get_text(separator="\n") if soup else raw_content

        lines = [line.strip() for line in text_content.splitlines() if line.strip()]
        parsed_menu = []

        pattern = re.compile(r"([가-힣a-zA-Z0-9\s\(\)]+?)[\s\:\-]+?([\d,]{3,7})\s*원?")

        for line in lines:
            match = pattern.search(line)
            if match:
                name = match.group(1).strip()
                price_str = match.group(2).replace(",", "").strip()

                if 2 <= len(name) <= 25 and price_str.isdigit():
                    price = int(price_str)
                    if 1000 <= price <= 500000:
                        # Kiwi 형태로 조사 떼어내고 순수 명사 가공
                        tokens = kiwi.tokenize(name)
                        clean_name = " ".join([t.form for t in tokens if t.tag in ('NNG', 'NNP', 'XR', 'SL', 'SN')])
                        final_name = clean_name if clean_name else name

                        category = self.predict_category(final_name)
                        parsed_menu.append(MenuItemWithCategory(
                            menuName=final_name,
                            price=price,
                            category=category
                        ))

        # 중복 제거
        unique_menu = {}
        for item in parsed_menu:
            if item.menuName not in unique_menu:
                unique_menu[item.menuName] = item

        return list(unique_menu.values())

# 전역 러너 인스턴스
runner = ModelRunner()
