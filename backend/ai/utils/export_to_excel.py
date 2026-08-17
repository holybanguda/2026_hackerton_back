import os
import json
import pandas as pd

def convert_v3_json_to_excel():
    """
    korean_restaurant_menu_dataset_150_v3.json 파일의 150개 점포별 메뉴 데이터를 
    한눈에 파악하기 쉬운 엑셀(.xlsx) 및 CSV 파일로 변환
    """
    base_dir = os.path.dirname(__file__)
    workspace_dir = os.path.dirname(base_dir)
    json_path = os.path.join(workspace_dir, "json", "korean_restaurant_menu_dataset_150_v4.json")
    excel_path = os.path.join(workspace_dir, "json", "korean_restaurant_menu_dataset_150_v4.xlsx")
    csv_path = os.path.join(workspace_dir, "json", "korean_restaurant_menu_dataset_150_v4.csv")

    if not os.path.exists(json_path):
        print(f"[Error] v3 JSON 파일이 존재하지 않습니다: {json_path}")
        return

    print(f"[Info] v3 JSON 데이터 변환 시작: {json_path}")
    with open(json_path, "r", encoding="utf-8") as f:
        data = json.load(f)

    menu_rows = []
    restaurant_summary = []

    for rest in data.get("restaurants", []):
        rest_id = rest.get("restaurant_id", "")
        rest_name = rest.get("restaurant_name", "")
        menu_list = rest.get("menu_list", [])

        main_count = 0
        side_count = 0
        drink_count = 0
        total_price = 0

        for menu in menu_list:
            m_type = menu.get("menu_type", "MAIN")
            price = menu.get("price", 0)
            total_price += price

            if m_type == "MAIN":
                main_count += 1
            elif m_type == "SIDE":
                side_count += 1
            else:
                drink_count += 1

            # 리스트 형태 데이터의 직관적 문자열 변환
            allergies_str = ", ".join(menu.get("allergies", []))
            ingredients_str = ", ".join(menu.get("ingredients", []))
            tags_str = ", ".join(menu.get("tags", []))
            pairings_str = ", ".join(menu.get("recommended_pairing_names", []))

            menu_rows.append({
                "점포ID": rest_id,
                "점포명": rest_name,
                "메뉴ID": menu.get("menu_id", ""),
                "메뉴명": menu.get("menu_name", ""),
                "가격(원)": price,
                "메뉴구분": m_type,
                "매운맛(1~5)": menu.get("spicy_level", 0),
                "포만감지수": menu.get("portion_index", 1.0),
                "알레르기성분": allergies_str,
                "원재료": ingredients_str,
                "태그": tags_str,
                "추천주류/안주궁합": pairings_str
            })

        avg_price = int(total_price / len(menu_list)) if menu_list else 0
        restaurant_summary.append({
            "점포ID": rest_id,
            "점포명": rest_name,
            "총메뉴수": len(menu_list),
            "평균메뉴가격(원)": avg_price,
            "메인메뉴수": main_count,
            "사이드메뉴수": side_count,
            "주류/음료수": drink_count
        })

    # DataFrames 생성
    df_menus = pd.DataFrame(menu_rows)
    df_summary = pd.DataFrame(restaurant_summary)

    # 1. 엑셀 파일 저장 (다중 시트: 전체 메뉴 목록 + 점포별 요약)
    try:
        with pd.ExcelWriter(excel_path, engine='openpyxl') as writer:
            df_summary.to_excel(writer, sheet_name='점포별_요약', index=False)
            df_menus.to_excel(writer, sheet_name='전체_메뉴_상세목록', index=False)
        print(f"[OK] [1/2] 엑셀 파일 변환 완료: {excel_path}")
    except Exception as e:
        print(f"[Warning] openpyxl 미설치 시 엑셀 저장 건너뜀 ({e})")

    # 2. CSV 파일 저장 (cp949 인코딩으로 엑셀 열람 시 한글 깨짐 방지)
    df_menus.to_csv(csv_path, index=False, encoding='utf-8-sig')
    print(f"[OK] [2/2] CSV 파일 변환 완료: {csv_path}")

    print("==================================================================")
    print(f"총 {len(restaurant_summary)}개 점포, {len(menu_rows)}개 메뉴 변환 성공!")
    print("==================================================================")

if __name__ == "__main__":
    convert_v3_json_to_excel()
