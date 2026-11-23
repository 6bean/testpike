""" mock response template mirror (executor side of common MockTemplateResolver).

Grammar vectors mirror MockTemplateResolverTest on the Java side one-to-one:
same placeholder pattern, same closed whitelist, same fail-closed behaviour —
a raw {{mock.*}} token never leaks into a rendered payload. Cross-language RNG
bitstreams need not match; reproducibility semantics = same seed replays the
same value on this side too (genkit mirror discipline).
"""
from __future__ import annotations

import json
import re
import unittest


def ctx(**over):
    base = {
        "method": "POST",
        "path": "/mocks/demo/orders/42",
        "path_vars": {"user": "u-7", "orderId": "42"},
        "query": {"page": ["3", "9"], "ids": ["a", "b", "c"]},
        "headers": {"X-Trace-Id": "t-1", "Content-Type": "application/json"},
        "body_json": json.dumps({"data": {"name": "zhangsan"}, "flag": True,
                                 "items": [{"n": 1}]}),
    }
    base.update(over)
    return base


class TestMockTemplateEchoFamily(unittest.TestCase):

    def resolve(self, text, request, events=None):
        from driver.env_resolver import resolve_mock
        return resolve_mock(text, request, events if events is not None else [])

    def test_pathvar_query_header_echo(self):
        got = self.resolve("u={{mock.pathVar.user}} p={{mock.query.page}} "
                           "t={{mock.header.X-Trace-Id}}", ctx())
        self.assertEqual("u=u-7 p=3 t=t-1", got)

    def test_header_lookup_case_insensitive(self):
        self.assertEqual("t-1", self.resolve("{{mock.header.x-trace-id}}", ctx()))

    def test_query_multi_values_first_wins(self):
        self.assertEqual("3", self.resolve("{{mock.query.page}}", ctx()))

    def test_body_jsonpath_restricted_subset(self):
        self.assertEqual("zhangsan", self.resolve("{{mock.body '$.data.name'}}", ctx()))
        self.assertEqual("true", self.resolve("{{mock.body '$.flag'}}", ctx()))
        self.assertEqual("1", self.resolve("{{mock.body '$.items[0].n'}}", ctx()))

    def test_unknown_function_fails_closed_naming_available(self):
        with self.assertRaises(ValueError) as err:
            self.resolve("{{mock.uuid()}}", ctx())
        msg = str(err.exception)
        self.assertIn("未注册的函数", msg)
        for fn in ("pathVar", "query", "header", "body", "now", "randomInt", "cycle"):
            self.assertIn(fn, msg)

    def test_missing_reference_fails_closed_never_leaks_raw(self):
        for token in ("{{mock.pathVar.nope}}", "{{mock.query.nope}}",
                      "{{mock.header.nope}}"):
            with self.assertRaises(ValueError):
                self.resolve(token, ctx())

    def test_body_path_outside_subset_rejected(self):
        with self.assertRaises(ValueError) as err:
            self.resolve("""{{mock.body '$.data..name'}}""", ctx())
        self.assertIn("受限子集", str(err.exception))

    def test_body_without_quotes_rejected(self):
        with self.assertRaises(ValueError) as err:
            self.resolve("{{mock.body $.data.name}}", ctx())
        self.assertIn("带引号", str(err.exception))

    def test_bare_key_shape_rejected(self):
        with self.assertRaises(ValueError):
            self.resolve("{{mock.query}}", ctx())


class TestMockTemplateFunctionFamily(unittest.TestCase):

    def resolve(self, text, request, events=None):
        from driver.env_resolver import resolve_mock
        return resolve_mock(text, request, events if events is not None else [])

    def test_now_default_and_pattern(self):
        self.assertTrue(re.fullmatch(r"\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}",
                                     self.resolve("{{mock.now()}}", ctx())))
        self.assertTrue(re.fullmatch(r"\d{4}-\d{2}-\d{2}",
                                     self.resolve("""{{mock.now('yyyy-MM-dd')}}""", ctx())))

    def test_random_int_bounds_and_seed_replay(self):
        a = self.resolve("{{mock.randomInt(1,9)}}", ctx())
        self.assertTrue(1 <= int(a) <= 9)
        b = self.resolve("{{mock.randomInt(5,5,seed=3)}}", ctx())
        self.assertEqual("5", b)
        c1 = self.resolve("{{mock.randomInt(1,99,seed=7)}}", ctx())
        c2 = self.resolve("{{mock.randomInt(1,99,seed=7)}}", ctx())
        self.assertEqual(c1, c2, "same seed must replay the same value")

    def test_random_int_records_seed_events(self):
        events = []
        self.resolve("{{mock.randomInt(1,9,seed=4)}}", ctx(), events)
        self.assertEqual([{"fn": "randomInt", "seed": 4}], events)

    def test_random_int_bad_shape_rejected(self):
        with self.assertRaises(ValueError):
            self.resolve("{{mock.randomInt(9,1)}}", ctx())  # inverted bounds
        with self.assertRaises(ValueError):
            self.resolve("{{mock.randomInt(1)}}", ctx())    # missing bound

    def test_cycle_rotates_per_reference(self):
        got = self.resolve("{{mock.cycle('ids',1)}}-{{mock.cycle('ids',1)}}", ctx())
        self.assertEqual("a-b", got)
        # Java anchor cycle_rotatesOverQueryList: step starts from the first value
        stepped = self.resolve("{{mock.cycle('ids',2)}}|{{mock.cycle('ids',2)}}", ctx())
        self.assertEqual("a|c", stepped)

    def test_cycle_missing_key_fails_closed(self):
        with self.assertRaises(ValueError) as err:
            self.resolve("""{{mock.cycle('nope')}}""", ctx())
        self.assertIn("可用键", str(err.exception))


class TestMockTemplateSingleParser(unittest.TestCase):

    def test_gen_family_usable_inside_mock_template(self):
        from driver.env_resolver import resolve_mock
        events = []
        got = resolve_mock("h=${gen.sha1(text=abc)} u={{mock.pathVar.user}}",
                           ctx(), events)
        self.assertEqual("h=a9993e364706816aba3e25717850c26c9cd0d89d u=u-7", got)

    def test_no_placeholder_is_identity(self):
        from driver.env_resolver import resolve_mock
        self.assertEqual("plain text", resolve_mock("plain text", ctx(), []))

    def test_node_walk_applies_to_nested_strings(self):
        from driver.env_resolver import resolve_mock
        node = {"body": {"msg": "{{mock.query.page}}"}}
        got = resolve_mock(node, ctx(), [])
        self.assertEqual({"body": {"msg": "3"}}, got)

    def test_pattern_literal_matches_java_mirror(self):
        from driver.env_resolver import _MOCK_PATTERN, MOCK_FUNCTIONS
        self.assertEqual(r"\{\{mock\.([A-Za-z][A-Za-z0-9]*)([^{}]*)\}\}",
                         _MOCK_PATTERN.pattern)
        self.assertEqual(("pathVar", "query", "header", "body",
                          "now", "randomInt", "cycle", "session"), tuple(MOCK_FUNCTIONS))

    def test_session_reference_echoes_resolved_lane_and_fails_closed(self):
        from driver.env_resolver import resolve_mock, MockTemplateError
        got = resolve_mock({"msg": "journey={{mock.session}}"},
                           ctx(headers={"X-TP-Mock-Session": "lane-7"}), [])
        self.assertEqual({"msg": "journey=lane-7"}, got)
        with self.assertRaises(MockTemplateError):
            resolve_mock("{{mock.session}}", ctx(headers={}), [])


if __name__ == "__main__":
    unittest.main()
