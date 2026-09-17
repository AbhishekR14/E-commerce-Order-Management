---
name: run-phase
description: Execute one phase of docs/design/12-implementation-plan.md end-to-end (read specs, implement, test, commit, write private phase log). Use when the user says "run phase N" or "continue with the next phase".
---

# Run a phase

1. **Understand the phase.**
   - Open `docs/design/12-implementation-plan.md` and find the phase.
   - Read every doc listed under "Read first". Re-read `CLAUDE.md` §5–§6.
2. **Check the starting state.**
   - Run `git status` and `./mvnw -q clean verify`. The tree should be clean and green before you start.
   - If it is red, report this and fix it first (only with the user's OK if the fix is outside this phase).
3. **Plan.**
   - List the files you will create or change and the commits you intend, in 3–10 bullets.
   - If the spec is ambiguous, ask now.
4. **Implement in slices.** For each slice:
   - Add the migration (if any), then the entity, repository, service, DTOs and mapper, controller, and tests.
   - Run `./mvnw -q clean verify`.
   - Commit using skill `git-workflow`.
5. **Verify the acceptance criteria.**
   - Tick off every acceptance criterion listed for the phase.
   - Anything not met is either fixed or explicitly reported.
6. **Write up.**
   - Create `notes/phase-logs/phase-NN-<slug>.md` from the template (never commit it).
   - Append the prompt and outcome to `notes/prompts.md` (private — do NOT commit).
7. **Report back** to the user with:
   - a summary
   - commits made (`git log --oneline` for this phase)
   - the test count
   - open questions
   - the suggested next phase

Rules:
- Do not start the next phase without the user's go-ahead.
- Do not refactor unrelated code.
