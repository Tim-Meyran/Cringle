---
description: Fix the findings of the last review round (usage: /rework 114)
agent: lead
---
Load the skill `cringle-workflow`. Issue #$ARGUMENTS has the label `changes-requested`.

Do not claim a new branch: check out the existing `issue/$ARGUMENTS-*` branch. The spec is the last issue comment that starts with `Review round`. Implement exactly its blocker and major findings, nothing else, in small steps through `coder`; compile after each. Run `spotlessApply` and `build`. Push to the same branch, comment `Review round <k> addressed: <one line per finding>`, then `needs-review` again and remove `changes-requested`. Final answer in at most five lines.
