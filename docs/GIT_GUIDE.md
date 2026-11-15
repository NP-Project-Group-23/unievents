# UniEvents Git and pull request guide

This workflow reduces conflicts; it cannot guarantee none. Small changes, explicit ownership and early integration prevent most surprises. See [workload ownership](TEAM_WORKLOAD.md) before editing shared files. Commands below use PowerShell. Replace example branch names and paths with your task's values.

## 1. Branch policy and administrator setup

- `main`: reviewed demo/release state.
- `dev`: integrated team work.
- `feat/m1-login`, `feat/m3-seat-booking`, `fix/m4-reconnect`: short-lived task branches created from current `origin/dev`.
- Feature PRs target `dev`; approved releases go from `dev` to `main`.
- One implementer per task branch. Never force-push shared branches or commit directly to `dev`/`main`.

Administrator: in GitHub **Settings → Rules → Rulesets**, protect `main` and `dev` with PR review, at least one teammate approval, resolved conversations and blocked force pushes/deletions. Restrict the PR requirement to those integration branches, allowing normal feature-branch pushes. Check organization rules and legacy branch protection too: matching rules can combine. This repository previously rejected feature pushes with GH013, so verify the effective targets before the team starts. Do not broadly disable protection to fix this.

Enable squash merging for feature PRs and use a new task branch after every merge. For `dev` → `main`, prefer a merge commit to preserve shared branch ancestry. Only require CI checks once those workflows exist and their check names are known; these documents do not install CI or change remote settings.

Reference: [GitHub rulesets](https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-rulesets/available-rules-for-rulesets), [combined rules](https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-rulesets/about-rulesets).

## 2. First-time setup

```powershell
git clone https://github.com/NP-Project-Group-23/unievents.git
cd unievents
git config user.name "Your Name"
git config user.email "your-github-email@example.com"
git fetch origin
git switch dev
git pull --ff-only origin dev
```

If `dev` does not exist locally, use `git switch --track origin/dev`. Ensure the setup PR containing both apps has actually merged into `dev` before members start feature branches.

Install the Java version declared in `backend/pom.xml` (currently 21), Node compatible with the frontend, Git, and PostgreSQL. Use the Maven wrapper. Keep each developer's database separate; do not share a writable development database accidentally.

From the repo root:

```powershell
cd frontend
npm ci
cd ../backend
# Configure PostgreSQL and set DB_PASSWORD locally before starting.
.\mvnw.cmd spring-boot:run
```

The backend uses PostgreSQL; create the configured database and supply credentials before expecting startup/context tests to pass. Spring Boot does not automatically read a plain `.env` file without additional setup. Keep passwords in local environment variables, not tracked properties. Use an example configuration containing placeholders when documenting setup.

## 3. Start every task from the repository root

This works even when your terminal is inside `backend` or `frontend`:

```powershell
Set-Location (git rev-parse --show-toplevel)
git status
```

If there are unfinished changes, commit them on their correct task branch or deliberately stash them before switching. Do not carry another task's changes into a new branch by accident.

Create a fresh branch directly from the latest remote integration branch:

```powershell
git fetch origin
git switch -c feat/m3-seat-booking origin/dev
```

Do not reuse `feat/project-setup-pr` for ongoing feature work. Avoid large branches containing five members' unrelated tasks.

## 4. Daily edit, commit and push

Work within your issue's paths. Coordinate shared-file changes first. Before committing, run checks appropriate to the change (section 7), then:

```powershell
git status
git diff
# Example: replace this path with your actual changed files/folder.
git add backend/src/main/java/com/unievents/unievents_backend/booking
git diff --cached --stat
git diff --cached
git commit -m "feat: reserve event seats atomically"
git push -u origin HEAD
```

Stage the actual tests/docs as well. `git add` paths are relative to your current directory, which is why these examples start at the repository root. Review staged content before committing. Use later `git push` calls after upstream is set. A commit saves locally; a push uploads the current branch; a merged PR changes `dev`.

