#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""List issues that can be picked up: status open and all dependencies done."""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ISSUES = ROOT / "docs" / "issues"


def frontmatter(path):
    text = path.read_text(encoding="utf-8")
    m = re.match(r"---\n(.*?)\n---\n", text, re.S)
    if not m:
        return None
    data = {}
    for line in m.group(1).splitlines():
        line = line.split("#", 1)[0].rstrip()
        if ":" in line:
            k, v = line.split(":", 1)
            data[k.strip()] = v.strip()
    return data


def ids(value):
    return re.findall(r"\d+", value or "")


def main():
    issues = {}
    for p in sorted(ISSUES.glob("[0-9][0-9][0-9]-*.md")):
        fm = frontmatter(p)
        if fm:
            issues[fm["id"].zfill(3)] = (p, fm)
    ready = []
    for n, (p, fm) in issues.items():
        if fm.get("status") != "open":
            continue
        deps = [d.zfill(3) for d in ids(fm.get("depends_on"))]
        missing = [d for d in deps if d not in issues]
        if missing:
            print(f"warning: {p.name} depends on unknown issue(s) {missing}", file=sys.stderr)
            continue
        if all(issues[d][1].get("status") == "done" for d in deps):
            ready.append((fm.get("milestone", "M?"), n, fm.get("title", ""), p.name))
    ready.sort(key=lambda r: (int(r[0][1:]) if r[0][1:].isdigit() else 99, r[1]))
    if not ready:
        print("No issue is ready (all done, claimed, blocked or waiting for dependencies).")
        return
    print("Ready to pick up (milestone, id, title, file):")
    for ms, n, title, name in ready:
        print(f"  {ms}  {n}  {title}  [docs/issues/{name}]")
    print("\nClaim: check `git ls-remote --heads origin 'issue/NNN-*'`, then push branch issue/NNN-slug.")


if __name__ == "__main__":
    main()
