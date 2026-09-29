#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""One-time migration of docs/issues/*.md to GitHub issues.

What it does (with --apply):
  1. creates one GitHub issue per file (idempotent: existing issues with the same title are reused),
     with label `agent-task`, the matching milestone and status labels,
  2. rewrites legacy references ("issue 004") in the issue bodies and in docs/*.md to `#<number>`,
  3. adds a `**Depends on:** #a, #b` line and GitHub "blocked by" relationships,
  4. closes issues whose file says `status: done`,
  5. removes docs/issues/ with `git rm` and writes .migration-map.json (untracked).

Run `scripts/setup-github-repo.py --apply` first (labels and milestones must exist).
Without --apply nothing is sent to GitHub and nothing is changed: it prints the plan.
"""
import argparse
import json
import re
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from ghlib import MILESTONES, GhError, api, api_list, repo_name  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
ISSUE_DIR = ROOT / "docs" / "issues"
# Pull requests that already implemented issues before the migration.
DONE_PRS = {"001": 1, "002": 2}
BODY_SECTIONS = ["Context", "Owner decisions", "Scope", "Out of scope", "Design notes",
                 "Acceptance criteria", "Notes / Findings", "Result"]


def parse(path):
    text = path.read_text(encoding="utf-8").replace("\r\n", "\n")
    m = re.match(r"---\n(.*?)\n---\n(.*)", text, re.S)
    fm = {}
    for line in m.group(1).splitlines():
        line = line.split("#", 1)[0].rstrip()
        if ":" in line:
            k, v = line.split(":", 1)
            fm[k.strip()] = v.strip()
    sections, cur = {}, None
    for line in m.group(2).splitlines():
        if line.startswith("## "):
            cur = line[3:].strip()
            sections[cur] = []
        elif cur is not None:
            sections[cur].append(line)
    sections = {k: "\n".join(v).strip() for k, v in sections.items()}
    fm["id"] = fm["id"].zfill(3)
    return fm, sections, path


REF = re.compile(r"(?i)\b(issues?)(\s+\d{3}\b(?:(?:\s*,\s*|\s+(?:and|or|und|bis)\s+)\d{3}\b)*)")
BARE = re.compile(r"\b(0\d\d)\b")


def rewrite_refs(text, idmap):
    """Rewrite 'Issue 004' style references (used in docs/*.md)."""
    def repl(m):
        nums = re.sub(r"\b(\d{3})\b", lambda n: f"#{idmap[n.group(1)]}" if n.group(1) in idmap else n.group(1), m.group(2))
        return m.group(1) + nums
    return REF.sub(repl, text)


def rewrite_bare(text, idmap):
    """Inside issue bodies every standalone legacy id (e.g. 'see 016/017') is an issue reference."""
    return BARE.sub(lambda n: f"#{idmap[n.group(1)]}" if n.group(1) in idmap else n.group(1), text)


def arch_line(fm):
    chapters = re.findall(r'"([^"]+)"', fm.get("architecture", ""))
    return "Kapitel " + ", ".join(chapters) + " (docs/Architecture.md)" if chapters else ""


def build_body(fm, sections, path, deps=None, idmap=None):
    done = fm.get("status") == "done"
    parts = [f"_Migriert aus `docs/issues/{path.name}` (legacy ID {fm['id']})._", ""]
    if arch_line(fm):
        parts.append(f"**Architecture:** {arch_line(fm)}")
    parts.append("**Depends on:** " + (", ".join(f"#{d}" for d in deps) if deps else "none"))
    parts.append("")
    for name in BODY_SECTIONS:
        content = sections.get(name, "").strip()
        if not content or (name in ("Notes / Findings", "Result") and not done):
            continue
        if name == "Acceptance criteria" and done:
            content = content.replace("- [ ]", "- [x]")
        if idmap:
            content = rewrite_bare(content, idmap)
        parts += [f"## {name}", content, ""]
    return "\n".join(parts).rstrip() + "\n"


def labels_for(fm, branches):
    labels = ["agent-task"]
    status = fm.get("status", "open")
    if status == "deferred":
        labels.append("deferred")
    elif status == "blocked":
        labels.append("blocked")
    elif status == "in-progress" or (status != "done" and fm["id"] in branches):
        labels.append("in-progress")
    return labels


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawTextHelpFormatter)
    ap.add_argument("--apply", action="store_true")
    ap.add_argument("--keep-files", action="store_true", help="do not remove docs/issues/")
    a = ap.parse_args()

    files = sorted(p for p in ISSUE_DIR.glob("[0-9][0-9][0-9]-*.md"))
    if not files:
        sys.exit("No issue files found in docs/issues/. Already migrated?")
    issues = [parse(p) for p in files]

    if not a.apply:
        print("DRY RUN. Plan (use --apply to execute):\n")
        for fm, sections, path in issues:
            deps = re.findall(r"\d+", fm.get("depends_on", ""))
            print(f"  {fm['id']} [{fm['milestone']}] {fm['title']}  status={fm['status']} "
                  f"labels={labels_for(fm, set())} depends_on={deps}")
        fm, sections, path = issues[2]
        print("\nExample body:\n" + "-" * 60)
        print(build_body(fm, sections, path, deps=[1, 2]))
        return

    try:
        repo = repo_name()
        existing = {i["title"]: i for i in api_list(f"repos/{repo}/issues?state=all&labels=agent-task&per_page=100")
                    if "pull_request" not in i}
        milestones = {m["title"].split(" ")[0]: m["number"] for m in api_list(f"repos/{repo}/milestones?state=all")}
        branches = {m.group(1) for b in api_list(f"repos/{repo}/branches?per_page=100")
                    if (m := re.match(r"issue/(\d{3})-", b["name"]))}
        missing = [k for k in MILESTONES if k not in milestones]
        if missing:
            sys.exit(f"Milestones missing: {missing}. Run scripts/setup-github-repo.py --apply first.")

        idmap, gh_issue = {}, {}
        print("Pass 1: creating issues")
        for fm, sections, path in issues:
            title = fm["title"]
            if title in existing:
                created = existing[title]
                print(f"  reuse #{created['number']} {title}")
            else:
                created = api(f"repos/{repo}/issues", "POST", {
                    "title": title,
                    "body": build_body(fm, sections, path),
                    "milestone": milestones[fm["milestone"]],
                    "labels": labels_for(fm, branches),
                })
                print(f"  create #{created['number']} {title}")
            idmap[fm["id"]] = created["number"]
            gh_issue[fm["id"]] = created

        print("Pass 2: bodies, dependencies, closing")
        warned = False
        for fm, sections, path in issues:
            n = idmap[fm["id"]]
            deps_legacy = [d.zfill(3) for d in re.findall(r"\d+", fm.get("depends_on", ""))]
            deps = [idmap[d] for d in deps_legacy]
            body = build_body(fm, sections, path, deps, idmap)
            api(f"repos/{repo}/issues/{n}", "PATCH", {"body": body})
            for d in deps_legacy:
                try:
                    api(f"repos/{repo}/issues/{n}/dependencies/blocked_by", "POST",
                        {"issue_id": gh_issue[d]["id"]})
                except GhError as e:
                    if "already" in str(e).lower() or "422" in str(e):
                        continue
                    if not warned:
                        print(f"  note: blocked-by relationships not available ({str(e).splitlines()[-1]}); "
                              "the 'Depends on' line in the body is used instead.")
                        warned = True
            if fm.get("status") == "done" and gh_issue[fm["id"]]["state"] != "closed":
                pr = DONE_PRS.get(fm["id"])
                note = f"Umgesetzt in #{pr}." if pr else "Bereits vor der Migration umgesetzt."
                api(f"repos/{repo}/issues/{n}/comments", "POST", {"body": note})
                api(f"repos/{repo}/issues/{n}", "PATCH", {"state": "closed", "state_reason": "completed"})
                print(f"  closed #{n}")

        (ROOT / ".migration-map.json").write_text(json.dumps(idmap, indent=2), encoding="utf-8")

        print("Rewriting references in docs/*.md")
        for doc in (ROOT / "docs").glob("*.md"):
            old = doc.read_text(encoding="utf-8")
            new = rewrite_refs(old, idmap)
            if new != old:
                doc.write_text(new, encoding="utf-8")
                print(f"  updated {doc.relative_to(ROOT)}")
        if not a.keep_files:
            subprocess.run(["git", "rm", "-r", "-q", "docs/issues"], cwd=ROOT, check=True)
            print("Removed docs/issues/ (git rm).")
        print("\nLegacy id -> GitHub issue:")
        for k, v in idmap.items():
            print(f"  {k} -> #{v}")
        print("\nNext: review `git status`, commit, push, open the pull request.")
    except GhError as e:
        sys.exit(f"error: {e}")


if __name__ == "__main__":
    main()
