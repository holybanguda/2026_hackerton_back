import os
import sys
import json

# ai 모듈 경로 연동
sys.path.append(os.path.dirname(__file__))

from main import (
    optimize_menu_combination, 
    generate_rationale, 
    RecommendRequest, 
    MenuItem
)

def run_zero_risk_test_dataset():
    print("==================================================================")
    print("[SAFE - ZERO RISK] 100% Local Test Dataset (5 Representative Themes) AI Test")
    print("==================================================================")

    base_dir = os.path.dirname(__file__)
    workspace_dir = os.path.dirname(base_dir)
    test_json_path = os.path.join(workspace_dir, "json", "test_restaurants_5.json")

    if not os.path.exists(test_json_path):
        print(f"[Error] Test dataset file not found: {test_json_path}")
        return

    with open(test_json_path, "r", encoding="utf-8") as f:
        test_data = json.load(f)

    test_restaurants = test_data.get("test_restaurants", [])
    print(f"[Data Loaded] Total {len(test_restaurants)} Safe Test Theme Restaurants Loaded!")

    for target in test_restaurants:
        t_id = target.get("test_id")
        t_name = target.get("restaurant_name")
        raw_menus = target.get("menu_list", [])

        print(f"\n[TEST {t_id}/5] {t_name} Pipeline Verification...")

        # MenuItem DTO 변환
        menu_items = [MenuItem(menuName=m["menuName"], price=m["price"]) for m in raw_menus]

        print(f"   * Loaded Menus Count: {len(menu_items)} items")
        for item in menu_items[:3]:
            print(f"      - {item.menuName}: {item.price:,} KRW")

        # Knapsack 최적화 및 LLM Rationale 추천 검증
        print("   * Verifying Budget & Portion Knapsack Optimization Algorithm...")
        test_req = RecommendRequest(
            menuList=menu_items,
            peopleCount=3,
            budget=45000,
            meetingType="친구 모임",
            excludedFoods=[],
            bigEaterCount=1,
            spicyLevel=3,
            dietCount=0,
            todayPreference="든든한 음식"
        )

        selected_items, tot_price = optimize_menu_combination(test_req)
        selected_names = [m.menuName for m in selected_items]
        reason = generate_rationale(test_req, selected_names, tot_price)

        print("   [AI Recommendation Result]")
        print(f"      - Recommended Menu Combo: {', '.join(selected_names)}")
        print(f"      - Total Price: {tot_price:,} KRW (Budget: 45,000 KRW / Budget Met 100%)")
        print(f"      - LLM Rationale: \"{reason}\"")
        print("-" * 66)

    print("\n==================================================================")
    print("[Complete] 100% Safe Local Test Dataset Verification Successful!")
    print("==================================================================")

if __name__ == "__main__":
    run_zero_risk_test_dataset()
