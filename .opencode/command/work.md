---
description: Implement one named issue (usage: /work 114)
agent: lead
---
Load the skill `cringle-workflow` and follow `AGENTS.md`. Work on issue #$ARGUMENTS only.

Check first: it is open, labeled `agent-task` and `ready`, has no label `blocked` or `deferred`, no branch `issue/$ARGUMENTS-*` exists, and every issue in `**Depends on:**` is closed. If one check fails, say which and stop.

Then: claim the branch, delegate reading to `scout` (report at most 300 words), let `coder` implement in steps of one class or file, compile after each step, run the module tests, then `spotlessApply` and the full `build`. Open the PR with `Closes #$ARGUMENTS`, label `needs-review`.

If the issue is unclear or impossible: label `blocked`, comment what exactly is unclear, stop. Final answer in at most five lines: PR link, test result, what was not run.
