
from __future__ import annotations

import csv
import json
import math
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time
import zipfile
from datetime import datetime, timezone

from driver.webdriver import RunOutcome

ENVELOPE_KIND = "perf-run"
ENGINE_VALUES = ("K6", "GATLING", "JMETER")
K6_BINARY = "k6"
# Gatling engine jars root (process boundary, AGPL body never enters the repo:
# user-installed via installkit recipe, env-configurable GATLING_LIB; the launcher
# is the JDK java binary on PATH. Missing = human-worded fail, never silent k6 swap)
GATLING_LIB_ENV = "GATLING_LIB"
# JMeter binary name (process boundary, user-installed Apache-2.0 engine body never
# enters the repo; missing binary = human-worded fail, never a silent engine swap)
JMETER_BINARY = "jmeter"
MAX_EVENT_DETAIL_CHARS = 4000
DEFAULT_BUDGET_MS = 30 * 60 * 1000
BREAKER_POLL_SEC = 1.0
# Histogram bucket upper bounds in ms (ascending; the last bound is an open top) — the
# cross-language mirror of core PerfHistogram.BOUNDARIES ( stage-b: the collection
# side packs counts per bucket, core merges the arrays for full-run P50-P99 recomputation).
# Drift is pinned by core PerfHistogramMirrorConsistencyTest reading this file.
HIST_BOUNDARIES = (5.0, 10.0, 15.0, 20.0, 25.0, 30.0, 40.0, 50.0, 75.0, 100.0,
                   125.0, 150.0, 175.0, 200.0, 250.0, 300.0, 400.0, 500.0, 750.0,
                   1000.0, 1500.0, 2000.0, 3000.0, 5000.0, 10000.0, 30000.0, 60000.0)
# Fixed five error-class keys (03 section 2.4 errorClass contract; the TopN error drawer
# reads these words — one vocabulary, no per-page coinages).
ERROR_CLASS_KEYS = ("timeout", "refused", "http4xx", "http5xx", "biz")

# : ceiling of frames per shipped row, mirrored from the core single
# gate (PerfMetricServiceImpl.MAX_BATCH_FRAMES) which the dispatch context now
# projects as perf.maxShipFrames — the projected value always wins; this default
# only backstops legacy envelopes. A row over the gate is refused WHOLE by core,
# which used to drop entire flush windows once multi-step runs (1 aggregate +
# N per-step frames per second) crossed it at a raised flush cadence.
DEFAULT_MAX_SHIP_FRAMES = 7200


def select_new_frames(frames: list[dict], watermark: int) -> tuple[list[dict], int]:
    """Partial-frame incremental selection : only frames whose secondOffset
 is strictly above the shipped watermark go out this round; the returned watermark
 is the max offset ever shipped (never regressed — a late low-offset frame cannot
 drag the cursor back). Pure function: the flush window in _run_k6/_run_gatling
 calls it, tests pin the replay guard without spawning an engine.
 """
    fresh = [f for f in frames
             if isinstance(f, dict) and int(f.get("secondOffset", -1)) > watermark]
    new_water = max([int(f.get("secondOffset", 0)) for f in fresh], default=watermark)
    return fresh, new_water


def ship_partial_batch(sink: str | None, run_id: int, seq: int,
                       frames: list[dict],
                       host_stats: dict | None = None,
                       max_ship_frames: int = DEFAULT_MAX_SHIP_FRAMES) -> int:
    """Append {runId, seq, frames[, hostStats]} JSONL key rows to the
 partial-frame line file (03 §2.2 perfPartial; runner main process drains
 incrementally with heartbeats). Sink absent / empty batch = zero write
 (trailing-key zero drift: no key rides a heartbeat without content).
 chunking: the batch is split so every row stays within
 max_ship_frames (core refuses an over-gate batch whole) — one row below the
 ceiling, several rows above it, seq incrementing per chunk in frame order.
 The host sample rides only the first row of the batch (one procfs sample per
 due window, B-5 posture). Dirty/non-positive ceiling falls back to the
 shipped default. Returns the frame count shipped (0 = nothing written).
 """
    if not sink or not frames:
        return 0
    ceiling = max_ship_frames if isinstance(max_ship_frames, int) \
        and max_ship_frames > 0 else DEFAULT_MAX_SHIP_FRAMES
    chunks = [frames[offset:offset + ceiling]
              for offset in range(0, len(frames), ceiling)]
    shipped = 0
    try:
        with open(sink, "a", encoding="utf-8") as f:
            for index, chunk in enumerate(chunks):
                row: dict = {"runId": run_id, "seq": seq + index, "frames": chunk}
                if host_stats and index == 0:
                    row["hostStats"] = host_stats
                f.write(json.dumps(row, ensure_ascii=False) + "\n")
                shipped += len(chunk)
    except OSError:
        # line file gone mid-batch (task retired) = honest count of what landed,
        # engine keeps pressing
        return shipped
    return shipped


# B-5 : procfs root the host self-monitor reads (module attribute so
# tests can point it at a fixture tree; missing root = honest absence below).
HOST_PROC_ROOT = "/proc"


def collect_host_stats(proc_root: str | None = None) -> dict | None:
    """One load-engine host sample for the driver-bottleneck boundary (B-5).

    Reads the procfs trio — /proc/loadavg (1-minute load), /proc/meminfo
    (MemAvailable) and the visible core count — and normalizes them into
    {load1, cores, loadRatio, memAvailMb}. loadRatio = load1 / cores keeps
    the strain threshold machine-independent (core K8 key
    perf.self-monitor.load-ratio compares the same number on a 4-core and a
    64-core box). Honest absence everywhere: non-linux host, unreadable or
    dirty proc files, cores unavailable (ratio would be a lie) = None = the
    hostStats key simply does not ride the frame row — a fabricated sample
    is forbidden (it would silently poison the bottleneck attribution).
    """
    root = proc_root if proc_root is not None else HOST_PROC_ROOT
    try:
        with open(f"{root}/loadavg", encoding="utf-8") as f:
            load1 = float(f.read().split()[0])
        with open(f"{root}/meminfo", encoding="utf-8") as f:
            mem_avail_mb: float | None = None
            for line in f:
                if line.startswith("MemAvailable:"):
                    mem_avail_mb = float(line.split()[1]) / 1024.0
                    break
        if mem_avail_mb is None:
            return None
    except (OSError, ValueError, IndexError):
        return None
    cores = os.cpu_count()
    if not cores or cores <= 0:
        return None  # ratio unknowable = honest absence, never a divide-by-zero guess
    return {"load1": load1, "cores": int(cores),
            "loadRatio": load1 / float(cores), "memAvailMb": mem_avail_mb}


def make_partial_shipper(partial: dict | None):
    """Build the in-press partial-frame shipper or None (channel absent:
 sink not injected = old core / legacy run shape — zero drift, engines press on
 without shipping). Flush cadence = the claim perf context's framesFlushSeconds
 (core K8 key perf.frames-flush-interval-seconds projected at dispatch, clamped
 there; missing/dirty falls back to 10s here — never 0 = heartbeat flooding).
 The returned callable takes the latest frame list and appends one incremental
 key row per due window (watermark guard inside select_new_frames = no replay).
 """
    if not isinstance(partial, dict):
        return None
    sink = partial.get("sink")
    try:
        run_id = int(partial.get("runId"))
    except (TypeError, ValueError):
        return None
    if not sink or run_id <= 0:
        return None
    try:
        flush = float(partial.get("flushSeconds") or 0)
    except (TypeError, ValueError):
        flush = 0.0
    if flush < 1:
        flush = 10.0
    try:
        max_ship = int(partial.get("maxShipFrames") or 0)
    except (TypeError, ValueError):
        max_ship = 0
    if max_ship < 1:
        max_ship = DEFAULT_MAX_SHIP_FRAMES
    state = {"watermark": -1, "seq": 0, "last": 0.0}

    def ship(frames: list[dict]) -> None:
        now = time.monotonic()
        if now - state["last"] < flush:
            return
        state["last"] = now
        fresh, state["watermark"] = select_new_frames(frames, state["watermark"])
        if fresh:
            # One row per gate-sized chunk ; seq advances by the
            # chunk count so the next window never reuses a shipped seq.
            chunk_count = (len(fresh) + max_ship - 1) // max_ship
            state["seq"] += chunk_count
            # B-5: one host sample per due window (the flush cadence already
            # IS the K8-keyed heartbeat budget — sampling per frame would
            # multiply procfs reads for zero attribution value).
            ship_partial_batch(sink, run_id, state["seq"] - chunk_count + 1, fresh,
                               host_stats=collect_host_stats(),
                               max_ship_frames=max_ship)

    return ship


