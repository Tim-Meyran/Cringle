---
description: Independent review of the pull request of an issue, then merge or send back (usage: /review 114)
agent: reviewer
---
Load the skill `cringle-workflow`. Review the pull request that belongs to issue #$ARGUMENTS (`gh pr list --search "#$ARGUMENTS" --state open`).

1. Read the issue (Scope, Out of scope, Acceptance criteria) and the diff (`gh pr diff`). Check each acceptance criterion against a test or a command result; check that nothing outside Scope changed.
2. Look for: production checks removed or weakened, debug output, scratch files, `Thread.sleep`, new `usePlaintext`, secrets in logs or arguments, tests that cannot fail (`try/catch` without `fail`).
3. Check out the branch and run `./gradlew build --quiet --console=plain --no-daemon` on the final commit. A silent run is green.
4. Approve: `gh pr merge --squash --delete-branch` with `Closes #$ARGUMENTS` in the message, confirm the issue is closed, remove `needs-review` and `in-progress`.
5. Otherwise comment on the issue `Review round <k>: CHANGES REQUESTED` with numbered findings (blocker/major, `file:line`, what to change), set `changes-requested`, remove `needs-review`. Do not fix the code yourself.
