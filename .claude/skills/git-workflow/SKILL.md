---
name: git-workflow
description: Safe commit procedure for this repo — conventional commits, small commits, and NEVER committing the private notes/ folder or secrets. Use before every commit.
---

# Commit procedure

1. Run `./mvnw -q clean verify`. Do not commit red builds (except a WIP test commit explicitly requested by the user).
2. Run `git status --porcelain`.
   - **If any path starts with `notes/`, STOP.** Unstage it (`git restore --staged notes/`) and check that `.git/info/exclude` contains `notes/`.
   - If `.env`, `*.pem` or anything containing a real connection string appears, STOP and warn the user.
3. Stage explicit paths (`git add src/ docs/ pom.xml ...`). Never use `git add -f`.
4. Commit with Conventional Commits.
   - Types: `feat`, `fix`, `test`, `refactor`, `docs`, `chore`, `build`.
   - Scopes: `bootstrap`, `security`, `catalog`, `warehouse`, `inventory`, `cart`, `pricing`, `checkout`, `order`, `events`, `fulfillment`, `returns`, `seed`, `ai`, `readme`.
   - Subject is imperative, ≤ 72 chars. Add a body when explaining *why*.
5. Run `git log --oneline -1` to confirm.

Never use `--amend` on pushed commits, force-push, rebase shared history, or `git add -A` without checking step 2 first.
