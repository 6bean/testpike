

import json
import logging
import os
import threading
import time
import unittest

import psycopg2

from driver.db_dialect import (ConnectionPool, DbDialectRegistry, DbExecError,
                               validate_sql_step)
from driver.env_resolver import VarStore
from driver.expect_match import ExpectMatchError

_ENV_PATH = os.path.join(os.path.dirname(__file__), "..", "..", ".env")


def _env_credentials():
    pass
    creds = {}
    with open(_ENV_PATH, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                k, _, v = line.partition("=")
                creds[k.strip()] = v.strip()
    return creds


CREDS = _env_credentials()


def _pg_spec(**over):
    spec = {
        "id": 1, "name": "DB_SIT", "dialect": "pg",
        "url": "jdbc:postgresql://127.0.0.1:15432/testpike",
        "user": CREDS.get("DB_USERNAME", "testpike"),
        "password": CREDS.get("DB_PASSWORD", ""),
        "readonly": True, "maxPool": 2,
    }
    spec.update(over)
    return spec


def _raw_conn():
    return psycopg2.connect(host="127.0.0.1", port=15432, dbname="testpike",
                            user=CREDS.get("DB_USERNAME", "testpike"),
                            password=CREDS.get("DB_PASSWORD", ""))


class _TmpTableCase(unittest.TestCase):
    pass

    def setUp(self):
        self.registry = DbDialectRegistry()
        self.pool = ConnectionPool(self.registry)
        conn = _raw_conn()
        conn.autocommit = True
        with conn.cursor() as cur:
            cur.execute("DROP TABLE IF EXISTS tp_tmp_t191")
            cur.execute("CREATE TABLE tp_tmp_t191 (order_no text, status text)")
            cur.execute("INSERT INTO tp_tmp_t191 VALUES "
                        "('A1','PENDING'),('A2','PENDING'),('A3','DONE')")
        conn.close()

    def tearDown(self):
        self.pool.close_all()
        conn = _raw_conn()
        conn.autocommit = True
        with conn.cursor() as cur:
            cur.execute("DROP TABLE IF EXISTS tp_tmp_t191")
        conn.close()

    def _mutate(self, sql, params=None):
        conn = _raw_conn()
        conn.autocommit = True
        with conn.cursor() as cur:
            cur.execute(sql, params or None)
        conn.close()


class DialectRegistryTest(unittest.TestCase):

    def test_未注册方言人话点名支持集(self):
        self.assertTrue(DbDialectRegistry().require("pg"))
        self.assertTrue(DbDialectRegistry().require("mysql"))
        self.assertTrue(DbDialectRegistry().require("  PG "))
        with self.assertRaises(DbExecError) as cm:
            DbDialectRegistry().require("oracle")
        msg = str(cm.exception)
        self.assertIn("oracle", msg)
        self.assertIn("PostgreSQL", msg)
        self.assertIn("MySQL", msg)

    def test_具名绑定重绑pyformat_字面量冒号不误绑(self):
        reg = DbDialectRegistry()
        entry = reg.require("pg")
        text, names = entry.to_driver_sql(
            "SELECT status FROM t WHERE order_no = :oid AND tag = 'a:b' AND s = :oid")
        self.assertEqual(names, ["oid"])
        self.assertNotIn(":oid", text)
        self.assertIn("a:b", text)
        self.assertIn("%(oid)s", text)


class SqlGate复检Test(unittest.TestCase):

    def test_sql_assert链路首词白名单执行前独立拒(self):
        with self.assertRaises(DbExecError) as cm:
            validate_sql_step("sql.assert", "TRUNCATE TABLE tp_tmp_t191")
        self.assertIn("TRUNCATE", str(cm.exception))
        with self.assertRaises(DbExecError):
            validate_sql_step("sql.assert", "DELETE FROM tp_tmp_t191 WHERE 1=1")
        validate_sql_step("sql.assert", "SELECT 1")

    def test_多语句分号拒(self):
        with self.assertRaises(DbExecError):
            validate_sql_step("sql.assert", "SELECT 1; DROP TABLE x")

    def test_未绑定占位点名(self):
        with self.assertRaises(DbExecError) as cm:
            validate_sql_step("sql.assert",
                              "SELECT * FROM t WHERE a = :oid", params={})
        self.assertIn("oid", str(cm.exception))


class SqlAssertExpectTest(_TmpTableCase):

    _BASE_SQL = ("SELECT status FROM tp_tmp_t191 WHERE order_no = :oid")

    def test_scalar命中过与不符归断言(self):
        detail = self.pool.sql_assert(_pg_spec(), {
            "action": "sql.assert", "conn": "DB_SIT", "sql": self._BASE_SQL,
            "params": {"oid": "A1"},
            "expect": {"type": "scalar", "matchType": "exact", "text": "PENDING"},
        }, policy={})
        self.assertIn("scalar", detail)
        with self.assertRaises(ExpectMatchError) as cm:
            self.pool.sql_assert(_pg_spec(), {
                "sql": self._BASE_SQL, "params": {"oid": "A1"},
                "expect": {"type": "scalar", "matchType": "exact",
                           "text": "SHIPPED"},
            }, policy={})
        self.assertIn("断言不满足", str(cm.exception))

    def test_scalar多行拒人话(self):
        with self.assertRaises(DbExecError) as cm:
            self.pool.sql_assert(_pg_spec(), {
                "sql": "SELECT status FROM tp_tmp_t191",
                "expect": {"type": "scalar", "matchType": "exact", "text": "PENDING"},
            }, policy={})
        self.assertIn("scalar", str(cm.exception))

    def test_row_count区间比对(self):
        self.pool.sql_assert(_pg_spec(), {
            "sql": "SELECT 1 FROM tp_tmp_t191",
            "expect": {"type": "row_count", "matchType": "range",
                       "min": 2, "max": 4},
        }, policy={})
        with self.assertRaises(ExpectMatchError):
            self.pool.sql_assert(_pg_spec(), {
                "sql": "SELECT 1 FROM tp_tmp_t191",
                "expect": {"type": "row_count", "matchType": "range",
                           "min": 10, "max": 12},
            }, policy={})

    def test_columns集合比对(self):
        self.pool.sql_assert(_pg_spec(), {
            "sql": "SELECT order_no, status FROM tp_tmp_t191 LIMIT 1",
            "expect": {"type": "columns",
                       "columns": ["order_no", "status"]},
        }, policy={})
        with self.assertRaises(ExpectMatchError):
            self.pool.sql_assert(_pg_spec(), {
                "sql": "SELECT order_no FROM tp_tmp_t191 LIMIT 1",
                "expect": {"type": "columns", "columns": ["order_no", "missing"]},
            }, policy={})

    def test_未支持expect型点名(self):
        with self.assertRaises(DbExecError) as cm:
            self.pool.sql_assert(_pg_spec(), {
                "sql": "SELECT 1", "expect": {"type": "json"},
            }, policy={})
        self.assertIn("json", str(cm.exception))


class SqlAssert轮询Test(_TmpTableCase):

    def test_轮询至转绿_interval钳1s加WARN(self):
        def _late_writer():
            time.sleep(0.2)
            self._mutate("INSERT INTO tp_tmp_t191 VALUES ('A4','DONE')")

        t = threading.Thread(target=_late_writer)
        t.start()
        try:
            with self.assertLogs("driver.db_dialect", level="WARNING") as logs:
                started = time.time()
                detail = self.pool.sql_assert(_pg_spec(), {
                    "sql": "SELECT 1 FROM tp_tmp_t191 WHERE order_no = 'A4'",
                    "expect": {"type": "row_count", "matchType": "range",
                               "min": 1, "max": 1},
                    "retry": {"intervalMs": 500},
                }, policy={}, deadline_s=10.0)
                elapsed = time.time() - started
            self.assertGreaterEqual(elapsed, 1.0)
            self.assertTrue(any("钳" in m or "1s" in m for m in logs.output))
            self.assertIn("轮询", detail)
        finally:
            t.join()

    def test_轮询超时归env类禁断言字样(self):
        started = time.time()
        with self.assertRaises(DbExecError) as cm:
            self.pool.sql_assert(_pg_spec(), {
                "sql": "SELECT 1 FROM tp_tmp_t191 WHERE order_no = 'NOPE'",
                "expect": {"type": "scalar", "matchType": "exact", "text": "X"},
                "retry": {"intervalMs": 1000},
            }, policy={}, deadline_s=1.5)
        self.assertGreaterEqual(time.time() - started, 1.0)
        msg = str(cm.exception)
        self.assertIn("轮询", msg)
        self.assertIn("末次实际", msg)
        self.assertNotIn("断言不满足", msg)
        for hint in ("Timeout", "定位", "locator", "等待", "get_by", "Locator"):
            self.assertNotIn(hint, msg)


class ConnectionPoolTest(_TmpTableCase):

    def test_同连接名复用不重建(self):
        sql = {"sql": "SELECT status FROM tp_tmp_t191 WHERE order_no = :oid",
               "params": {"oid": "A1"},
               "expect": {"type": "scalar", "matchType": "exact",
                          "text": "PENDING"}}
        self.pool.sql_assert(_pg_spec(), sql, policy={})
        first = self.pool.size()
        self.pool.sql_assert(_pg_spec(), sql, policy={})
        self.assertEqual(self.pool.size(), first)

    def test_maxpool超限置换旧连(self):
        spec = _pg_spec(maxPool=1)
        q = {"sql": "SELECT 1", "expect": {"type": "row_count",
                                           "matchType": "range", "min": 1, "max": 1}}
        self.pool.sql_assert(spec, q, policy={})
        spec2 = _pg_spec(id=2, name="DB_SIT2")
        self.pool.sql_assert(spec2, q, policy={})
        self.assertLessEqual(self.pool.size(), 1)

    def test_连接失败人话零明文密码(self):
        spec = _pg_spec(password="s3cr3t-密码值",
                        url="jdbc:postgresql://127.0.0.1:59999/nope")
        with self.assertRaises(DbExecError) as cm:
            self.pool.sql_assert(spec, {
                "sql": "SELECT 1",
                "expect": {"type": "row_count", "matchType": "range",
                           "min": 1, "max": 1},
            }, policy={}, deadline_s=1.0)
        msg = str(cm.exception)
        self.assertNotIn("s3cr3t", msg)
        self.assertIn("DB_SIT", msg)


class DriverWiringTest(_TmpTableCase):
    pass

    def test_sql_assert步端到端_ENV连接名解析映射(self):
        from driver.webdriver import run_action_json
        doc = {
            "steps": [{
                "action": "sql.assert", "conn": "{{ENV:DB_SIT}}",
                "sql": "SELECT status FROM tp_tmp_t191 WHERE order_no = :oid",
                "params": {"oid": "A1"},
                "expect": {"type": "scalar", "matchType": "exact",
                           "text": "PENDING"},
            }],
        }
        outcome = run_action_json(
            json.dumps(doc, ensure_ascii=False),
            env_base_url="file:///dev/null",
            env_vars={"DB_SIT": "DB_SIT"},
            connections=[_pg_spec()])
        self.assertEqual(outcome.result, "pass", outcome.error_detail)

    def test_未知连接名人话点名(self):
        from driver.webdriver import run_action_json
        doc = {"steps": [{
            "action": "sql.assert", "conn": "DB_MISSING",
            "sql": "SELECT 1", "expect": {"type": "row_count",
                                          "matchType": "range", "min": 1, "max": 1}}]}
        outcome = run_action_json(json.dumps(doc), env_base_url="file:///dev/null",
                                  connections=[_pg_spec()])
        self.assertEqual(outcome.result, "fail")
        self.assertIn("DB_MISSING", outcome.error_detail)
        self.assertEqual(outcome.error_type, 3)


if __name__ == "__main__":
    unittest.main()
