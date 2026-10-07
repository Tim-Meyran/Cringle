@AGENTS.md

# Claude: how to work in this project

In this project you define and implement issues (see "Roles" in AGENTS.md). You work in the cloud or on a developer machine, one issue per branch and pull request, and the owner merges. The workflow, the conventions and the rules are in AGENTS.md; this file adds how you pick and prepare work.

## Picking work

1. If the owner names an issue, take it. Otherwise `python scripts/next-issue.py` lists what is `ready`.
2. If nothing is `ready`: `gh issue list --label agent-task --state open --json number,title,labels,milestone`. Take issues that are neither `ready` nor `deferred`; lowest milestone first, then lowest number, preferring issues whose dependencies are closed. Define it (below), mark it `ready`, and tell the owner before you implement it unless the owner told you to go on.
3. Read `docs/status.md` first: it says what is done, what is open and which decisions are not yet in `docs/decisions.md`.

## Defining an issue

1. **Read.** The issue, `docs/decisions.md`, `docs/status.md`, the architecture chapters named under **Architecture**, the pull requests of the issues it depends on (`gh pr view`), and the code that already exists in the affected modules.
2. **Sharpen the issue** until it meets the *Definition of Ready* in AGENTS.md: `gh issue edit <n> --body-file <file>`. Keep the structure of `.github/ISSUE_TEMPLATE/task.md`. Name files, classes and methods; give every acceptance criterion a named test or command. Do not edit `docs/Architecture.md` or `docs/decisions.md`; propose changes to them to the owner instead.
3. **Too big for one pull request?** Split it into issues of their own (one concern, about five production files), each with its own Scope and Acceptance criteria, and make the original one the tracking issue that depends on them.
4. **Open decisions.** For `[Offen]` points or anything neither in the issue, the decisions nor the architecture: propose the simplest reversible option in **Design notes**. If the owner has to decide, add the label `needs-owner-decision`, do not mark the issue `ready`, and ask the owner.
5. **Hand over.** `gh issue edit <n> --add-label ready` (remove `blocked` if present) and add a one-line comment on what was defined.

## Implementing

Follow AGENTS.md, steps 2 to 6. Some experience from this project:

- Start with a short plan of the files you change, then make the smallest change that meets the acceptance criteria. Compile a module (`./gradlew :<module>:compileKotlin --console=plain --no-daemon`) before you write its tests.
- When a change touches many call sites (a constructor, a test fixture), fix the main code first, then the tests module by module, and run that module's tests before you go on.
- Edit files with the edit tools rather than with shell regular expressions. A `perl`/`sed` substitution that contains `||` or `|` inside a `|`-delimited pattern silently inserts text at the start of the file.
- The tests of `engine`, `daemon`, `management-server` and `cli` start real processes. A failure that only shows in the full build is often a port, a trust entry or a stopped process: read the failing test report in `kotlin/<module>/build/test-results/test/`.

## Token economy

Keep token use low in every step; quality of the issues stays the priority.

- **Answers:** result first, in a few lines: issue numbers, decisions, open questions. No recap of steps, no restating the issue text, no tables or lists where one sentence does.
- **Reading:** read only what the task needs. Use `gh issue view <n> --json body,labels --jq ...`, `git show origin/master:<path>` with `sed -n`/`grep -n` for ranges, and `git ls-tree` for file lists.
- **Writing:** edit issues with targeted replacements and never re-type a whole body. Write each issue body once; state decisions as decisions. Link to documents instead of copying them.
- **Commands:** batch independent `gh` calls into one shell call; print only the fields needed (`--jq`), `head`/`cut` long output.
- **Questions to the owner:** ask only for decisions that change the issue, all at once (up to four per round), with a recommended option first.
