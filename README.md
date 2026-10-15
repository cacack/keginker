# repo-template

Internal template repository seeding new repos with baseline config:

- `.github/dependabot.yml` — weekly GitHub Actions updates with `ci(deps)` commit prefix
- `.editorconfig` — universal whitespace defaults
- `.gitignore` — OS/editor noise (add language-specific entries per repo)
- `LICENSE` — MIT (change per repo if needed)

Used by the GitHub Terraform module at
[`home/git-repositories`](https://gitlab.com/home109/git-repositories) when
new repos opt in via `from_template = true`. This file is replaced when a
new repo is created from this template.
