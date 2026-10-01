#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Benchmark local models as the opencode `coder` agent on real, already merged Cringle issues.

For every (model, task, run):
  1. a git worktree is created at the parent of the commit that originally solved the task,
  2. `opencode run --agent coder --model lm-studio-local/<model> "<work package prompt>"` is started in it,
  3. afterwards the ORIGINAL tests of that commit are copied into the worktree (the model never saw them)
     and run with Gradle: that is the oracle,
  4. metrics are appended to results.jsonl (pass/fail, tests, time, tokens, tool errors, scope violations).

Commands:  list | run | report   (see README.md)
"""
import argparse
import csv
import fnmatch
import json
import os
import platform
import shutil
import signal
import statistics
import subprocess
import sys
import tempfile
import time
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[1]
TASKS_DIR = HERE / "tasks"
IS_WIN = platform.system() == "Windows"
PROVIDER = "lm-studio-local"
OVERLAY = ("AGENTS.md", "opencode.json")  # copied from the current checkout so that every task sees today's rules


# ---------------------------------------------------------------- helpers

def sh(cmd, cwd, check=True, timeout=None, env=None):
    p = subprocess.run(cmd, cwd=str(cwd), capture_output=True, text=True, encoding="utf-8", errors="replace",
                       timeout=timeout, env=env)
    if check and p.returncode != 0:
        raise RuntimeError(f"{' '.join(map(str, cmd))} failed ({p.returncode}): {p.stderr.strip() or p.stdout.strip()}")
    return p


def git(*args, cwd=REPO, check=True):
    return sh(["git", *args], cwd, check).stdout


def git_bytes(*args, cwd=REPO):
    p = subprocess.run(["git", *args], cwd=str(cwd), capture_output=True)
    if p.returncode != 0:
        raise RuntimeError(f"git {' '.join(args)} failed: {p.stderr.decode(errors='replace')}")
    return p.stdout


def write_commit_file(wt, commit, rel):
    """Writes the version of `rel` that exists in `commit` into the worktree."""
    target = wt / rel
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(git_bytes("show", f"{commit}:{rel}"))


def kill_tree(p):
    try:
        if IS_WIN:
            subprocess.run(["taskkill", "/PID", str(p.pid), "/T", "/F"], capture_output=True)
        else:
            os.killpg(os.getpgid(p.pid), signal.SIGKILL)
    except Exception:
        p.kill()


def load_tasks(selected):
    tasks = []
    for d in sorted(TASKS_DIR.iterdir()):
        if not (d / "task.json").exists():
            continue
        t = json.loads((d / "task.json").read_text(encoding="utf-8"))
        t["prompt"] = (d / "prompt.md").read_text(encoding="utf-8")
        if selected and not any(t["id"] == s or t["id"].startswith(s + "-") for s in selected):
            continue
        tasks.append(t)
    return tasks


# ---------------------------------------------------------------- one run

def prepare_worktree(task, wt):
    git("worktree", "add", "--detach", str(wt), task["commit"] + "^")
    for f in OVERLAY:
        if (REPO / f).exists():
            shutil.copyfile(REPO / f, wt / f)
    for f in task["setup_files"]:
        write_commit_file(wt, task["commit"], f)
    git("add", "-A", cwd=wt)
    git("-c", "user.name=bench", "-c", "user.email=bench@localhost", "commit", "-q", "--allow-empty", "-m", "bench setup", cwd=wt)


def remove_worktree(wt):
    try:
        git("worktree", "remove", "--force", str(wt), check=False)
    finally:
        shutil.rmtree(wt, ignore_errors=True)
        git("worktree", "prune", check=False)


def warmup(url, model):
    """Loads the model into LM Studio (just-in-time loading) so that the load time is not part of a run."""
    body = json.dumps({"model": model, "messages": [{"role": "user", "content": "ok"}], "max_tokens": 1}).encode()
    req = urllib.request.Request(url.rstrip("/") + "/chat/completions", data=body, headers={"Content-Type": "application/json"})
    try:
        urllib.request.urlopen(req, timeout=900).read()
    except Exception as e:  # noqa: BLE001
        print(f"  warm-up of {model} failed ({e}); continuing")


def run_agent(task, model, wt, args, logfile):
    exe = shutil.which("opencode") or "opencode"
    cmd = [exe, "run", "--agent", args.agent, "--model", f"{PROVIDER}/{model}", "--format", "json", task["prompt"]]
    env = dict(os.environ)
    # belt and braces: make sure the agent really uses the model under test
    env["OPENCODE_CONFIG_CONTENT"] = json.dumps({"agent": {args.agent: {"model": f"{PROVIDER}/{model}"}}})
    kw = {} if IS_WIN else {"start_new_session": True}
    start = time.time()
    timed_out = False
    with open(logfile, "wb") as out:
        p = subprocess.Popen(cmd, cwd=str(wt), stdout=out, stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL, env=env, **kw)
        try:
            rc = p.wait(timeout=args.timeout)
        except subprocess.TimeoutExpired:
            timed_out = True
            kill_tree(p)
            rc = -1
    return {"rc": rc, "seconds": round(time.time() - start, 1), "timed_out": timed_out}


def apply_reference(task, wt):
    """Validation mode: apply the real solution (everything but the oracle tests) instead of running a model."""
    names = git("diff", "--name-only", task["commit"] + "^", task["commit"]).split()
    for rel in names:
        if rel in task["oracle_tests"] or not any(fnmatch.fnmatch(rel, g) for g in task["allowed"]):
            continue  # the commit may contain more than this task covers
        exists = subprocess.run(["git", "cat-file", "-e", f"{task['commit']}:{rel}"], cwd=str(REPO), capture_output=True).returncode == 0
        if exists:
            write_commit_file(wt, task["commit"], rel)
        elif (wt / rel).exists():
            (wt / rel).unlink()


def parse_log(path):
    r = {"tokens_in": 0, "tokens_out": 0, "tokens_cache_read": 0, "steps": 0, "tool_calls": 0, "tool_errors": 0, "usage_found": False}
    try:
        lines = Path(path).read_text(encoding="utf-8", errors="replace").splitlines()
    except OSError:
        return r
    for line in lines:
        line = line.strip()
        if not line.startswith("{"):
            continue
        try:
            ev = json.loads(line)
        except ValueError:
            continue
        part = ev.get("part") if isinstance(ev.get("part"), dict) else ev
        ptype = part.get("type") or ev.get("type")
        if ptype in ("step-finish", "step_finish"):
            tk = part.get("tokens") or {}
            if "input" in tk:
                r["usage_found"] = True
                r["steps"] += 1
                r["tokens_in"] += int(tk.get("input") or 0)
                r["tokens_out"] += int(tk.get("output") or 0)
                r["tokens_cache_read"] += int((tk.get("cache") or {}).get("read") or 0)
        elif ptype == "tool":
            status = (part.get("state") or {}).get("status")
            if status in ("completed", "error"):
                r["tool_calls"] += 1
                if status == "error":
                    r["tool_errors"] += 1
    return r


def model_changes(task, wt, patch_file):
    git("add", "-A", cwd=wt)
    files = [f for f in git("diff", "--cached", "--name-only", "HEAD", cwd=wt).splitlines() if f]
    patch = git("diff", "--cached", "HEAD", cwd=wt)
    Path(patch_file).write_text(patch, encoding="utf-8")
    lines = sum(1 for l in patch.splitlines() if l[:1] in "+-" and not l.startswith(("+++", "---")))
    allowed = task["allowed"]
    violations = [f for f in files if not any(fnmatch.fnmatch(f, g) for g in allowed)]
    return files, lines, violations


def run_oracle(task, wt, args):
    for rel in task["oracle_tests"]:
        write_commit_file(wt, task["commit"], rel)
    results = wt / "kotlin" / task["module"] / "build" / "test-results"
    shutil.rmtree(results, ignore_errors=True)
    gradlew = str(wt / ("gradlew.bat" if IS_WIN else "gradlew"))
    cmd = [gradlew, f":{task['module']}:test", "--console=plain", "-q", "--continue"]
    for c in task["oracle_classes"]:
        cmd += ["--tests", c]
    start = time.time()
    try:
        p = sh(cmd, wt, check=False, timeout=args.gradle_timeout)
        rc, tail = p.returncode, (p.stdout + p.stderr)[-2500:]
    except subprocess.TimeoutExpired:
        rc, tail = -1, "gradle timed out"
    total = failed = 0
    missing = []
    for c in task["oracle_classes"]:
        f = results / "test" / f"TEST-{c}.xml"
        if not f.exists():
            missing.append(c)
            continue
        root = ET.parse(f).getroot()
        total += int(root.get("tests", 0))
        failed += int(root.get("failures", 0)) + int(root.get("errors", 0))
    return {"gradle_rc": rc, "oracle_seconds": round(time.time() - start, 1), "tests": total, "tests_failed": failed,
            "oracle_missing": missing, "gradle_tail": tail}


def run_one(task, model, run_no, args, out_dir):
    tag = f"{task['id']}_{model.replace('/', '_')}_{run_no}"
    wt = Path(args.work_dir) / tag
    log = out_dir / "logs" / f"{tag}.jsonl"
    patch = out_dir / "patches" / f"{tag}.patch"
    log.parent.mkdir(parents=True, exist_ok=True)
    patch.parent.mkdir(parents=True, exist_ok=True)
    rec = {"model": model, "task": task["id"], "difficulty": task["difficulty"], "run": run_no}
    try:
        prepare_worktree(task, wt)
        if args.reference:
            apply_reference(task, wt)
            rec.update(rc=0, seconds=0.0, timed_out=False)
            log.write_text("", encoding="utf-8")
        else:
            rec.update(run_agent(task, model, wt, args, log))
        rec.update(parse_log(log))
        files, lines, violations = model_changes(task, wt, patch)
        rec.update(files_changed=len(files), diff_lines=lines, scope_violations=violations)
        rec.update(run_oracle(task, wt, args))
        ok = rec["gradle_rc"] == 0 and not rec["oracle_missing"] and rec["tests"] > 0 and rec["tests_failed"] == 0
        if ok:
            rec["status"] = "PASS"
        elif not files:
            rec["status"] = "NO_CHANGE"
        elif rec["oracle_missing"]:
            rec["status"] = "COMPILE_FAIL"
        else:
            rec["status"] = "TEST_FAIL"
    except Exception as e:  # noqa: BLE001
        rec["status"] = "HARNESS_ERROR"
        rec["error"] = str(e)[:500]
    finally:
        if not args.keep:
            remove_worktree(wt)
    return rec


# ---------------------------------------------------------------- commands

def cmd_list(args):
    for t in load_tasks(args.tasks):
        print(f"{t['id']:26} {t['difficulty']:12} {t['module']:18} {t['commit']}  {t['title']}")


def cmd_run(args):
    tasks = load_tasks(args.tasks)
    if not tasks:
        sys.exit("no task selected")
    models = ["reference"] if args.reference else [m.strip() for m in args.models.split(",") if m.strip()]
    if not models:
        sys.exit("--models is required (comma separated LM Studio model ids)")
    out_dir = Path(args.out) / (args.label or time.strftime("%Y%m%d-%H%M%S"))
    out_dir.mkdir(parents=True, exist_ok=True)
    results = out_dir / "results.jsonl"
    Path(args.work_dir).mkdir(parents=True, exist_ok=True)
    print(f"results: {out_dir}")
    if args.dry_run:
        for m in models:
            for t in tasks:
                print(f"[{m}] {t['id']} x{args.runs}: opencode run --agent {args.agent} --model {PROVIDER}/{m} <{len(t['prompt'])} chars>")
        return
    for model in models:  # all runs of one model together: LM Studio keeps it loaded
        if not args.reference and not args.no_warmup:
            print(f"loading {model} ...")
            warmup(args.lmstudio_url, model)
        for task in tasks:
            for n in range(1, args.runs + 1):
                rec = run_one(task, model, n, args, out_dir)
                with open(results, "a", encoding="utf-8") as f:
                    f.write(json.dumps(rec) + "\n")
                print(f"[{model}] {task['id']} #{n}: {rec['status']:13} tests {rec.get('tests', 0) - rec.get('tests_failed', 0)}/{rec.get('tests', 0)}  "
                      f"{rec.get('seconds', 0):>6}s  tok {rec.get('tokens_in', 0)}/{rec.get('tokens_out', 0)}  "
                      f"toolerr {rec.get('tool_errors', 0)}  scope {len(rec.get('scope_violations', []))}"
                      + (f"  {rec['error']}" if rec.get("error") else ""))
    cmd_report(argparse.Namespace(paths=[str(out_dir)]))


def med(values):
    values = [v for v in values if v is not None]
    return statistics.median(values) if values else None


def cmd_report(args):
    recs = []
    for p in args.paths:
        p = Path(p)
        for f in ([p] if p.is_file() else sorted(p.rglob("results.jsonl"))):
            recs += [json.loads(l) for l in f.read_text(encoding="utf-8").splitlines() if l.strip()]
    if not recs:
        sys.exit("no results found")
    models = sorted({r["model"] for r in recs})
    tasks = sorted({r["task"] for r in recs})
    out = ["# Benchmark report", "", f"{len(recs)} runs, {len(models)} model(s), {len(tasks)} task(s).", "",
           "## Pass rate per task (passed/runs)", "", "| task | " + " | ".join(models) + " |", "|---|" + "---|" * len(models)]
    for t in tasks:
        row = []
        for m in models:
            rs = [r for r in recs if r["model"] == m and r["task"] == t]
            row.append(f"{sum(r['status'] == 'PASS' for r in rs)}/{len(rs)}" if rs else "-")
        out.append(f"| {t} | " + " | ".join(row) + " |")
    out += ["", "## Summary per model", "",
            "| model | pass rate | compile fails | no change | median s | median tokens in/out | tool errors/run | scope violations | harness errors |",
            "|---|---|---|---|---|---|---|---|---|"]
    for m in models:
        rs = [r for r in recs if r["model"] == m]
        n = len(rs)
        count = lambda s: sum(r["status"] == s for r in rs)  # noqa: E731
        tin = med([r.get("tokens_in") for r in rs if r.get("usage_found")])
        tout = med([r.get("tokens_out") for r in rs if r.get("usage_found")])
        out.append(f"| {m} | {count('PASS')}/{n} ({100 * count('PASS') // n}%) | {count('COMPILE_FAIL')} | {count('NO_CHANGE')} | "
                   f"{med([r.get('seconds') for r in rs])} | {tin}/{tout} | "
                   f"{round(sum(r.get('tool_errors', 0) for r in rs) / n, 1)} | {sum(len(r.get('scope_violations', [])) for r in rs)} | {count('HARNESS_ERROR')} |")
    text = "\n".join(out) + "\n"
    print(text)
    base = Path(args.paths[0])
    base = base if base.is_dir() else base.parent
    (base / "report.md").write_text(text, encoding="utf-8")
    keys = ["model", "task", "difficulty", "run", "status", "tests", "tests_failed", "seconds", "oracle_seconds", "tokens_in", "tokens_out",
            "steps", "tool_calls", "tool_errors", "files_changed", "diff_lines", "timed_out"]
    with open(base / "results.csv", "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(keys + ["scope_violations"])
        for r in recs:
            w.writerow([r.get(k) for k in keys] + [";".join(r.get("scope_violations", []))])


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    sub.add_parser("list").add_argument("tasks", nargs="*")
    r = sub.add_parser("run")
    r.add_argument("--models", help="comma separated LM Studio model ids, e.g. qwen3.8-27b,unsloth/qwen3.5-27b")
    r.add_argument("--tasks", nargs="*", default=[], help="task ids or prefixes (t01 t05); default: all")
    r.add_argument("--runs", type=int, default=3)
    r.add_argument("--agent", default="coder")
    r.add_argument("--timeout", type=int, default=1200, help="seconds per agent run")
    r.add_argument("--gradle-timeout", type=int, default=1800)
    r.add_argument("--out", default=str(REPO / "build" / "bench"))
    r.add_argument("--label")
    r.add_argument("--work-dir", default=str(Path(tempfile.gettempdir()) / "cringle-bench"))
    r.add_argument("--lmstudio-url", default="http://127.0.0.1:1234/v1")
    r.add_argument("--no-warmup", action="store_true")
    r.add_argument("--keep", action="store_true", help="keep the worktrees")
    r.add_argument("--reference", action="store_true", help="validate the tasks: apply the real solution instead of running a model")
    r.add_argument("--dry-run", action="store_true")
    rp = sub.add_parser("report")
    rp.add_argument("paths", nargs="+")
    args = ap.parse_args()
    {"list": cmd_list, "run": cmd_run, "report": cmd_report}[args.cmd](args)


if __name__ == "__main__":
    main()
