#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""List GitHub issues an agent can pick up.

An issue is ready when it is open, labeled `agent-task` and `ready`, not labeled
`in-progress`/`blocked`/`deferred`, has no branch `issue/<number>-*` on origin, and all
issues named in its `**Depends on:**` line are closed.
"""
import json
import re
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from ghlib import GhError, gh  # noqa: E402

SKIP_LABELS = {"in-progress", "blocked", "deferred"}


def depends_on(body):
    m = re.search(r"^\*\*Depends on:\*\*(.*)$", body or "", re.M)
    return [int(n) for n in re.findall(r"#(\d+)", m.group(1))] if m else []


def claimed_branches():
    out = subprocess.run(
        ["git", "ls-remote", "--heads", "origin", "issue/*"], capture_output=True, text=True
    )
    return {int(n) for n in re.findall(r"refs/heads/issue/(\d+)-", out.stdout)}


def milestone_rank(issue):
    m = re.match(r"M(\d+)", (issue.get("milestone") or {}).get("title", ""))
    return int(m.group(1)) if m else 99


def main():
    try:
        issues = json.loads(
            gh("issue", "list", "--state", "all", "--limit", "1000",
               "--json", "number,title,state,labels,milestone,body")
        )
    except GhError as e:
        sys.exit(f"error: {e}")
    state = {i["number"]: i["state"] for i in issues}
    claimed = claimed_branches()
    ready = []
    for i in issues:
        labels = {l["name"] for l in i["labels"]}
        if i["state"] != "OPEN" or not {"agent-task", "ready"} <= labels or labels & SKIP_LABELS:
            continue
        if i["number"] in claimed:
            continue
        if all(state.get(d) == "CLOSED" for d in depends_on(i["body"])):
            ready.append(i)
    ready.sort(key=lambda i: (milestone_rank(i), i["number"]))
    if not ready:
        print("No issue is ready (all done, claimed, not yet defined, blocked, deferred or waiting for dependencies).")
        return
    print("Ready to pick up (milestone, issue, title):")
    for i in ready:
        ms = (i.get("milestone") or {}).get("title", "no milestone").split(" ")[0]
        print(f"  {ms:<3} #{i['number']:<4} {i['title']}")
    print("\nClaim: `git push origin <branch>` with the name issue/<number>-<slug> (see AGENTS.md).")


if __name__ == "__main__":
    main()
