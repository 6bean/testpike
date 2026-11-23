

import json
import os
import unittest

import psycopg2

from driver.db_dialect import (ConnectionPool, DbDialectRegistry, DbExecError,
                               validate_sql_step)

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
        "readonly": False, "maxPool": 2,
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
            cur.execute("DROP TABLE IF EXISTS tp_tmp_t191b")
            cur.execute("CREATE TABLE tp_tmp_t191b (order_no text, status text)")
            cur.execute("INSERT INTO tp_tmp_t191b VALUES "
                        "('A1','PENDING'),('A2','PENDING'),('A3','DONE')")
        conn.close()

    def tearDown(self):
        self.pool.close_all()
        conn = _raw_conn()
        conn.autocommit = True
        with conn.cursor() as cur:
            cur.execute("DROP TABLE IF EXISTS tp_tmp_t191b")
        conn.close()


class SqlExec词法Test(unittest.TestCase):
    pass

    def test_受控DML首词过无WHERE拒(self):
        validate_sql_step("sql.exec", "INSERT INTO t VALUES (1)")
        with self.assertRaises(DbExecError) as cm:
            validate_sql_step("sql.exec", "UPDATE t SET a = 1")
        self.assertIn("WHERE", str(cm.exception))
        with self.assertRaises(DbExecError):
            validate_sql_step("sql.exec", "TRUNCATE TABLE t")
        with self.assertRaises(DbExecError):
            validate_sql_step("sql.exec", "SELECT 1")


class SqlExec执行链路Test(_TmpTableCase):

    def test_insert_update_delete影响行数(self):
        d1 = self.pool.sql_exec(_pg_spec(), {
            "sql": "INSERT INTO tp_tmp_t191b VALUES (:no, 'NEW')",
            "params": {"no": "A9"}})
        self.assertIn("1", d1)
        self.assertIn("sql.exec", d1)
        d2 = self.pool.sql_exec(_pg_spec(), {
            "sql": "UPDATE tp_tmp_t191b SET status = 'SHIP'"
                   " WHERE status = 'PENDING'"})
        self.assertIn("2", d2)
        d3 = self.pool.sql_exec(_pg_spec(), {
            "sql": "DELETE FROM tp_tmp_t191b WHERE order_no = :no",
            "params": {"no": "A9"}})
        self.assertIn("1", d3)

    def test_readonly连接拒写人话(self):
        with self.assertRaises(DbExecError) as cm:
            self.pool.sql_exec(_pg_spec(readonly=True), {
                "sql": "INSERT INTO tp_tmp_t191b VALUES ('X','Y')"})
        msg = str(cm.exception)
        self.assertIn("只读", msg)
        self.assertIn("DB_SIT", msg)

    def test_执行链路词法闸独立拒无WHERE(self):
        with self.assertRaises(DbExecError):
            self.pool.sql_exec(_pg_spec(), {
                "sql": "UPDATE tp_tmp_t191b SET status = 'ZZZ'"})
        conn = _raw_conn()
        with conn.cursor() as cur:
            cur.execute("SELECT count(*) FROM tp_tmp_t191b WHERE status='ZZZ'")
            self.assertEqual(cur.fetchone()[0], 0)
        conn.close()

    def test_未绑定具名参数拒(self):
        with self.assertRaises(DbExecError) as cm:
            self.pool.sql_exec(_pg_spec(), {
                "sql": "DELETE FROM tp_tmp_t191b WHERE order_no = :no"})
        self.assertIn("no", str(cm.exception))