def _breaker_rate_from_snapshot(snapshot) -> float | None:
    pass
    data = snapshot
    if isinstance(data, str):
        try:
            data = json.loads(data)
        except Exception:
            return None
    if not isinstance(data, dict):
        return None
    sla = data.get("sla")
    if isinstance(sla, str):
        try:
            sla = json.loads(sla)
        except Exception:  # noqa: BLE001
            return None
    if not isinstance(sla, dict):
        return None
    try:
        rate = float(sla.get("breakerErrorRate"))
    except (TypeError, ValueError):
        return None
    if not math.isfinite(rate) or rate <= 0:
        return None
    return rate


def _now_iso() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def _clip_detail(text: str) -> str:
    pass
    if len(text) <= MAX_EVENT_DETAIL_CHARS:
        return text
    return "…(前文摘要截断)…\n" + text[-(MAX_EVENT_DETAIL_CHARS - 24):]


def _perf_context(task: dict) -> dict | None:
    pass
    perf = task.get("perf")
    return perf if isinstance(perf, dict) else None


def materialize_artifact(handle: str, api_base: str | None,
                         extract_dir: str) -> tuple[str | None, str | None]:
    """Turn the dispatch artifact handle into an engine-readable path .

 Since the core adapters hand out an object-storage key of a zip
 bundle instead of a local path. This function materializes that bundle on
 the execution host: ask the api /internal channel for a presigned GET
 (authorization lives in core — this side judges nothing), download, extract
 into extract_dir, and return the entry the engine consumes:

 - k6 bundle (script + ds_*.json sidecar at zip root): the extracted .js
 path, sidecar beside it (the template opens dataset files relative to
 the script directory);
 - Gatling bundle (manifest.json + source + classes/**): manifest.json with
 its zip-relative pointers (classesDir/sourceFile) rewritten to absolute
 extracted paths;
 - JMETER bundle (plan.jmx at zip root): the extracted .jmx plan path;
 - compatibility read: an empty handle or an existing local path passes
 straight through with zero network calls (in-flight runs dispatched
 before , and local dev, stay untouched);
 - every failure mode is fail-closed with a human-worded reason naming the
 object-storage stage — never a silent fallback to a stale local tree.

 Returns (path, None) on success, (None, error_detail) on failure.
 """
    if not handle or os.path.isfile(handle):
        return handle, None
    if not api_base:
        return None, (f"压测无法启动：编译产物位于对象存储「{handle}」，但下载授权通道未接线"
                      "（执行器缺 api 地址注入，请检查 runner 启动配置后重跑）")
    from protocol import ExecutorProtocolClient

    try:
        grant = ExecutorProtocolClient(str(api_base)).grant_perf_artifact_download(handle)
    except Exception as e:  # noqa: BLE001 —— grant chain failure = task-level human-worded fail, never a raw throw
        return None, (f"压测无法启动：编译产物下载授权失败「{handle}」: {e}")
    url = grant.get("downloadUrl") if isinstance(grant, dict) else None
    if not url:
        return None, (f"压测无法启动：编译产物下载授权应答缺 downloadUrl「{handle}」"
                      "（服务端应答漂移，禁猜产物源）")
    try:
        import httpx

        bundle_path = os.path.join(extract_dir, "artifact-bundle.zip")
        with open(bundle_path, "wb") as f:
            f.write(httpx.get(str(url), timeout=300.0).content)
        with zipfile.ZipFile(bundle_path) as zf:
            zf.extractall(extract_dir)
    except Exception as e:  # noqa: BLE001 —— download/extract failure = half-bundle abandoned, task-level human-worded fail
        return None, (f"压测无法启动：编译产物从对象存储下载或解包失败「{handle}」: {e}")
    manifest_path = os.path.join(extract_dir, "manifest.json")
    if os.path.isfile(manifest_path):
        # Gatling form: rebase zip-relative pointers so the launcher consumes
        # absolute paths exactly like the pre-A-10 on-disk layout.
        try:
            with open(manifest_path, "r", encoding="utf-8") as f:
                manifest = json.load(f)
            for key in ("classesDir", "sourceFile"):
                value = str(manifest.get(key) or "")
                if value and not os.path.isabs(value):
                    manifest[key] = os.path.join(extract_dir, value)
            with open(manifest_path, "w", encoding="utf-8") as f:
                json.dump(manifest, f, ensure_ascii=False)
        except (ValueError, OSError) as corrupt:
            return None, (f"压测无法启动：编译产物 manifest 物化后不可读「{handle}」: {corrupt}")
        return manifest_path, None
    scripts = [os.path.join(extract_dir, name)
               for name in sorted(os.listdir(extract_dir))
               if name.endswith(".js") and os.path.isfile(os.path.join(extract_dir, name))]
    if scripts:
        return scripts[0], None
    plans = [os.path.join(extract_dir, name)
             for name in sorted(os.listdir(extract_dir))
             if name.endswith(".jmx") and os.path.isfile(os.path.join(extract_dir, name))]
    if plans:
        return plans[0], None
    return None, (f"压测无法启动：编译产物包内结构非法「{handle}」"
                  "（既无 manifest.json、.js 脚本也无 .jmx 计划），请重新编译场景后再发起运行")


def _parse_timestamp(raw: str) -> float | None:
    pass
    if not raw:
        return None
    try:
        text = str(raw).strip().replace("Z", "+00:00")
        return datetime.fromisoformat(text).timestamp()
    except Exception:
        return None


def _hist_bucket_of(ms: float) -> int:
    """Latency value -> histogram bucket index (mirror of core PerfHistogram.bucketOf:
    first boundary >= value wins, over-cap values land in the last bucket)."""
    for i, bound in enumerate(HIST_BOUNDARIES):
        if ms <= bound:
            return i
    return len(HIST_BOUNDARIES) - 1


def _hist_counts(durations: list[float]) -> list[int]:
    """Pack one bucket's durations into the 27-slot count array (raw material the core
    merges for full-run P50-P99 recomputation — per-second p95-max is not mergeable)."""
    counts = [0] * len(HIST_BOUNDARIES)
    for ms in durations:
        counts[_hist_bucket_of(float(ms))] += 1
    return counts


def _classify_error(status, error_text: str) -> str:
    """One failed sample -> error class key (five-key contract, single source shared by
    the k6 and Gatling paths; the TopN error drawer and the replay gate consume the same
    words). HTTP status codes win over free text; a Gatling message carrying
    "status is 503" is read back as that code; text-only failures land on
    timeout/refused, everything else is a business failure."""
    code = None
    if status is not None:
        try:
            code = int(str(status).strip())
        except (TypeError, ValueError):
            code = None
    text = (error_text or "").lower()
    if code is None:
        m = re.search(r"status\s+is\s+(\d{3})", text)
        if m:
            code = int(m.group(1))
    if code is not None:
        if 400 <= code < 500:
            return "http4xx"
        if 500 <= code < 600:
            return "http5xx"
        return "biz"
    if "timeout" in text or "deadline" in text or "took too long" in text:
        return "timeout"
    if "refused" in text or "connection reset" in text or "no such host" in text:
        return "refused"
    return "biz"


def _error_class_of(samples: list[tuple], ) -> dict | None:
    """Failed samples (status, error text, weight) -> five-key count object; None when the
    bucket carries no failure (uncollected = key absent, never zero-filled)."""
    if not samples:
        return None
    counts = {key: 0 for key in ERROR_CLASS_KEYS}
    hit = False
    for status, text, weight in samples:
        counts[_classify_error(status, text)] += max(1, int(round(float(weight))))
        hit = True
    return counts if hit else None


