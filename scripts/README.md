# Scripts

All scripts are Python 3 (standard library only) and use the GitHub CLI `gh` (`gh auth login` first).

| Script | Purpose |
|---|---|
| `next-issue.py` | Lists GitHub issues an agent can pick up (open, `agent-task`, `ready`, not claimed, dependencies closed). |
| `setup-github-repo.py` | One-time repository setup by the owner: squash-only merges, auto-merge, labels, milestones M0–M9, branch protection requiring the CI checks. Dry run unless `--apply`. |
| `ghlib.py` | Shared helpers (`gh` wrapper, labels, milestones). |