Never stage passwords, tokens, `.env`, `node_modules`, `.next`, `target`, IDE caches or generated build output. Commit source, Maven wrapper/config, and the npm lockfile. Empty folders do not appear on GitHub; add a useful document or an intentional `.gitkeep` when needed.

## 5. Synchronize with dev before opening or merging a PR

Run on your feature branch with a clean working tree:

```powershell
git branch --show-current
git fetch origin
git merge origin/dev
```

Resolve conflicts if needed using section 8. Run relevant checks again, then `git push`. This team workflow uses merge to synchronize published feature branches; it avoids rewriting history and needing force pushes. Do not run `git pull origin dev` without checking your current branch and understanding which branch will be changed.

Temporary unfinished work can be saved deliberately:

```powershell
git stash push -u -m "WIP before syncing dev"
git fetch origin
git merge origin/dev
git stash apply
```

`-u` includes untracked files but not ignored files. Applying the stash can itself conflict. Keep the stash until you verify all work is restored; inspect with `git stash list` and only then drop the intended entry. Do not use stash as your only long-term backup.

## 6. Open, review and merge a PR

1. Push the task branch. On GitHub choose **Pull requests → New pull request**.
2. Set **base = dev**, **compare = your task branch**. Check the Files changed tab for only intended work.
3. Fill in the repository PR template: issue, behavior, scope, test evidence, contract/database/config changes and dependencies. Use a draft while incomplete.
4. Request the reviewer listed in the workload guide and any shared-file owner affected. The author should not approve their own work.
5. Reviewer checks behavior, validation/authorization, concurrency/resource cleanup, compatibility, secrets and tests. A clean textual merge does not guarantee correct combined behavior.
6. Address feedback on the same branch and push; the existing PR updates automatically. Resolve discussions when actually addressed.
7. One designated integrator merges ready PRs one at a time. For overlapping changes, merge the prerequisite first; the next author synchronizes with `origin/dev`, retests and gets renewed review as needed.
8. Squash-merge into `dev` after required checks and approvals. Delete the remote task branch using GitHub after merge. Do not continue new work on it.

After merge, with a clean working tree:

```powershell
git fetch origin
git switch dev
git pull --ff-only origin dev
git switch -c feat/m3-booking-history origin/dev
```

If local `dev` has diverged, stop and inspect it; do not reset it blindly. Fresh task branches can still be created from `origin/dev` once your work is safely committed. Squash merges may cause `git branch -d` to refuse deletion of an old local branch; leaving it temporarily is harmless.

## 7. Required validation

From the repository root, frontend changes:

```powershell
cd frontend
npm ci
npm run lint
npm run build
cd ..
```

`npm ci` uses the committed lockfile and replaces the existing dependency install. Use `npm install <package>` only when intentionally changing dependencies, then commit the manifest and lockfile together. The starter build may need network access for Google Fonts.

Backend changes:

```powershell
cd backend
.\mvnw.cmd test
.\mvnw.cmd package
cd ..
```

The current Spring context may require a running configured PostgreSQL instance. Agree on an isolated test database/profile before adding integration tests. Never run destructive tests against a shared or production database. Report missing prerequisites as blocked tests, not passing tests. The team should later automate these commands with a PostgreSQL test service in CI.

For networking/booking changes, include the scenario-specific tests in the workload guide. For docs-only changes, check links, commands, ownership consistency and `git diff --check`; application rebuilds are unnecessary.

## 8. Resolve a merge conflict safely

Example: while on your feature branch, `git merge origin/dev` reports a conflict.

```powershell
git status
git diff --name-only --diff-filter=U
```

Open each conflicted file. In this merge, `HEAD`/Current is your feature branch and Incoming is `origin/dev`. Other operations can use different meanings; verify before choosing. For example:

```text
<<<<<<< HEAD
your feature change
=======
change from dev
>>>>>>> origin/dev
```