class SqlQuery取值链路Test(_TmpTableCase):

    def test_scalar捕获单值(self):
        detail, leaves = self.pool.sql_query(_pg_spec(), {
            "sql": "SELECT status FROM tp_tmp_t191b WHERE order_no = :oid",
            "params": {"oid": "A1"}, "saveAs": "row"})
        self.assertEqual(leaves, {"row": "PENDING"})
        self.assertIn("saveAs", detail)

    def test_单行多列点分展开(self):
        _, leaves = self.pool.sql_query(_pg_spec(), {
            "sql": "SELECT order_no, status FROM tp_tmp_t191b"
                   " WHERE order_no = :oid",
            "params": {"oid": "A3"}, "saveAs": "r"})
        self.assertEqual(leaves, {"r.order_no": "A3", "r.status": "DONE"})

    def test_多行saveAs人话拒(self):
        with self.assertRaises(DbExecError) as cm:
            self.pool.sql_query(_pg_spec(), {
                "sql": "SELECT order_no FROM tp_tmp_t191b", "saveAs": "row"})
        self.assertIn("row", str(cm.exception))

    def test_零行saveAs人话拒(self):
        with self.assertRaises(DbExecError) as cm:
            self.pool.sql_query(_pg_spec(), {
                "sql": "SELECT status FROM tp_tmp_t191b WHERE order_no = 'NOPE'",
                "saveAs": "row"})
        msg = str(cm.exception)
        self.assertIn("row", msg)
        self.assertIn("无行", msg)

    def test_max_rows截断标注(self):
        detail, leaves = self.pool.sql_query(_pg_spec(), {
            "sql": "SELECT order_no FROM tp_tmp_t191b ORDER BY order_no"},
            policy={"sqlMaxRows": 2})
        self.assertEqual(leaves, {})
        self.assertIn("截断", detail)
        self.assertIn("2", detail)
        detail2, _ = self.pool.sql_query(_pg_spec(), {
            "sql": "SELECT order_no FROM tp_tmp_t191b"}, policy={})
        self.assertNotIn("截断", detail2)

    def test_query链路首词闸独立拒(self):
        with self.assertRaises(DbExecError):
            self.pool.sql_query(_pg_spec(), {
                "sql": "DELETE FROM tp_tmp_t191b WHERE order_no = 'A1'"})


class DriverWiringT191bTest(_TmpTableCase):
    pass

    def test_capture到变量链到sql_exec到assert全链(self):
        from driver.webdriver import run_action_json
        doc = {"steps": [
            {"action": "goto",
             "url": "data:text/html,<h1 id='t'>A1</h1>"},
            {"action": "expect", "target": {"css": "#t"}, "capture": "cap"},
            {"action": "sql.exec", "conn": "DB_SIT",
             "sql": "UPDATE tp_tmp_t191b SET status = 'X'"
                    " WHERE order_no = :no",
             "params": {"no": "{{VAR:cap}}"}},
            {"action": "sql.query", "conn": "DB_SIT",
             "sql": "SELECT status FROM tp_tmp_t191b WHERE order_no = :no",
             "params": {"no": "{{VAR:cap}}"}, "saveAs": "st"},
            {"action": "sql.assert", "conn": "DB_SIT",
             "sql": "SELECT count(*) FROM tp_tmp_t191b WHERE status = :s",
             "params": {"s": "{{VAR:st}}"},
             "expect": {"type": "scalar", "matchType": "exact", "text": "1"}},
        ]}
        outcome = run_action_json(
            json.dumps(doc, ensure_ascii=False),
            env_base_url="file:///dev/null", connections=[_pg_spec()])
        self.assertEqual(outcome.result, "pass", outcome.error_detail)

    def test_capture值不进detail零实值(self):
        from driver.webdriver import run_action_json
        doc = {"steps": [
            {"action": "goto", "url": "data:text/html,<h1>s3cr3t-page-val</h1>"},
            {"action": "expect", "target": {"css": "h1"}, "capture": "c1"},
        ]}
        outcome = run_action_json(json.dumps(doc),
                                  env_base_url="file:///dev/null")
        self.assertEqual(outcome.result, "pass", outcome.error_detail)
        timeline = json.dumps(outcome.steps_timeline, ensure_ascii=False)
        self.assertNotIn("s3cr3t-page-val", timeline)

    def test_capture未定义消费failfast(self):
        from driver.webdriver import run_action_json
        doc = {"steps": [
            {"action": "sql.assert", "conn": "DB_SIT",
             "sql": "SELECT 1 FROM tp_tmp_t191b WHERE order_no = :no",
             "params": {"no": "{{VAR:nope9}}"}}]}
        outcome = run_action_json(json.dumps(doc),
                                  env_base_url="file:///dev/null",
                                  connections=[_pg_spec()])
        self.assertEqual(outcome.result, "fail")
        self.assertIn("nope9", outcome.error_detail)


if __name__ == "__main__":
    unittest.main()
