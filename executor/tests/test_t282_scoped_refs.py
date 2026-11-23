
from __future__ import annotations

import unittest

from driver.env_resolver import (
    GlobalVarUndefinedError,
    VarStore,
    VarUndefinedError,
    resolve_global,
    resolve_vars,
)


class TestRunSynonym(unittest.TestCase):
    pass

    def test_RUN形态解析同store值(self):
        store = VarStore({"订单号": "R-20260929"})
        out = resolve_vars({"v": "{{RUN.订单号}}"}, store)
        self.assertEqual(out["v"], "R-20260929")

    def test_RUN与VAR互相可见同存储(self):
        store = VarStore({})
        store.set("token", "tk-1")
        out = resolve_vars({"h": "{{RUN.token}}"}, store)
        self.assertEqual(out["h"], "tk-1")
        out2 = resolve_vars({"h": "{{VAR:token}}"}, store)
        self.assertEqual(out2["h"], "tk-1")

    def test_RUN键文法容中文点号连字符(self):
        store = VarStore({"a.b-c": "v1"})
        out = resolve_vars({"x": "{{RUN.a.b-c}}"}, store)
        self.assertEqual(out["x"], "v1")

    def test_RUN缺键_fail_closed_一次点名(self):
        store = VarStore({})
        with self.assertRaises(VarUndefinedError) as cm:
            resolve_vars({"x": "{{RUN.缺甲}}-{{RUN.缺乙}}"}, store)
        self.assertEqual(sorted(cm.exception.missing_vars), ["缺乙", "缺甲"])

    def test_既有VAR形态零回归(self):
        store = VarStore({"n1": "v1"})
        out = resolve_vars({"a": "{{VAR:n1}}", "b": "{{VAR:n1}}x"}, store)
        self.assertEqual(out["a"], "v1")
        self.assertEqual(out["b"], "v1x")


class TestGlobalRef(unittest.TestCase):
    pass

    def test_剥前缀回退_种子形状混存可消费(self):
        store = VarStore({"库存": "99"})
        out = resolve_global({"v": "{{global.库存}}"}, store)
        self.assertEqual(out["v"], "99")

    def test_带前缀原键优先(self):
        store = VarStore({"global.X": "pref", "X": "bare"})
        out = resolve_global({"v": "{{global.X}}"}, store)
        self.assertEqual(out["v"], "pref")

    def test_同串多占位符与嵌套结构(self):
        store = VarStore({"k1": "a", "k2": "b"})
        doc = {"steps": [{"v": "{{global.k1}}/{{global.k2}}/{{global.k1}}"}]}
        out = resolve_global(doc, store)
        self.assertEqual(out["steps"][0]["v"], "a/b/a")

    def test_缺键_人话点名零实值(self):
        store = VarStore({"有值": "secret-1"})
        with self.assertRaises(GlobalVarUndefinedError) as cm:
            resolve_global({"v": "{{global.缺失键}}", "s": "{{global.有值}}"}, store)
        self.assertEqual(cm.exception.missing_keys, ["缺失键"])
        self.assertNotIn("secret-1", str(cm.exception))

    def test_无引用文档零改动新结构返回(self):
        doc = {"a": "{{VAR:n}}", "b": "{{ENV:U}}", "c": "{{买家.token}}"}
        out = resolve_global(doc, VarStore({}))
        self.assertEqual(out, doc)
        self.assertIsNot(out, doc)


class TestNamespaceOrthogonal(unittest.TestCase):
    pass

    def test_resolve_vars不动global形态(self):
        store = VarStore({"X": "poolval"})
        out = resolve_vars({"v": "{{global.X}}"}, store)
        self.assertEqual(out["v"], "{{global.X}}",
                         "VAR/RUN 解析器禁吞 global 形态")

    def test_resolve_global不动var与角色形态(self):
        store = VarStore({"X": "1"})
        out = resolve_global({"v": "{{VAR:X}}-{{RUN.X}}-{{买家.token}}"}, store)
        self.assertEqual(out["v"], "{{VAR:X}}-{{RUN.X}}-{{买家.token}}")

    def test_别名表空_恒等零迁移(self):
        store = VarStore({"直址": "d"})
        self.assertIsNone(store.lookup_alias("别名候选"))
        out = resolve_vars({"v": "{{RUN.直址}}"}, store)
        self.assertEqual(out["v"], "d")

    def test_别名表非空_先查别名回退直址(self):
        store = VarStore({"3.orderNo": "R-9"}, alias_map={"创建订单": "3.orderNo"})
        self.assertEqual(store.lookup_alias("创建订单"), "3.orderNo")
        self.assertIsNone(store.lookup_alias("未登记"))
        out = resolve_vars({"v": "{{RUN.创建订单}}"}, store)
        self.assertEqual(out["v"], "R-9")


if __name__ == "__main__":
    unittest.main()