def normalize_frames(export_lines: list[str]) -> list[dict]:
    pass
    AUTH_TAG = "auth"
    reqs: dict[int, float] = {}
    durations: dict[int, list[float]] = {}
    failed: dict[int, float] = {}
    auth_reqs: dict[int, float] = {}
    auth_durs: dict[int, list[float]] = {}
    # stage-b raw material: per-(second,step) request counts and durations feed the
    # step-split frames (B-4); failure samples keep tags.status/tags.error so the five-key
    # error classification (B-6) buckets them; aggregate math stays the existing one source.
    step_reqs: dict[tuple[int, str], float] = {}
    step_durs: dict[tuple[int, str], list[float]] = {}
    step_fails: dict[tuple[int, str], float] = {}
    fail_samples: dict[tuple[int, str], list[tuple]] = {}
    earliest: float | None = None
    for line in export_lines:
        line = line.strip()
        if not line:
            continue
        try:
            point = json.loads(line)
        except Exception:
            continue
        metric = point.get("metric")
        if metric not in ("http_reqs", "http_req_duration", "http_req_failed"):
            continue
        body = point.get("data") if isinstance(point.get("data"), dict) else point
        epoch = _parse_timestamp(body.get("time") or body.get("timestamp") or "")
        if epoch is None:
            continue
        if earliest is None or epoch < earliest:
            earliest = epoch
        try:
            value = float(body.get("value") or 0.0)
        except (TypeError, ValueError):
            continue
        offset = int(epoch - earliest)
        tags = body.get("tags") if isinstance(body.get("tags"), dict) else {}
        is_auth = tags.get("name") == AUTH_TAG
        if is_auth:
            if metric == "http_reqs":
                auth_reqs[offset] = auth_reqs.get(offset, 0.0) + value
            elif metric == "http_req_duration":
                auth_durs.setdefault(offset, []).append(value)
            continue
        name = str(tags.get("name") or "")
        if metric == "http_reqs":
            reqs[offset] = reqs.get(offset, 0.0) + value
            step_reqs[(offset, name)] = step_reqs.get((offset, name), 0.0) + value
        elif metric == "http_req_failed":
            failed[offset] = failed.get(offset, 0.0) + value
            step_fails[(offset, name)] = step_fails.get((offset, name), 0.0) + value
            fail_samples.setdefault((offset, name), []).append(
                (tags.get("status"), tags.get("error"), value))
        else:
            durations.setdefault(offset, []).append(value)
            step_durs.setdefault((offset, name), []).append(value)
    seconds = sorted(set(reqs) | set(durations) | set(failed)
                     | set(auth_reqs) | set(auth_durs))
    step_names = sorted({name for (_sec, name) in step_reqs}
                        | {name for (_sec, name) in step_durs})
    return _frames_from_buckets(reqs, durations, failed, auth_reqs, auth_durs, seconds,
                                step_names=step_names, step_reqs=step_reqs,
                                step_durs=step_durs, step_fails=step_fails,
                                fail_samples=fail_samples)


def _frames_from_buckets(reqs: dict[int, float], durations: dict[int, list[float]],
                         failed: dict[int, float], auth_reqs: dict[int, float],
                         auth_durs: dict[int, list[float]],
                         seconds: list[int],
                         step_names: list[str] | None = None,
                         step_reqs: dict[tuple[int, str], float] | None = None,
                         step_durs: dict[tuple[int, str], list[float]] | None = None,
                         step_fails: dict[tuple[int, str], float] | None = None,
                         fail_samples: dict[tuple[int, str], list[tuple]] | None = None,
                         ) -> list[dict]:
    """Second-bucket -> frame assembly, single source shared by the k6 JSON and
 the Gatling binary collection paths (red line 6: no duplicated three-line
 math). rps = bucket count, p95Ms = nearest-rank ceil(0.95n), errorRate =
 failed/rps clamped to 1.0; auth keys appear only for seconds that actually
 carry auth points (never zero-filled — D5 discipline).

 stage-b widening: every frame additionally carries the bucket columns
 (reqCount/failCount/durationSumMs/hist/errorClass/stepKey='') that feed the
 core full-run recomputation (A-7) and the TopN error drawer (B-6). When the
 run collected more than one business step name (B-4 per-step lines), one
 aggregated frame plus one frame per step are emitted for the shared second —
 single-step runs keep the old one-frame-per-second shape (no silent doubling:
 the 7200-frame batch cap and the weighted-error merge both read aggregate
 frames, step frames ride stepKey so the ingestion anchor stays distinct)."""
    frames: list[dict] = []
    split = bool(step_names) and len(step_names) > 1
    for sec in seconds:
        n = int(round(reqs.get(sec, 0.0)))
        durs = sorted(durations.get(sec, []))
        if durs:
            rank = max(1, math.ceil(0.95 * len(durs)))
            p95 = durs[rank - 1]
        else:
            p95 = 0.0
        fail_n = failed.get(sec, 0.0)
        rate = (fail_n / n) if n > 0 else 0.0
        frame = {"secondOffset": sec, "rps": float(n),
                 "p95Ms": float(p95), "errorRate": float(min(rate, 1.0))}
        # Bucket columns: aggregate frames always carry stepKey=''; when the collection
        # has no raw material the keys stay absent (legacy executor shape, the core
        # judge then degrades to the per-second caliber).
        if durs or n > 0:
            frame["reqCount"] = float(n)
            frame["failCount"] = float(int(round(fail_n)))
            frame["durationSumMs"] = float(sum(durs))
            frame["hist"] = _hist_counts(durs)
            frame["stepKey"] = ""
        if fail_n > 0:
            ec = _error_class_of(_samples_at(fail_samples, sec, None))
            if ec is not None:
                frame["errorClass"] = ec
        if sec in auth_reqs or sec in auth_durs:
            ad = auth_durs.get(sec, [])
            frame["authCount"] = float(auth_reqs.get(sec, 0.0))
            frame["authMs"] = (sum(ad) / len(ad)) if ad else 0.0
        frames.append(frame)
        if split:
            for name in step_names:
                sn = int(round((step_reqs or {}).get((sec, name), 0.0)))
                sdurs = sorted((step_durs or {}).get((sec, name), []))
                if sn <= 0 and not sdurs:
                    continue
                sfail = (step_fails or {}).get((sec, name), 0.0)
                srate = (sfail / sn) if sn > 0 else 0.0
                sp95 = 0.0
                if sdurs:
                    srank = max(1, math.ceil(0.95 * len(sdurs)))
                    sp95 = sdurs[srank - 1]
                step_frame = {"secondOffset": sec, "rps": float(sn),
                              "p95Ms": float(sp95),
                              "errorRate": float(min(srate, 1.0)),
                              "reqCount": float(sn),
                              "failCount": float(int(round(sfail))),
                              "durationSumMs": float(sum(sdurs)),
                              "hist": _hist_counts(sdurs),
                              "stepKey": name}
                sec_samples = _samples_at(fail_samples, sec, name)
                ec = _error_class_of(sec_samples)
                if ec is not None:
                    step_frame["errorClass"] = ec
                frames.append(step_frame)
    return frames


def _samples_at(fail_samples: dict | None, sec: int,
                name: str | None) -> list[tuple]:
    """Failed-sample rows (status, error text, weight) for one second. name=None means
    the aggregate frame (every step of that second); a step frame filters by its own
    step key so the TopN drawer attributes errors per step without double counting."""
    if not fail_samples:
        return []
    rows: list[tuple] = []
    for (s, n), samples in fail_samples.items():
        if s != sec:
            continue
        if name is not None and n != name:
            continue
        rows.extend(samples)
    return rows


# ---------------------------------------------------------------- gatling binary log
#
# Engine result file layout, proven by javap against gatling-core 3.13.5
# (io.gatling.core.stats.writer.*):
#   record = 1 header byte + payload; Run=0 Request=1 User=2 Group=3 Error=4
#   writeInt/writeLong = big-endian; writeBoolean = 1 byte
#   writeString  = int byte-length + raw bytes + 1 coder byte (empty = length 0
#                  alone — JDK17 String.value/coder internals)
#   writeCachedString = int: >=0 (cache index starts at 1) followed by the raw
#                  string; negative = back-reference to slot -index
#   writeByteBuffer = int blob length + raw bytes (length-prefixed, skippable)
#   Request payload = hierarchy (int count + cached names) + cached name +
#                  start (int ms rel. run) + end (int) + OK flag + cached message
#   Run payload     = version string + simulation class string + absolute start
#                  (long) + description string + config map (size + that many
#                  writeStrings) + assertion count + that many length-prefixed
#                  boopickle blobs (opaque, skipped by length — every field is
#                  self-delimiting so the record ends exactly at its boundary,
#                  no resync heuristic needed)
#   User payload    = scenario id (int) + start flag (bool) + timestamp (int)
#   Group payload   = hierarchy (count + cached) + start/end/cumulated ints + OK
#   Error payload   = cached message + timestamp (int)

