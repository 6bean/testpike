
from __future__ import annotations

import json
import sys

from config import ExecutorConfig
from driver.visual_diff import diff_ratio
from driver.webdriver import BrowserLaunchError, RunOutcome, WebDriverExecutor
from protocol import ExecutorProtocolClient


def build_visual_compare_port(executor: WebDriverExecutor, client):
    """Scale-B compare port for the equivalent re-anchor gate .

 Reads the evidence-plane baseline grant for one ledger element through
 the protocol channel, downloads the bytes via the driver's single
 download source (R40: no second presign/fetch implementation) and
 answers ``similarity = 1 - diff_ratio``. Every unprovable state —
 found=false, null storage url, download failure, pixel-dimension
 mismatch, protocol error — answers None, so the gate files a proposal
 instead of passing a heal silently (red line 1: no implicit pass).
 """

    def compare(element_id, screenshot_bytes):
        try:
            grant = client.get_reanchor_baseline(element_id)
            if not grant.get("found") or not grant.get("url"):
                return None
            baseline = executor._download_baseline(
                str(grant.get("url")), str(grant.get("screenshotKey") or ""))
            return 1.0 - diff_ratio(baseline, screenshot_bytes)
        except Exception:  # noqa: BLE001 — unprovable degrades to proposal
            return None

    return compare



def main(argv: list[str]) -> int:
    if len(argv) != 3:
        print("用法: task_worker.py <task.json> <outcome.json>", file=sys.stderr)
        return 2
    task_f, out_f = argv[1], argv[2]
    try:
        with open(task_f, "r", encoding="utf-8") as f:
            task = json.load(f)
    except Exception as e:  # noqa: BLE001
        print(f"任务文件读取失败: {e}", file=sys.stderr)
        return 2

    cfg = ExecutorConfig()
    outcome: RunOutcome
    executor: WebDriverExecutor | None = None
    try:
        executor = WebDriverExecutor(env_base_url=None, headless=True,
                                     screenshots_dir=cfg.screenshots_dir,
                                     browser=task.get("browser"),
                                     visual_baselines=task.get("visualBaselines"),
                                     tls_policy=task.get("tlsPolicy"),
                                     case_id=int(task.get("caseId") or 0),
 # (03 protocol 2.3 mock trailing key, U5 web route): pass the claim-side
 # {spaceKey,baseUrl,snapshotVersion} through — the key exists only for a
 # task_type=1 browser case bound to a PUBLISHED mock space. Old core /
 # unbound = None = zero mount, zero drift. A dirty shape is refused in
 # plain words at construction time (MockBindingError is a ValueError —
 # it lands in the fail/error_type=3 path below), never a silent fallback
 # to the real environment. All adjudication lives in the mock-server
 # kernel (single rule library, no second matcher).
                                     mock_binding=task.get("mock"),
 # (03 protocol 2.3 sessionState trailing key, face 3) core resolved the
 # login-state ledger row into a Playwright storage_state document; the driver
 # hands it to the single context build point. Absent key = None = legacy
 # context, zero drift. A half-built envelope raises in plain words at start()
 # and lands in the fail path below — never an anonymous run.
                                     session_state=task.get("sessionState"))
        # Re-anchor compare port (03 evidence-grant trailing channel,
        # piece 2b): injected post-construction because the port rides the
        # live protocol client, while driver baseline inputs stay
        # construction-time keys (pitfall: run() overrides reject them).
        executor._visual_compare = build_visual_compare_port(
            executor, ExecutorProtocolClient(cfg.api_base_url,
                                             cfg.request_timeout))
        executor.start()
        outcome = executor.run(
            task.get("actionJson", ""),
            case_title=str(task.get("caseId", "")),
            env_base_url=task.get("envBaseUrl") or None,
            env_vars=task.get("envVars") or None,
            script_limits=task.get("scriptLimits"),
            exec_policy=task.get("execPolicy"),
            connections=task.get("connections"),
            data_vars=task.get("dataVars") or None,
 # (03 protocol 2.3 elementAnchors trailing key, WE-S1): dispatch snapshot
 # binding for element-reference targets ({allowCandidate, elements});
 # absent key = None = reference steps refuse in plain words at the locator
 # (inline scripts zero drift — the normaliser never emits references for
 # them), never a silent fallback to inline guessing.
            element_anchors=task.get("elementAnchors"))
    except BrowserLaunchError as e:
        outcome = RunOutcome(result="fail", error_type=3, error_detail=str(e))
    except Exception as e:
        outcome = RunOutcome(result="fail", error_type=3, error_detail=f"执行异常: {e}")
    finally:
        if executor is not None:
            try:
                executor.stop()
            except Exception:
                pass

    try:
        with open(out_f, "w", encoding="utf-8") as f:
            json.dump(outcome.to_dict(), f, ensure_ascii=False)
    except Exception as e:  # noqa: BLE001
        print(f"结果文件写入失败: {e}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
