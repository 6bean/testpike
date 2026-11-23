
import hashlib
import json
import uuid

import httpx
import pytest
from conftest import (
    assert_no_errors,
    inject_token,
    sql_query,
)
from playwright.sync_api import expect

pytestmark = pytest.mark.timeout(600)

NS = "t278e2e."
CORE = "http://localhost:8083"


def _scalar(q: str) -> str:
    return sql_query(q).strip().splitlines()[0].strip()


def _cleanup() -> None:
    for q in (
        f"DELETE FROM tp_judge_review WHERE run_id IN (SELECT id FROM tp_judge_run WHERE target_ref LIKE '{NS}%')",
        f"DELETE FROM tp_judge_record WHERE run_id IN (SELECT id FROM tp_judge_run WHERE target_ref LIKE '{NS}%')",
        f"DELETE FROM tp_judge_run WHERE target_ref LIKE '{NS}%'",
        f"DELETE FROM tp_judge_sample WHERE set_name LIKE '{NS}%'",
        f"DELETE FROM tp_judge_rubric WHERE name LIKE '{NS}%'",
    ):
        sql_query(q)


def _canonical_row(inp, expected, refs_json, source, sensitive):
    def ap(s):
        v = s if s is not None else ""
        return f"{len(v)}:{v}|"
    return ap(inp) + ap(expected) + ap(refs_json) + ap(source) + ap(str(sensitive))


def _seed() -> dict:
    pass
    suffix = uuid.uuid4().hex[:8]
    set_name = f"{NS}集-{suffix}"
    rubric_name = f"{NS}标准-{suffix}"
    domain = f"case-gen-{suffix}"
    dims = [{"name": "合规性", "definition": "格式规范", "weight": 0.6, "anchorExamples": []},
            {"name": "完整性", "definition": "覆盖完整", "weight": 0.4, "anchorExamples": []}]
    dims_json = dims
    _cleanup()
    sql_query(
        "INSERT INTO tp_judge_rubric (name, domain, version, dimensions, rules, "
        "pass_score, w_rule, w_llm, status, created_by) VALUES ("
        f"'{rubric_name}', '{domain}', 1, '{json.dumps(dims_json, ensure_ascii=False)}'::jsonb, "
        "'[]'::jsonb, 60, 0.400, 0.600, 1, 1)")
    snap = json.dumps({"name": rubric_name, "domain": domain, "version": 1,
                       "dimensions": dims_json, "rules": [], "passScore": 60,
                       "wRule": 0.4, "wLlm": 0.6}, ensure_ascii=False)
    in1 = f"{NS} 会话续期用例：token 到期前自动刷新，断言无感续登"
    in2 = f"{NS} 订单导出用例：导出 90 天订单核对金额与状态字段"
    digest = hashlib.sha256("\n".join(sorted([
        _canonical_row(in1, in1, "[]", "manual", 0),
        _canonical_row(in2, in2, "[]", "manual", 0)])).encode()).hexdigest()
    s1 = _scalar("INSERT INTO tp_judge_sample (set_name, version, snapshot_digest, input, "
                 f"expected, reference_docs, source, sensitive, created_by) VALUES "
                 f"('{set_name}',1,'{digest}','{in1}','{in1}','[]'::jsonb,'manual',0,1) RETURNING id")
    s2 = _scalar("INSERT INTO tp_judge_sample (set_name, version, snapshot_digest, input, "
                 f"expected, reference_docs, source, sensitive, created_by) VALUES "
                 f"('{set_name}',1,'{digest}','{in2}','{in2}','[]'::jsonb,'manual',0,1) RETURNING id")

    def make_run(tag: str) -> str:
        return _scalar(
            "INSERT INTO tp_judge_run (rubric_name, rubric_domain, rubric_version, "
            "rubric_snapshot, evalset_name, evalset_version, evalset_digest, "
            "target_type, target_ref, llm_config_id, status, created_by) VALUES ("
            f"'{rubric_name}', '{domain}', 1, '{snap}'::jsonb, '{set_name}', 1, '{digest}', "
            f"'pipeline', '{NS}{suffix}.{tag}', NULL, 0, 1) RETURNING id")

    run_a, run_b = make_run("a"), make_run("b")

    def post_record(run_id, sample_id, rule, llm, tokens):
        body = {"runId": int(run_id), "sampleId": int(sample_id), "ruleScore": rule,
                "ruleHits": [], "llmScores": llm,
                "reasons": ({k: "口径示例" for k in llm} if llm else None),
                "latencyMs": 100, "tokens": tokens}
        r = httpx.post(f"{CORE}/internal/judge/records", json=body, timeout=15)
        assert r.json().get("code") == "0", r.text[:200]

    post_record(run_a, s1, 90, {"合规性": 82.0, "完整性": 70.0}, 60)
    post_record(run_a, s2, 55, {"完整性": 40.0}, 40)
    post_record(run_b, s1, 70, None, 30)
    post_record(run_b, s2, 90, None, 70)
    for rid in (run_a, run_b):
        sql_query(f"UPDATE tp_judge_run SET status = 2, finished_at = now() WHERE id = {rid}")
    return {"rubric": rubric_name, "domain": domain, "runA": run_a, "runB": run_b,
            "set": set_name}


