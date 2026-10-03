---
description: Pick the next ready issue and work it through to a pull request
agent: lead
---
Load the skill `cringle-workflow` and follow `AGENTS.md`.

1. Run `python scripts/next-issue.py`. Take the first line, unless `$ARGUMENTS` names an issue number.
2. If nothing is ready, say so in one sentence and stop. Do not define or relabel issues.
3. Run the loop of the skill for that issue: claim, scout, coder in small steps, compile after every step, `spotlessApply`, `build`, PR, `needs-review`.
4. Final answer: issue number, PR link, the commands you ran with their result (green or the failing lines), and what you did not run. Two to five lines.