_GATLING_HEADER_RUN = 0
_GATLING_HEADER_REQUEST = 1
_GATLING_HEADER_USER = 2
_GATLING_HEADER_GROUP = 3
_GATLING_HEADER_ERROR = 4


class _GatlingCursor:
    """Cursor over the binary log implementing the writer's primitives
    (reverse of BufferedFileChannelWriter). Raises _Truncated at the tail of a
    live file so the caller keeps complete records and flags the cut."""

    def __init__(self, data: bytes):
        self.data = data
        self.pos = 0
        self.cache: dict[int, str] = {}
        self.meta: dict = {"gatlingVersion": None, "simulationClassName": None,
                           "runStartMillis": None}

    def _need(self, n: int):
        if self.pos + n > len(self.data):
            raise _Truncated()

    def byte(self) -> int:
        self._need(1)
        v = self.data[self.pos]
        self.pos += 1
        return v

    def int32(self) -> int:
        self._need(4)
        v = int.from_bytes(self.data[self.pos:self.pos + 4], "big", signed=True)
        self.pos += 4
        return v

    def int64(self) -> int:
        self._need(8)
        v = int.from_bytes(self.data[self.pos:self.pos + 8], "big", signed=True)
        self.pos += 8
        return v

    def boolean(self) -> bool:
        return self.byte() != 0

    def string(self) -> str:
        n = self.int32()
        if n == 0:
            return ""
        self._need(n + 1)  # bytes + trailing coder byte
        s = self.data[self.pos:self.pos + n].decode("utf-8", errors="replace")
        self.pos += n + 1
        return s

    def blob(self) -> bytes:
        n = self.int32()
        if n == 0:
            return b""
        self._need(n)
        v = self.data[self.pos:self.pos + n]
        self.pos += n
        return v

    def cached_string(self) -> str:
        idx = self.int32()
        if idx >= 0:
            s = self.string()
            self.cache[idx] = s
            return s
        return self.cache.get(-idx, "")


class _Truncated(Exception):
    """Read past the end of a still-growing file: keep what was parsed."""


def parse_gatling_log(path: str) -> dict:
    """Parse the engine binary simulation.log into {meta, points, truncated}.

    points = request records only ({name,startMs,endMs,ok,message,groups});
    Run/User/Group/Error records are consumed for stream alignment but never
    surface as points (frames derive from request timings). A torn tail (live
    run) returns complete points so far with truncated=True — half records are
    dropped, never invented. Unknown header byte = corrupt stream: parse stops,
    truncated=True (fail-visible over fail-guessed).
    """
    with open(path, "rb") as f:
        data = f.read()
    cur = _GatlingCursor(data)
    out = _parse_gatling_stream(cur, data)
    return {"points": out[0], "truncated": out[1], **out[2]}


def _parse_gatling_stream(cur: _GatlingCursor, data: bytes) -> tuple[list, bool, dict]:
    """Scan records from the cursor position to EOF: (points, truncated, meta).

    points = request records only ({name,startMs,endMs,ok,message,groups});
    Run/User/Group/Error records are consumed for stream alignment but never
    surface as points (frames derive from request timings). A torn tail (live
    run) returns complete points so far with truncated=True — half records are
    dropped, never invented. Unknown header byte = corrupt stream: parse stops,
    truncated=True (fail-visible over fail-guessed). The cursor (position +
    string cache) persists across calls so a growing file parses incrementally;
    re-reads of a rolled-back record re-insert cache entries idempotently."""
    meta = {"gatlingVersion": cur.meta.get("gatlingVersion"),
            "simulationClassName": cur.meta.get("simulationClassName"),
            "runStartMillis": cur.meta.get("runStartMillis")}
    points: list[dict] = []
    truncated = False
    record_start = cur.pos
    while cur.pos < len(data):
        record_start = cur.pos
        try:
            header = cur.byte()
            if header == _GATLING_HEADER_REQUEST:
                count = cur.int32()
                groups = [cur.cached_string() for _ in range(count)]
                name = cur.cached_string()
                start = cur.int32()
                end = cur.int32()
                ok = cur.boolean()
                message = cur.cached_string()
                points.append({"name": name, "startMs": start, "endMs": end,
                               "ok": ok, "message": message, "groups": groups})
            elif header == _GATLING_HEADER_RUN:
                cur.meta["gatlingVersion"] = cur.string()
                cur.meta["simulationClassName"] = cur.string()
                cur.meta["runStartMillis"] = cur.int64()
                meta["gatlingVersion"] = cur.meta["gatlingVersion"]
                meta["simulationClassName"] = cur.meta["simulationClassName"]
                meta["runStartMillis"] = cur.meta["runStartMillis"]
                cur.string()  # run description (unused for attribution)
                config_size = cur.int32()
                for _ in range(config_size):
                    # sorted value rendering per config key — one writeString
                    cur.string()
                assertion_count = cur.int32()
                for _ in range(max(0, assertion_count)):
                    cur.blob()  # length-prefixed boopickle assertion record
            elif header == _GATLING_HEADER_USER:
                cur.int32()
                cur.boolean()
                cur.int32()
            elif header == _GATLING_HEADER_GROUP:
                count = cur.int32()
                for _ in range(count):
                    cur.cached_string()
                cur.int32()
                cur.int32()
                cur.int32()
                cur.boolean()
            elif header == _GATLING_HEADER_ERROR:
                cur.cached_string()
                cur.int32()
            else:
                truncated = True  # unknown header = corrupt stream, stop visible
                break
        except _Truncated:
            cur.pos = record_start  # roll back the partial record
            truncated = True
            break
    return points, truncated, meta