def _card_scores(page):
    return [float((c.locator(".judge-card-score").inner_text() or "0").strip())
            for c in page.locator("[data-test='judge-card-list'] .judge-card").all()]


def test_judge_report_page(page, collectors):
    pass
    seed = _seed()
    try:
        inject_token(page)
        base = page.url.split("#")[0]

        page.goto(f"{base}#/judge/report?runId={seed['runA']}", wait_until="domcontentloaded")
        root = page.locator("[data-test='judge-report-page']")
        expect(root).to_be_visible()
        expect(page.locator("[data-test='judge-avg-score']")).not_to_have_text("—")
        meta = page.locator("[data-test='judge-summary-meta']")
        expect(meta).to_contain_text(seed["set"])
        expect(page.locator("[data-test='judge-radar']")).to_be_visible()

        page.locator("[data-test='judge-tabs'] .el-tabs__item:has-text(\"样本卡片流\")").first.click()
        cards = page.locator("[data-test='judge-card-list'] .judge-card")
        expect(cards).to_have_count(2)
        scores = _card_scores(page)
        assert scores[0] <= scores[1], f"最差置顶失效: {scores}"
        expect(page.locator("[data-test='judge-card-source']").first).to_be_visible()
        machine_line = cards.first.locator(".judge-card-line", has_text="机器")
        expect(machine_line.first).to_be_visible()

        page.locator("[data-test='judge-low-only']").click()
        expect(cards).to_have_count(1)
        assert scores[0] < 60
        page.locator("[data-test='judge-low-only']").click()

        page.goto(f"{base}#/judge/report?runId={seed['runA']}", wait_until="domcontentloaded")
        expect(root).to_be_visible()
        page.locator("[data-test='judge-tabs'] .el-tabs__item:has-text(\"样本卡片流\")").first.click()
        expect(cards).to_have_count(2)

        page.locator("[data-test='judge-tabs'] .el-tabs__item:has-text(\"两运行对比\")").first.click()
        page.locator("[data-test='judge-diff-a']").fill(seed["runA"])
        page.locator("[data-test='judge-diff-b']").fill(seed["runB"])
        page.locator("[data-test='judge-diff-btn']").click()
        expect(page.locator("[data-test='judge-diff-stats']")).to_be_visible()
        expect(page.locator("[data-test='judge-diff-note']")).not_to_have_text("")
        expect(page.locator("[data-test='judge-diff-table']")).to_be_visible()

        page.locator("[data-test='judge-tabs'] .el-tabs__item:has-text(\"成本卡\")").first.click()
        expect(page.locator("[data-test='judge-cost-desc']")).to_be_visible()
        price = _scalar("SELECT config_value FROM tp_sys_config "
                        "WHERE config_key='judge.cost.price-per-1k-tokens'")
        if not price or float(price) <= 0:
            est = page.locator("[data-test='judge-est-cost']").inner_text()
            assert est.strip() == "—", f"未设牌价却渲染金额: {est}"
            expect(page.locator("[data-test='judge-cost-note']")).to_contain_text("牌价")

        page.locator("[data-test='judge-tabs'] .el-tabs__item:has-text(\"趋势与战绩\")").first.click()
        page.locator("[data-test='judge-trend-name']").fill(seed["rubric"])
        page.locator("[data-test='judge-trend-domain']").fill(seed["domain"])
        page.locator("[data-test='judge-trend-btn']").click()
        table = page.locator("[data-test='judge-session-table']")
        expect(table).to_be_visible()
        expect(table.locator(".el-table__row")).to_have_count(2)

        assert_no_errors(collectors, "judge report page E2E")
    finally:
        _cleanup()
    assert _scalar(f"SELECT count(*) FROM tp_judge_run WHERE target_ref LIKE '{NS}%'") == "0"
