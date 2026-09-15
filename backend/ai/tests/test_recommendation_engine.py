"""추천 점수식은 그대로 두고 제외 조합/fallback 동작을 검증한다.
main.py의 실제 순수 함수 AST를 실행하여 OCR 초기화, 모델 다운로드 및 외부 AI 호출을 차단한다.
"""
import ast
import itertools
import pathlib
import random
import time
import unittest
from types import SimpleNamespace

class HTTPException(Exception):
    def __init__(self, status_code, detail):
        self.status_code = status_code
        self.detail = detail

source = pathlib.Path(__file__).resolve().parents[1] / "main.py"
tree = ast.parse(source.read_text(encoding="utf-8"))
function = next(node for node in tree.body if isinstance(node, ast.FunctionDef)
                and node.name == "custom_recommendation_engine_support")
module = ast.Module(body=[ast.ImportFrom(module="__future__", names=[ast.alias(name="annotations")], level=0), function], type_ignores=[])
namespace = dict(combinations=itertools.combinations, random=random, time=time, HTTPException=HTTPException)
exec(compile(ast.fix_missing_locations(module), str(source), "exec"), namespace)
engine = namespace["custom_recommendation_engine_support"]

def request(names=("A", "B", "C"), prices=None, people=2, budget=1000, excluded=None, foods=None):
    return SimpleNamespace(
        menuList=[SimpleNamespace(menuName=n, price=p) for n, p in zip(names, prices or [100]*len(names))],
        peopleCount=people, budget=budget, bigEaterCount=0, dietCount=0,
        excludedCombos=excluded or [], excludedFoods=foods or [])

class RecommendationEngineTest(unittest.TestCase):
    def test_order_independent_exclusion(self):
        items, _, _ = engine(request(excluded=[["B","A"]]), "value")
        self.assertNotEqual(sorted(m.menuName for m in items), ["A","B"])

    def test_fallback_does_not_repeat_excluded_combination(self):
        items, _, _ = engine(request(names=("A","B"), people=5, excluded=[["A","B"]]), "value")
        self.assertNotEqual(sorted(m.menuName for m in items), ["A","B"])

    def test_exhausted_candidates_have_machine_readable_error(self):
        with self.assertRaises(HTTPException) as ctx:
            engine(request(names=("A","B"), excluded=[["A"],["B"],["A","B"]]), "value")
        self.assertEqual(ctx.exception.status_code,409)
        self.assertEqual(ctx.exception.detail["code"],"NO_ALTERNATIVE_COMBINATION")

    def test_fallback_obeys_budget(self):
        items, price, _ = engine(request(prices=[500,100,900], budget=150, people=5), "value")
        self.assertEqual(price,100)
        self.assertEqual([m.menuName for m in items],["B"])

    def test_no_affordable_item_is_error(self):
        with self.assertRaises(HTTPException):
            engine(request(budget=1), "value")

    def test_excluded_foods_are_not_reintroduced(self):
        with self.assertRaises(HTTPException):
            engine(request(names=("새우",),foods=["새우"]), "value")

    def test_quantity_uses_repeated_names(self):
        # 동일 이름 두 행은 count=2에 해당하며 ["A","A"]로 제외된다.
        items, _, _ = engine(request(names=("A","A"), excluded=[["A","A"]]), "value")
        self.assertEqual(len(items),1)

if __name__ == "__main__":
    unittest.main()

