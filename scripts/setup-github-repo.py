#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""One-time repository setup for the agent workflow (run by the repository owner).

Configures squash-only merges, auto-merge, branch deletion after merge, labels,
milestones M0..M9, and branch protection that requires the CI checks and a pull request.
Dry run by default; pass --apply to change the repository. Safe to run repeatedly.
"""
import argparse
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from ghlib import LABELS, MILESTONES, GhError, api, api_list, default_branch, gh, repo_name  # noqa: E402

CI_CHECKS = ["Build & Test (ubuntu-latest)", "Build & Test (windows-latest)"]


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawTextHelpFormatter)
    ap.add_argument("--apply", action="store_true", help="really change the repository")
    ap.add_argument("--no-enforce-admins", action="store_true",
                    help="let repository admins bypass branch protection (default: admins are bound too)")
    a = ap.parse_args()
    try:
        repo = repo_name()
        branch = default_branch()
    except GhError as e:
        sys.exit(f"error: {e}")
    mode = "APPLY" if a.apply else "DRY RUN"
    print(f"[{mode}] repository {repo}, default branch {branch}")

    settings = {
        "allow_auto_merge": True,
        "delete_branch_on_merge": True,
        "allow_squash_merge": True,
        "allow_merge_commit": False,
        "allow_rebase_merge": False,
        "squash_merge_commit_title": "PR_TITLE",
        "squash_merge_commit_message": "PR_BODY",
    }
    print("settings:", settings)
    protection = {
        "required_status_checks": {"strict": False, "contexts": CI_CHECKS},
        "enforce_admins": not a.no_enforce_admins,
        "required_pull_request_reviews": {"required_approving_review_count": 0},
        "restrictions": None,
        "allow_force_pushes": False,
        "allow_deletions": False,
    }
    print(f"branch protection on {branch}:", protection)
    print("labels:", ", ".join(LABELS))
    print("milestones:", ", ".join(t for t, _ in MILESTONES.values()))
    if not a.apply:
        print("\nNothing changed. Re-run with --apply.")
        return

    api(f"repos/{repo}", "PATCH", settings)
    print("ok: repository settings")

    for name, (color, desc) in LABELS.items():
        gh("label", "create", name, "--color", color, "--description", desc, "--force")
    print("ok: labels")

    existing = {m["title"].split(" ")[0]: m for m in api_list(f"repos/{repo}/milestones?state=all")}
    for key, (title, desc) in MILESTONES.items():
        body = {"title": title, "description": desc}
        if key in existing:
            api(f"repos/{repo}/milestones/{existing[key]['number']}", "PATCH", body)
        else:
            api(f"repos/{repo}/milestones", "POST", body)
    print("ok: milestones")

    try:
        api(f"repos/{repo}/branches/{branch}/protection", "PUT", protection)
        print("ok: branch protection")
    except GhError as e:
        print(f"WARNING: branch protection could not be set:\n{e}\n"
              "Private repositories on a free plan do not support it. Without protection, "
              "auto-merge merges immediately instead of waiting for CI.", file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()