def normalize_gatling_frames(points: list[dict]) -> list[dict]:
    """Gatling request points -> second-level frames (three-line semantics,
    same bucket math as k6 via _frames_from_buckets — single source).

    duration = endMs - startMs; failed = ok flag false. Offsets are whole
    seconds of the run-relative clock (negative clamps to 0). No auth bucket on
    this path: the tag scheme is k6-only, so authCount/authMs keys never
    appear (uncollected = key absent). Error classification rides the failure
    message ("status is 503" reads back as http5xx) since the binary log has
    no status tag."""
    reqs: dict[int, float] = {}
    durations: dict[int, list[float]] = {}
    failed: dict[int, float] = {}
    step_reqs: dict[tuple[int, str], float] = {}
    step_durs: dict[tuple[int, str], list[float]] = {}
    step_fails: dict[tuple[int, str], float] = {}
    fail_samples: dict[tuple[int, str], list[tuple]] = {}
    for p in points:
        try:
            start = int(p["startMs"])
            end = int(p["endMs"])
        except (KeyError, TypeError, ValueError):
            continue
        offset = max(0, start // 1000)
        name = str(p.get("name") or "")
        reqs[offset] = reqs.get(offset, 0.0) + 1.0
        step_reqs[(offset, name)] = step_reqs.get((offset, name), 0.0) + 1.0
        durations.setdefault(offset, []).append(float(max(0, end - start)))
        step_durs.setdefault((offset, name), []).append(float(max(0, end - start)))
        if not p.get("ok"):
            failed[offset] = failed.get(offset, 0.0) + 1.0
            step_fails[(offset, name)] = step_fails.get((offset, name), 0.0) + 1.0
            fail_samples.setdefault((offset, name), []).append(
                (None, str(p.get("message") or ""), 1.0))
    seconds = sorted(set(reqs) | set(durations) | set(failed))
    step_names = sorted({name for (_sec, name) in step_reqs})
    return _frames_from_buckets(reqs, durations, failed, {}, {}, seconds,
                                step_names=step_names, step_reqs=step_reqs,
                                step_durs=step_durs, step_fails=step_fails,
                                fail_samples=fail_samples)


# ---------------------------------------------------------------- jmeter JTL csv
#
# Ground truth probed against the real Apache JMeter 5.6.3 binary (CSV SaveService
# default layout, header-driven — never positional):
#   timeStamp,elapsed,label,responseCode,responseMessage,threadName,dataType,
#   success,failureMessage,bytes,sentBytes,grpThreads,allThreads,URL,Latency,
#   IdleTime,Connect
# timeStamp = absolute epoch millis, elapsed = sample duration in millis,
# label = sampler name (the business step key, B-4 per-step shape), and
# success = "true"/"false" is the authoritative failure flag — responseCode
# alone lies because non-HTTP samplers record 200 even on failed runs.
# Engine-side totals (stdout summary, report dashboard) are never parsed: every
# metric is recomputed from raw rows via the shared _frames_from_buckets source.


def normalize_jmeter_frames(lines: list[str]) -> list[dict]:
    """JTL CSV rows -> second-level frames (three-line semantics, same bucket
    math as the k6 JSON and Gatling binary paths via _frames_from_buckets —
    single source). Offsets are whole seconds relative to the first sampled
    row (negative clamps to 0). Torn/noise rows (unparsable epoch, required
    columns not flushed) are skipped exactly like non-JSON lines on the k6
    path; a half-written header simply yields zero frames until the file
    completes. No auth bucket on this engine path (D5 keys never appear —
    the tag scheme is k6-only)."""
    reqs: dict[int, float] = {}
    durations: dict[int, list[float]] = {}
    failed: dict[int, float] = {}
    step_reqs: dict[tuple[int, str], float] = {}
    step_durs: dict[tuple[int, str], list[float]] = {}
    step_fails: dict[tuple[int, str], float] = {}
    fail_samples: dict[tuple[int, str], list[tuple]] = {}
    base_ts: int | None = None
    for row in csv.DictReader(lines):
        try:
            ts = int(str(row.get("timeStamp") or "").strip())
            dur = int(str(row.get("elapsed") or "").strip())
        except ValueError:
            continue
        if row.get("success") is None:
            continue  # torn tail: the success column has not landed yet
        label = str(row.get("label") or "")
        if base_ts is None:
            base_ts = ts
        offset = max(0, (ts - base_ts) // 1000)
        ok = str(row["success"]).strip().lower() == "true"
        reqs[offset] = reqs.get(offset, 0.0) + 1.0
        step_reqs[(offset, label)] = step_reqs.get((offset, label), 0.0) + 1.0
        durations.setdefault(offset, []).append(float(max(0, dur)))
        step_durs.setdefault((offset, label), []).append(float(max(0, dur)))
        if not ok:
            failed[offset] = failed.get(offset, 0.0) + 1.0
            step_fails[(offset, label)] = step_fails.get((offset, label), 0.0) + 1.0
            code_raw = str(row.get("responseCode") or "").strip()
            try:
                code = int(code_raw)
            except ValueError:
                code = None
            # A 2xx/3xx code on a failed sample is the non-HTTP sampler default
            # (Test Action records 200 even when it failed): it carries no error
            # signal, so only the failure text rides the shared five-key
            # classifier (_classify_error via _error_class_of — single source).
            signal = code_raw if code is not None and code >= 400 else None
            fail_samples.setdefault((offset, label), []).append(
                (signal, str(row.get("failureMessage") or ""), 1.0))
    seconds = sorted(set(reqs) | set(durations) | set(failed))
    step_names = sorted({name for (_sec, name) in step_reqs})
    return _frames_from_buckets(reqs, durations, failed, {}, {}, seconds,
                                step_names=step_names, step_reqs=step_reqs,
                                step_durs=step_durs, step_fails=step_fails,
                                fail_samples=fail_samples)


def _read_export_delta(path: str, pos: int, sink: list[str],
                       final: bool = False) -> int:
    pass
    try:
        with open(path, "r", encoding="utf-8") as f:
            f.seek(pos)
            chunk = f.read()
    except FileNotFoundError:
        return pos
    if not chunk:
        return pos
    cut = chunk.rfind("\n")
    if cut == -1:
        if final:
            sink.extend(chunk.splitlines())
            return pos + len(chunk)
        return pos
    sink.extend(chunk[:cut].splitlines())
    if final and cut + 1 < len(chunk):
        sink.append(chunk[cut + 1:])
        return pos + len(chunk)
    return pos + cut + 1


def _breaker_trip(frames: list[dict], breaker_rate: float) -> tuple[int, float] | None:
    pass
    if len(frames) < 2:
        return None
    max_off = frames[-1]["secondOffset"]
    for fr in frames:
        if fr["secondOffset"] < max_off and fr["errorRate"] > breaker_rate:
            return fr["secondOffset"], fr["errorRate"]
    return None


def _terminate_engine(proc) -> None:
    pass
    try:
        proc.kill()
    except Exception:
        pass
    try:
        proc.wait(timeout=5)
    except Exception:  # noqa: BLE001
        pass


def _run_k6(artifact: str, budget_ms: float,
             breaker_rate: float | None = None,
             partial_shipper=None,
             tls_policy: dict | None = None,
             events: list[dict] | None = None) -> tuple[RunOutcome, list[str]]:
    pass
    if events is None:
        events = []
    if not artifact or not os.path.isfile(artifact) or not os.access(artifact, os.R_OK):
        return (RunOutcome(result="fail", error_type=3,
                           error_detail=f"压测无法启动：编译产物不存在或不可读「{artifact}」，"
                                        f"请先在场景页重新编译后再发起运行"),
                [])
    if shutil.which(K6_BINARY) is None:
        return (RunOutcome(result="fail", error_type=3,
                           error_detail="k6 引擎二进制缺失（AGPL 进程边界处置=用户自装，"
                                        "本系统不随包分发）：请在执行机 PATH 安装 k6 后重试，"\
                                        "禁止静默切换 Gatling 顶替备选引擎"),
                [])
    # B-10: claim tier -> engine flags (pure map, never a silent relaxation;
    # custom_ca rides k6's built-in trust store with an honesty event naming
    # the CA-flag gap — self-signed envs on custom_ca fail visibly in-press).
    from driver.perf_tls import k6_tls_flags
    tls_flags = k6_tls_flags(tls_policy or {})
    if (tls_policy or {}).get("mode") == "custom_ca":
        events.append({"secondOffset": 0, "eventKind": "tls-tier-note",
                       "detail": "TLS 档 custom_ca：k6 命令面无 CA 注入旗标，本压测按引擎"
                                 "内置信任库校验（自签证书请求将以 TLS 错误失败，"
                                 "如需放宽请显式改档 insecure）"})
    budget_sec = max(float(budget_ms or DEFAULT_BUDGET_MS), 1.0) / 1000.0
    started = time.monotonic()
    with tempfile.TemporaryDirectory(prefix="tp-perf-") as td:
        export_path = os.path.join(td, "metrics.jsonl")
        log_path = os.path.join(td, "engine.log")
        with open(log_path, "w", encoding="utf-8") as log_f:
            proc = subprocess.Popen(
                [K6_BINARY, "run", "--quiet", *tls_flags,
                 "--out", f"json={export_path}", artifact],
                stdout=log_f, stderr=log_f, text=True, cwd=td)
        timed_out = False
        tripped: tuple[int, float] | None = None
        lines: list[str] = []
        read_pos = 0
        while True:
            exit_code = proc.poll()
            read_pos = _read_export_delta(export_path, read_pos, lines)
            if exit_code is not None:
                break
            if time.monotonic() - started >= budget_sec:
                timed_out = True
                _terminate_engine(proc)
                exit_code = proc.returncode
                break
            if breaker_rate is not None:
                tripped = _breaker_trip(normalize_frames(lines), breaker_rate)
                if tripped is not None:
                    _terminate_engine(proc)
                    exit_code = proc.returncode
                    break
            # b2 in-press partial frames: hand the freshest normalized seconds
            # to the shipper each poll cycle (it self-throttles by flush window;
            # shipper absent = zero side effect, legacy cadence untouched).
            if partial_shipper is not None:
                partial_shipper(normalize_frames(lines))
            time.sleep(BREAKER_POLL_SEC)
        read_pos = _read_export_delta(export_path, read_pos, lines, final=True)
        try:
            with open(log_path, "r", encoding="utf-8", errors="replace") as f:
                engine_log = f.read()
        except OSError:
            engine_log = ""
        elapsed_ms = int((time.monotonic() - started) * 1000)
        frames = normalize_frames(lines)
        if tripped is not None:
            off, rate = tripped
            events.append({"secondOffset": off, "eventKind": "engine-abort",
                           "detail": _clip_detail(
                               f"熔断自动停止：第 {off} 秒错误率 {rate:.1%} 超过熔断阈值 "
                               f"{breaker_rate:.1%}，引擎进程已定向终止（停压优先）；"
                               f"引擎输出尾={engine_log[-500:]}")})
            return (RunOutcome(result="fail", error_type=3,
                               error_detail=f"已熔断：错误率{rate:.1%} 超阈值"
                                            f"{breaker_rate:.1%}，自动停止",
                               perf_frames=frames, perf_events=events,
                               perf_status=3, duration_ms=elapsed_ms), lines)
        if timed_out:
            events.append({"secondOffset": len(frames),
                           "eventKind": "engine-timeout",
                           "detail": _clip_detail(
                               f"k6 压测超过预算 {budget_sec:.0f}s 未返回，已定向终止引擎"
                               f"进程（失联收敛停压）；引擎输出尾={engine_log[-500:]}")})
            return (RunOutcome(result="fail", error_type=3,
                               error_detail=f"k6 压测预算耗尽（>{budget_sec:.0f}s），"
                                            f"已终止并收敛停压",
                               perf_frames=frames, perf_events=events,
                               duration_ms=elapsed_ms), lines)
        if exit_code == 0:
            return (RunOutcome(result="pass",
                               perf_frames=frames, perf_events=events,
                               perf_status=2, duration_ms=elapsed_ms), lines)
        events.append({"secondOffset": len(frames),
                       "eventKind": "engine-exit",
                       "detail": _clip_detail(
                           f"k6 进程非零退出（exit code {exit_code}）；"
                           f"引擎输出尾={engine_log[-3000:]}")})
        return (RunOutcome(result="fail", error_type=3,
                           error_detail=_clip_detail(
                               f"k6 压测进程异常退出（exit code {exit_code}）：\n"
                               f"{engine_log or '（引擎无输出）'}"),
                           perf_frames=frames, perf_events=events,
                           duration_ms=elapsed_ms), lines)


def _find_gatling_log(results_root: str) -> str | None:
    """Locate simulation.log under the results tree. The CLI nests it as
    <root>/<simulation>/<run-stamp>/simulation.log and — depending on version
    and how -rf is resolved — may add another "results/" wrapper, so the scan
    covers the given root and one candidate wrapper beneath it. Newest file
    wins when a rerun left more than one stamp (single run dir per invocation
    is the CLI contract; the fallback keeps our own temp dir deterministic)."""
    found: list[str] = []
    roots = [results_root, os.path.join(results_root, "results")]
    for root in roots:
        for dirpath, _dirs, files in os.walk(root):
            for name in files:
                if name == "simulation.log":
                    found.append(os.path.join(dirpath, name))
    if not found:
        return None
    return max(found, key=os.path.getmtime)


def _run_gatling(artifact: str, budget_ms: float,
                 breaker_rate: float | None = None,
                 partial_shipper=None,
                 tls_policy: dict | None = None,
                 events: list[dict] | None = None) -> tuple[RunOutcome, list]:
    """Gatling subprocess execution : read the core-compiled
 manifest (fqcn/classesDir/sourceFile), launch the engine through the JDK java
 launcher with the compiled classes on the classpath
 (`java --add-opens java.base/java.lang=ALL-UNNAMED -cp <classes>:<lib>/*
 io.gatling.app.Gatling -s <fqcn> -rf <results> -nr`), tail the binary
 simulation.log into second-level frames, same budget/breaker/exit semantics
 as the k6 branch (three-line math shared via _frames_from_buckets, breaker
 judgment shared via _breaker_trip).

 The 3.x CLI has no source-folder option (javap GatlingCliOptions, live probe
 tick 1480) and the -nr flag skips HTML reports — the product never consumes
 them (D6: metric recompute single source from the binary log). Engine jars
 ride GATLING_LIB (installkit drop dir, env-configurable = no hard-coded
 paths); the AGPL body never enters this repo.

 Ordering discipline mirrors _run_k6: artifact check before environment check
 (dispatch-chain break named before environment gap). Missing lib/java =
 human-worded installkit pointer — never a silent k6 substitution. The
 results tree lives in our own temp dir (cleaned on exit), so the scan for
 simulation.log cannot pick up another run's leftovers."""
    if not artifact or not os.path.isfile(artifact) or not os.access(artifact, os.R_OK):
        return (RunOutcome(result="fail", error_type=3,
                           error_detail=f"压测无法启动：编译产物（manifest）不存在或不可读「{artifact}」，"
                                        f"请先在场景页重新编译后再发起运行"),
                [])
    try:
        with open(artifact, "r", encoding="utf-8") as f:
            manifest = json.load(f)
        fqcn = str(manifest["fqcn"])
        classes_dir = str(manifest["classesDir"])
    except (ValueError, KeyError, OSError) as corrupt:
        return (RunOutcome(result="fail", error_type=3,
                           error_detail=f"压测无法启动：编译产物 manifest 损坏或字段缺失（{corrupt}），"
                                        f"请先在场景页重新编译后再发起运行"),
                [])
    if not os.path.isdir(classes_dir):
        return (RunOutcome(result="fail", error_type=3,
                           error_detail=f"压测无法启动：编译产物 classesDir 缺失「{classes_dir}」"
                                        f"（core 编译段未落盘），请重新编译场景"),
                [])
    lib_dir = (os.environ.get(GATLING_LIB_ENV) or "").strip()
    if not lib_dir or not os.path.isdir(lib_dir):
        return (RunOutcome(result="fail", error_type=3,
                           error_detail="Gatling 引擎运行件缺失（AGPL 进程边界处置=用户自装，"
                                        "本系统不随包分发）：请在执行机安装 Gatling 发行件并将其 lib 目录"
                                        "配置为环境变量 GATLING_LIB（安装配方见主机 service-agent 安装件），"
                                        "禁止静默切换 k6 顶替备选引擎"),
                [])
    java_bin = shutil.which("java")
    if java_bin is None:
        return (RunOutcome(result="fail", error_type=3,
                           error_detail="Gatling 无法启动：执行机 PATH 中未找到 java 可执行件"
                                        "（引擎经 JDK 进程边界拉起），请安装 JDK 17+ 后重试，"
                                        "禁止静默切换 k6 顶替备选引擎"),
                [])
    budget_sec = max(float(budget_ms or DEFAULT_BUDGET_MS), 1.0) / 1000.0
    started = time.monotonic()
    if events is None:
        events = []
    with tempfile.TemporaryDirectory(prefix="tp-perf-gat-") as td:
        # B-10: claim tier -> JVM properties. custom_ca without material is
        # fail-closed BEFORE the engine launches (dispatch anomaly named,
        # never passed as the default tier); insecure rides the engine switch.
        from driver.perf_tls import GatlingTlsError, gatling_tls_properties
        try:
            tls_props = gatling_tls_properties(tls_policy or {}, td)
        except GatlingTlsError as gap:
            return (RunOutcome(result="fail", error_type=3,
                               error_detail=f"压测无法启动：{gap}"), [])
        results_root = os.path.join(td, "results")
        os.makedirs(results_root, exist_ok=True)
        log_path = os.path.join(td, "engine.log")
        with open(log_path, "w", encoding="utf-8") as log_f:
            # Same-process-group inheritance (no start_new_session): runner killpg
            # reaches the engine tree; our own stops go through _terminate_engine
            # gatling.data.file.bufferSize=256 (live-proven): the engine's
            # FileDataWriter buffers simulation.log in an 8 KB buffer by
            # default, so a low-RPS press flushes nothing until exit — the
            # in-press breaker and frame polling would judge an empty log.
            # A small buffer streams records while the run is alive.
            proc = subprocess.Popen(
                [java_bin, "-Dgatling.data.file.bufferSize=256", *tls_props,
                 "--add-opens", "java.base/java.lang=ALL-UNNAMED",
                 "-cp", classes_dir + os.pathsep + os.path.join(lib_dir, "*"),
                 "io.gatling.app.Gatling", "-s", fqcn,
                 "-rf", results_root, "-nr"],
                stdout=log_f, stderr=log_f, text=True, cwd=td)
        timed_out = False
        tripped: tuple[int, float] | None = None
        points: list[dict] = []
        cur = _GatlingCursor(b"")
        frames: list[dict] = []
        while True:
            exit_code = proc.poll()
            frames, points = _gatling_poll_once(cur, results_root, points, frames)
            if exit_code is not None:
                break
            if time.monotonic() - started >= budget_sec:
                timed_out = True
                _terminate_engine(proc)
                exit_code = proc.returncode
                break
            if breaker_rate is not None:
                # Breaker judged only while the engine is still pressing (same
                # placement as the k6 branch): a self-exited run reports its
                # outcome, it is not retroactively tripped.
                tripped = _breaker_trip(frames, breaker_rate)
                if tripped is not None:
                    _terminate_engine(proc)
                    exit_code = proc.returncode
                    break
            # b2 in-press partial frames (same placement as the k6 branch —
            # only while pressing; the shipper self-throttles to the flush window).
            if partial_shipper is not None:
                partial_shipper(frames)
            time.sleep(BREAKER_POLL_SEC)
        # Engine gone: final drain so the tail records are never lost. Breaker is
        # NOT re-judged here — a completed run reports what happened, the trip
        # only fires while the engine is still pressing (same as the k6 branch).
        frames, points = _gatling_poll_once(cur, results_root, points, frames)
        try:
            with open(log_path, "r", encoding="utf-8", errors="replace") as f:
                engine_log = f.read()
        except OSError:
            engine_log = ""
        elapsed_ms = int((time.monotonic() - started) * 1000)
        if tripped is not None:
            off, rate = tripped
            events.append({"secondOffset": off, "eventKind": "engine-abort",
                           "detail": _clip_detail(
                               f"熔断自动停止：第 {off} 秒错误率 {rate:.1%} 超过熔断阈值 "
                               f"{breaker_rate:.1%}，引擎进程已定向终止（停压优先）；"
                               f"引擎输出尾={engine_log[-500:]}")})
            return (RunOutcome(result="fail", error_type=3,
                               error_detail=f"已熔断：错误率{rate:.1%} 超阈值"
                                            f"{breaker_rate:.1%}，自动停止",
                               perf_frames=frames, perf_events=events,
                               perf_status=3, duration_ms=elapsed_ms), points)
        if timed_out:
            events.append({"secondOffset": len(frames),
                           "eventKind": "engine-timeout",
                           "detail": _clip_detail(
                               f"Gatling 压测超过预算 {budget_sec:.0f}s 未返回，已定向终止引擎"
                               f"进程（失联收敛停压）；引擎输出尾={engine_log[-500:]}")})
            return (RunOutcome(result="fail", error_type=3,
                               error_detail=f"Gatling 压测预算耗尽（>{budget_sec:.0f}s），"
                                            f"已终止并收敛停压",
                               perf_frames=frames, perf_events=events,
                               duration_ms=elapsed_ms), points)
        if exit_code == 0:
            if not points:
                # Engine exited clean but produced nothing attributable: an
                # empty-but-green run is a silent failure — name the product gap
                events.append({"secondOffset": 0, "eventKind": "engine-exit",
                               "detail": _clip_detail(
                                   f"Gatling 进程正常退出但未发现 simulation.log 结果文件"
                                   f"（结果目录={results_root}）；引擎输出尾={engine_log[-3000:]}")})
                return (RunOutcome(result="fail", error_type=3,
                                   error_detail=_clip_detail(
                                       "Gatling 压测未产出结果文件 simulation.log：引擎可能"
                                       "未执行任何场景（检查仿真编译与断言配置）；\n"
                                       f"{engine_log or '（引擎无输出）'}"),
                                   perf_events=events, duration_ms=elapsed_ms), points)
            return (RunOutcome(result="pass", perf_frames=frames,
                               perf_events=events, perf_status=2,
                               duration_ms=elapsed_ms), points)
        events.append({"secondOffset": len(frames),
                       "eventKind": "engine-exit",
                       "detail": _clip_detail(
                           f"Gatling 进程非零退出（exit code {exit_code}）；"
                           f"引擎输出尾={engine_log[-3000:]}")})
        return (RunOutcome(result="fail", error_type=3,
                           error_detail=_clip_detail(
                               f"Gatling 压测进程异常退出（exit code {exit_code}）：\n"
                               f"{engine_log or '（引擎无输出）'}"),
                           perf_frames=frames, perf_events=events,
                           duration_ms=elapsed_ms), points)


def _gatling_poll_once(cur: _GatlingCursor, results_root: str, points: list[dict],
                       frames: list[dict]) -> tuple[list[dict], list[dict]]:
    """One poll cycle of the growing binary log: read the whole file, parse the
    delta with the persistent cursor (append-only => position/cache stay valid),
    extend points, recompute frames. A torn tail is retried next cycle (the
    mid-record stays rolled back until its remaining bytes land).

    Returns (frames, points). Breaker judgment lives in the caller loop (k6
    placement: only while pressing)."""
    log_file = _find_gatling_log(results_root)
    if log_file is not None:
        try:
            with open(log_file, "rb") as f:
                data = f.read()
        except OSError:
            data = None
        if data is not None:
            cur.data = data
            new_points, _trunc, _meta = _parse_gatling_stream(cur, data)
            if new_points:
                points = points + new_points
                frames = normalize_gatling_frames(points)
    return frames, points


def _run_jmeter(artifact: str, budget_ms: float,
                breaker_rate: float | None = None,
                partial_shipper=None,
                tls_policy: dict | None = None,
                events: list[dict] | None = None) -> tuple[RunOutcome, list[str]]:
    pass
    if events is None:
        events = []
    if not artifact or not os.path.isfile(artifact) or not os.access(artifact, os.R_OK):
        return (RunOutcome(result="fail", error_type=3,
                           error_detail=f"压测无法启动：编译产物不存在或不可读「{artifact}」，"
                                        f"请先在场景页重新编译后再发起运行"),
                [])
    if shutil.which(JMETER_BINARY) is None:
        return (RunOutcome(result="fail", error_type=3,
                           error_detail="JMeter 引擎二进制缺失（Apache-2.0 进程边界处置=执行机"
                                        "自装，本系统不随包分发）：请在执行机 PATH 安装 JMeter "
                                        "后重试，禁止静默切换 K6/Gatling 顶替备选引擎"),
                [])
    tier = (tls_policy or {}).get("mode")
    if tier in ("insecure", "custom_ca"):
        events.append({"secondOffset": 0, "eventKind": "tls-tier-note",
                       "detail": f"TLS 档 {tier}：JMeter 命令面无统一注入旗标，本次按 JVM "
                                 "信任库校验（放宽需在引擎侧显式配置 keystore/系统属性，"
                                 "执行器不做档位改写）"})
    budget_sec = max(float(budget_ms or DEFAULT_BUDGET_MS), 1.0) / 1000.0
    started = time.monotonic()
    with tempfile.TemporaryDirectory(prefix="tp-perf-jtl-") as td:
        jtl_path = os.path.join(td, "result.jtl")
        jmeter_log = os.path.join(td, "jmeter.log")
        log_path = os.path.join(td, "engine.log")
        with open(log_path, "w", encoding="utf-8") as log_f:
            proc = subprocess.Popen(
                [JMETER_BINARY, "-n", "-t", artifact, "-l", jtl_path,
                 "-j", jmeter_log],
                stdout=log_f, stderr=log_f, text=True, cwd=td)
        timed_out = False
        tripped: tuple[int, float] | None = None
        rows: list[str] = []
        read_pos = 0
        while True:
            exit_code = proc.poll()
            read_pos = _read_export_delta(jtl_path, read_pos, rows)
            if exit_code is not None:
                break
            if time.monotonic() - started >= budget_sec:
                timed_out = True
                _terminate_engine(proc)
                exit_code = proc.returncode
                break
            if breaker_rate is not None:
                tripped = _breaker_trip(normalize_jmeter_frames(rows), breaker_rate)
                if tripped is not None:
                    _terminate_engine(proc)
                    exit_code = proc.returncode
                    break
            if partial_shipper is not None:
                partial_shipper(normalize_jmeter_frames(rows))
            time.sleep(BREAKER_POLL_SEC)
        read_pos = _read_export_delta(jtl_path, read_pos, rows, final=True)
        try:
            with open(log_path, "r", encoding="utf-8", errors="replace") as f:
                engine_log = f.read()
        except OSError:
            engine_log = ""
        elapsed_ms = int((time.monotonic() - started) * 1000)
        frames = normalize_jmeter_frames(rows)
        if tripped is not None:
            off, rate = tripped
            events.append({"secondOffset": off, "eventKind": "engine-abort",
                           "detail": _clip_detail(
                               f"熔断自动停止：JMeter 第 {off} 秒错误率 {rate:.1%} 超过熔断"
                               f"阈值 {breaker_rate:.1%}，引擎进程已定向终止（停压优先）；"
                               f"引擎输出尾={engine_log[-500:]}")})
            return (RunOutcome(result="fail", error_type=3,
                               error_detail=f"已熔断：错误率{rate:.1%} 超阈值"
                                            f"{breaker_rate:.1%}，自动停止",
                               perf_frames=frames, perf_events=events,
                               perf_status=3, duration_ms=elapsed_ms), rows)
        if timed_out:
            events.append({"secondOffset": len(frames),
                           "eventKind": "engine-timeout",
                           "detail": _clip_detail(
                               f"JMeter 压测超过预算 {budget_sec:.0f}s 未返回，已定向终止"
                               f"引擎进程（失联收敛停压）；引擎输出尾={engine_log[-500:]}")})
            return (RunOutcome(result="fail", error_type=3,
                               error_detail=f"JMeter 压测预算耗尽（>{budget_sec:.0f}s），"
                                            f"已终止并收敛停压",
                               perf_frames=frames, perf_events=events,
                               duration_ms=elapsed_ms), rows)
        if exit_code == 0:
            return (RunOutcome(result="pass",
                               perf_frames=frames, perf_events=events,
                               perf_status=2, duration_ms=elapsed_ms), rows)
        events.append({"secondOffset": len(frames),
                       "eventKind": "engine-exit",
                       "detail": _clip_detail(
                           f"JMeter 进程非零退出（exit code {exit_code}）；"
                           f"引擎输出尾={engine_log[-3000:]}")})
        return (RunOutcome(result="fail", error_type=3,
                           error_detail=_clip_detail(
                               f"JMeter 压测进程异常退出（exit code {exit_code}）：\n"
                               f"{engine_log or '（引擎无输出）'}"),
                           perf_frames=frames, perf_events=events,
                           duration_ms=elapsed_ms), rows)


def run_perf_task(task: dict) -> RunOutcome:
    pass
    started = time.monotonic()
    try:
        envelope = json.loads(str(task.get("actionJson") or "") or "{}")
    except Exception:
        envelope = {}
    if isinstance(envelope, dict) and envelope.get("kind") not in (None, ENVELOPE_KIND):
        return RunOutcome(result="fail", error_type=3,
                          error_detail=f"压测任务信封 kind 非法（期望 {ENVELOPE_KIND}）: "
                                       f"{envelope.get('kind')!r}")
    perf = _perf_context(task)
    if perf is None:
        return RunOutcome(result="fail", error_type=3,
                          error_detail="压测运行上下文缺失（运行可能已被删除或任务参数不完整），"
                                       "本次执行已终止")
    engine = str(perf.get("engine") or "").strip().upper()
    if engine not in ENGINE_VALUES:
        return RunOutcome(result="fail", error_type=3,
                          error_detail=f"压测引擎标识非法（{engine or '空'}），可选=" + "/".join(ENGINE_VALUES))
    budget_ms = task.get("timeoutMs") or DEFAULT_BUDGET_MS
    breaker = _breaker_rate_from_snapshot(perf.get("pressureSnapshot"))
    # B-10 : claim-tail tlsPolicy -> defensive normalization only
    # (mirror fail-safe strict via driver/tls_client — no tier re-deciding on
    # this side, single source = core EnvTlsSupport resolution). Missing key
    # (old core / legacy run) = strict default, zero argv drift on engines.
    from driver.tls_client import normalize_policy
    tls_policy = normalize_policy(perf.get("tlsPolicy"))
    # b2 partial-frame channel: sink injected by the runner via task payload
    # (_perfPartialSink), cadence from the claim perf context (core K8 projection).
    # Any piece absent = shipper None = legacy shape, engines press unshipped.
    shipper = make_partial_shipper({
        "sink": task.get("_perfPartialSink"),
        "runId": perf.get("runId"),
        "flushSeconds": perf.get("framesFlushSeconds"),
        "maxShipFrames": perf.get("maxShipFrames"),
    })
    # A-10: the dispatch handle may be an object-storage key of a compiled
    # bundle — materialize it into a temp dir that outlives the engine subprocess
    # (k6 reads its dataset sidecar relative to the script; Gatling consumes the
    # rebased manifest). Compatibility read: a local path (or an
    # empty handle) passes through with zero network calls, so in-flight runs
    # and local dev stay untouched. Materialize-or-fail is shared by both engine
    # branches (red-line-6 single wiring point).
    with tempfile.TemporaryDirectory(prefix="tp-perf-mat-") as mat_dir:
        local_handle, mat_error = materialize_artifact(
            str(perf.get("artifact") or ""), task.get("_apiBaseUrl"), mat_dir)
        if mat_error:
            return RunOutcome(result="fail", error_type=3, error_detail=mat_error)
        # B-10 shared event line: engine-side honesty notes (k6 custom_ca tier
        # note) ride the outcome even when the engine exits early.
        tls_events: list[dict] = []
        if engine == "GATLING":
            # Subprocess form : the AGPL engine body is user-installed, we
            # run the core-compiled simulation through the gatling CLI. Missing
            # binary / artifact = human-worded fail — never a silent k6
            # substitution (engine mutual-exclusion discipline, same stance as
            # the k6 branch).
            outcome, _pts = _run_gatling(str(local_handle or ""),
                                         float(budget_ms), breaker_rate=breaker,
                                         partial_shipper=shipper,
                                         tls_policy=tls_policy, events=tls_events)
        elif engine == "JMETER":
            # Subprocess form: the executor runs the core-compiled plan headless;
            # JTL raw rows are the only truth parsed on this side (engine summary
            # numbers never consumed). Missing binary / artifact = human-worded
            # fail — never a silent substitution by another engine (mutual-
            # exclusion discipline, same stance as the k6 and Gatling branches).
            outcome, _rows = _run_jmeter(str(local_handle or ""),
                                         float(budget_ms), breaker_rate=breaker,
                                         partial_shipper=shipper,
                                         tls_policy=tls_policy, events=tls_events)
        else:
            outcome, _lines = _run_k6(str(local_handle or ""), float(budget_ms),
                                      breaker_rate=breaker, partial_shipper=shipper,
                                      tls_policy=tls_policy, events=tls_events)
        if tls_events and outcome.perf_events is None:
            outcome.perf_events = tls_events
    if outcome.duration_ms == 0:
        outcome.duration_ms = int((time.monotonic() - started) * 1000)
    return outcome


def main(argv: list[str]) -> int:
    if len(argv) != 3:
        print("用法: perf_worker.py <task.json> <outcome.json>", file=sys.stderr)
        return 2
    task_f, out_f = argv[1], argv[2]
    try:
        with open(task_f, "r", encoding="utf-8") as f:
            task = json.load(f)
    except Exception as e:  # noqa: BLE001
        print(f"任务文件读取失败: {e}", file=sys.stderr)
        return 2

    try:
        outcome = run_perf_task(task)
    except Exception as e:
        outcome = RunOutcome(result="fail", error_type=3, error_detail=f"压测执行异常: {e}")

    try:
        with open(out_f, "w", encoding="utf-8") as f:
            json.dump(outcome.to_dict(), f, ensure_ascii=False)
    except Exception as e:  # noqa: BLE001
        print(f"结果文件写入失败: {e}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
