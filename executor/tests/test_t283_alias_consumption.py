
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from driver.env_resolver import (  # noqa: E402
    VarStore,
    resolve_alias,
    resolve_global,
    resolve_vars,
)


class TestBareAliasResolution(unittest.TestCase):
    pass

    def test_裸别名命中_解析到规范池键(self):
        store = VarStore({"3.orderNo": "R-9"}, alias_map={"创建订单": "3.orderNo"})
        out = resolve_alias({"v": "{{创建订单.订单号}}"}, store)
        self.assertEqual(out["v"], "R-9")

    def test_stepNo漂移_改别名一行不断链(self):
        ref = {"v": "{{创建订单.订单号}}"}
        before = VarStore({"3.orderNo": "R-9"}, alias_map={"创建订单": "3.orderNo"})
        self.assertEqual(resolve_alias(ref, before)["v"], "R-9")
        after = VarStore({"5.orderNo": "R-9"}, alias_map={"创建订单": "5.orderNo"})
        self.assertEqual(resolve_alias(ref, after)["v"], "R-9")

    def test_直址对照_池键含点形态直址命中(self):
        store = VarStore({"3.orderNo": "R-9"})
        out = resolve_alias({"v": "{{3.orderNo}}"}, store)
        self.assertEqual(out["v"], "R-9")

    def test_别名登记但池未产出_原样穿透保真(self):
        store = VarStore({"其他键": "x"}, alias_map={"创建订单": "3.orderNo"})
        out = resolve_alias({"v": "{{创建订单.订单号}}"}, store)
        self.assertEqual(out["v"], "{{创建订单.订单号}}")

    def test_未登记未直址_原样穿透(self):
        store = VarStore({"a": "1"})
        out = resolve_alias({"v": "{{买家.token}}"}, store)
        self.assertEqual(out["v"], "{{买家.token}}",
                         "角色裸形态（T275 消费面）本解析器禁吞禁改写")


class TestNamespaceOrthogonalBare(unittest.TestCase):
    pass

    def test_保留命名空间头_别名表同名词也不劫持(self):
        store = VarStore({"X": "pool", "global.Y": "g"},
                         alias_map={"global": "X", "RUN": "X"})
        out = resolve_alias({"a": "{{global.Y}}", "b": "{{RUN.X}}",
                             "c": "{{ENV:k}}", "d": "{{DATA:m}}",
                             "e": "{{VAR:n}}", "f": "{{SESSION:s}}"}, store)
        self.assertEqual(out["a"], "{{global.Y}}")
        self.assertEqual(out["b"], "{{RUN.X}}")
        self.assertEqual(out["c"], "{{ENV:k}}")
        self.assertEqual(out["d"], "{{DATA:m}}")
        self.assertEqual(out["e"], "{{VAR:n}}")
        self.assertEqual(out["f"], "{{SESSION:s}}")

    def test_别名表空_行为恒等零迁移(self):
        store = VarStore({"k": "v"})
        out = resolve_alias({"v": "{{RUN.k}}", "w": "{{不存在.字段}}"}, store)
        self.assertEqual(out["v"], "{{RUN.k}}", "本解析器禁吞 RUN 形态")
        self.assertEqual(out["w"], "{{不存在.字段}}")

    def test_与既有解析链顺序位兼容(self):
        store = VarStore({"orderNo": "R-7", "3.uid": "U-3"},
                         alias_map={"下单步骤": "3.uid"})
        node = {"u": "{{VAR:orderNo}}/{{下单步骤}}/{{下单步骤.字段}}"}
        out = resolve_vars(node, store)
        out = resolve_global(out, store)
        out = resolve_alias(out, store)
        self.assertEqual(out["u"], "R-7/{{下单步骤}}/U-3")


class TestClaimEnvelopeStepAliasesWiring(unittest.TestCase):
    pass

    def test_旧core缺键_空别名表恒等零破坏(self):
        import api_runner

        self.assertTrue(hasattr(api_runner, "run_api_task"))
        import inspect

        params = inspect.signature(api_runner.run_api_task).parameters
        self.assertIn("step_aliases", params,
                      "run_api_task 需尾追 step_aliases 消费参（旧 core 缺键恒等）")


if __name__ == "__main__":
    unittest.main()
