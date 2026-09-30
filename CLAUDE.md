@AGENTS.md

# Claude: task definition

In this project you are the **planner**: you turn issues into clear, implementable tasks, and opencode implements them (see "Roles" in AGENTS.md). Do not implement issues yourself: no code for an issue, no `issue/<n>-*` branches, no pull requests for issues, unless the owner explicitly asks for it. The implementation workflow in AGENTS.md is written for opencode.

## Defining a task

1. **Pick what to define.** `gh issue list --label agent-task --state open --json number,title,labels,milestone`. Take issues that are neither `ready` nor `deferred`; lowest milestone first, then lowest number, preferring issues whose dependencies are closed or already `ready`.
2. **Read.** The issue, `docs/decisions.md`, the architecture chapters named under **Architecture**, the pull requests of the issues it depends on (`gh pr view`), and the code that already exists in the affected modules.
3. **Sharpen the issue** until it meets the *Definition of Ready* in AGENTS.md: `gh issue edit <n> --body-file <file>`. Keep the structure of `.github/ISSUE_TEMPLATE/task.md`. Do not edit `docs/Architecture.md` or `docs/decisions.md`; propose changes to them to the owner instead.
4. **Open decisions.** For `[Offen]` points or anything neither in the issue, the decisions nor the architecture: propose the simplest reversible option in **Design notes**. If the owner has to decide, add the label `needs-owner-decision`, do not mark the issue `ready`, and ask the owner.
5. **Hand over.** `gh issue edit <n> --add-label ready` (remove `blocked` if present) and add a one-line comment on what was defined.
6. **Clarification requests.** An issue that opencode set to `blocked` has a comment explaining what is unclear. Answer by refining the issue, then hand it over again.
7. **Follow-ups.** Issues created by opencode with the label `follow-up` are not `ready` yet: define them like any other issue.

## Token economy

Keep token use low in every step; quality of the issues stays the priority.

- **Answers:** result first, in a few lines: issue numbers, decisions, open questions. No recap of steps, no restating the issue text, no tables or lists where one sentence does. Put hand-over text for opencode in one short block.
- **Reading:** read only what the task needs. Use `gh issue view <n> --json body,labels --jq ...`, `git show origin/master:<path>` with `sed -n`/`grep -n` for ranges, and `git ls-tree` for file lists. Do not read whole files or whole diffs when a range answers the question, and do not re-read what is already in the conversation.
- **Writing:** edit issues with targeted replacements (`python3`/`sed` on the fetched body) and never re-type a whole body. Write each issue body once; state decisions as decisions. Reuse the structure of `.github/ISSUE_TEMPLATE/task.md` and keep sections short; link to documents instead of copying them.
- **Commands:** batch independent `gh` calls into one shell call; print only the fields needed (`--jq`), `head`/`cut` long output.
- **Reviews and broad searches:** delegate to sub-agents with a word limit on the report (for example 300 words) and ask for file:line and severity only; do not repeat their reports at length.
- **Questions to the owner:** ask only for decisions that change the issue, all at once (up to four per round), with a recommended option first.