Discuss overlapping logic with the other author. Edit the final file to preserve the required behavior from both changes, removing all markers. Do not choose “Accept All Current” or “Accept All Incoming” merely to clear the conflict. Then stage the specific resolved files:

```powershell
# Replace with each real resolved path.
git add path/to/resolved-file
git diff --name-only --diff-filter=U
git diff --cached
git diff --cached --check
```

The unresolved-files command should print nothing. Run relevant checks, then finish:

```powershell
git commit -m "merge: integrate dev and resolve booking conflicts"
git push
```

To stop an in-progress merge, use `git merge --abort`. Start merges from a clean tree so abort can restore the pre-merge state reliably. Conflict resolutions also need review. [GitHub conflict guide](https://docs.github.com/en/pull-requests/how-tos/merge-and-close-pull-requests/resolving-a-merge-conflict-using-the-command-line).

### Files needing special handling

- `package-lock.json`: agree the final `package.json` first. Ask the dependency coordinator to reconcile the lockfile using npm from an agreed valid lockfile baseline; inspect dependency changes and run `npm ci`, lint and build. Do not hand-edit thousands of lockfile lines or blindly discard one side's dependency updates.
- `pom.xml`: retain each required dependency once; agree versions, then run Maven checks.
- Database migrations: allocate separate versions; rebase the migration plan logically onto the merged schema. Never silently overwrite a migration already applied by teammates.
- Global CSS/layout: let the shared UI coordinator reconcile styling; keep feature styling local.
- `application.properties`: retain environment placeholders and agreed defaults, never copy someone's actual password into the resolution.
- Formatting: follow `.editorconfig`; avoid formatting unrelated files in a feature PR. LF/CRLF warnings alone are not push errors. Propose any `.gitattributes` normalization as a separate coordinated PR.

## 9. Troubleshooting this repository's earlier problems

| Symptom | Meaning / action |
| --- | --- |
| `pathspec 'backend' did not match` | You may already be inside `backend`. Return to repo root with `Set-Location (git rev-parse --show-toplevel)` and inspect `git status`. |
| `Everything up-to-date` but files missing | Uncommitted/untracked files are not uploaded. Check `git status`, `git ls-files backend` and the GitHub branch selector. |
| Backend only contains HELP.md | Check the current branch's tracked files. Earlier backend code was on a different local branch; inspect `git log --all --oneline -- backend` and `git ls-tree -r <branch> -- backend` before restoring anything. |
| GH013: changes must use a PR on `dev` | Push a task branch and open a PR. Do not force-push. |
| GH013 on a feature branch | Ask admin to inspect effective repository/organization rules and narrow the PR rule to integration branches. Renaming branches repeatedly will not fix a rule targeting all branches. |
| Push rejected: fetch first | Fetch and inspect remote changes. On a task branch, merge its remote tracking branch if someone updated it, then merge `origin/dev` as needed; resolve and retest. Do not overwrite remote commits. |
| LF will become CRLF | Line-ending warning, not a rejected push. Coordinate formatting policy separately. |
| Source not visible on `main` | Select the pushed feature branch. It reaches `dev` after the feature PR and `main` after the release PR. |

If you accidentally committed on local `dev` and have not pushed, first preserve it with `git switch -c fix/preserve-local-work`. Inspect the history with the integrator before cleaning up `dev`. If a file was committed on a different branch, inspect it before selectively restoring; restoring a whole folder can remove files absent from the source branch. If a real credential was pushed, rotate it and coordinate repository cleanup; merely deleting it in a later commit does not remove it from history.

## 10. Quick team checklist

Before work: current `origin/dev`, unique task branch, one issue owner, agreed interfaces and file boundaries.

Before PR: intended files staged, no secrets/build output, latest `dev` merged, relevant tests run, template completed, reviewer selected.

Before merge: dependencies merged, conflicts jointly reviewed, approval and checks current, integrator ready to verify the combined app.
