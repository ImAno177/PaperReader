# Contributing

Status: current contributor workflow for the pre-1.0 `main` branch. Start with a focused issue before
large UI, schema, provider, reader, or dependency changes.

## Ground rules

- Module boundaries and dependency direction: [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md#module-ownership).
- Domain identity rules (`PaperWork`, `PaperManifestation`, provider records, and exact aliases):
  [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md#domain-and-persistence).
- Safety, dependency, and test-integrity prohibitions: [`AGENTS.md`](AGENTS.md#development-and-verification).
- Keep production UI English-only until localization is intentionally reopened.

## Development workflow

1. Create a typed branch named `<type>/<kebab-case-summary>`, optionally with an issue number such as
   `fix/425-rss-deleting-bug`. Use `feat`, `fix`, `refactor`, `chore`, `docs`, `test`, `build`, `ci`,
   `release`, or `dependency`. Do not add a tool, agent, or owner prefix. Avoid vague `dev`, `patch`, or bare-name branches. Keep one independently
   reviewable concern per branch and align the Conventional Commit and pull-request types.
   Extend the nearest contract test for behavior changes; use the local smoke flow for reversible UI
   polish.
2. Update exported Room schemas and migrations for persistence changes.
3. Update `docs/SPEC.md`, `docs/ARCHITECTURE.md`, or `docs/EXTENSIONS.md` when a documented decision changes.
4. Update `CHANGELOG.md` for user-visible, public API, schema, security, build, or CI changes. Keep
   unreleased work under `Unreleased` until a tag exists, and group its entries under Added, Changed,
   Fixed, Removed, Security, or Build. Use dated semantic-version headings only for released tags.
5. Run the Markdown and local host gates defined in [`docs/TESTING.md`](docs/TESTING.md). A pull
   request merges after these gates pass on its head commit and a code review finds no open problems.
   No GitHub status check is required. CI runs on demand with
   `gh workflow run android-ci.yml --ref <branch>`; releases run the Android gate and CodeQL before
   they publish.
6. Run the connected gate for Android runtime/UI changes and report the device/API used.

Dependency and build-tool versions belong in [`gradle/libs.versions.toml`](gradle/libs.versions.toml).
Keep Compose libraries governed by its BOM. Editor whitespace and line endings follow
[`.editorconfig`](.editorconfig) and [`.gitattributes`](.gitattributes).

Provider unit tests must use recorded local fixtures. Live API calls belong in an explicit manual
verification, not the deterministic suite. Prohibited shortcuts, such as weakening `LogicBoundaryTest`
or adding a lint baseline, are listed in [`AGENTS.md`](AGENTS.md#development-and-verification).

Dependabot checks grouped Gradle and GitHub Actions updates at 09:00 ICT on the first and fifteenth of
each month. The schedule in [`.github/dependabot.yml`](.github/dependabot.yml) is authoritative.
Security-update groups stay separate from minor and patch updates; handle urgent fixes outside those
review windows.

Pull requests should explain the user-visible change, public facade/schema impact, tests run, and any
deliberately deferred behavior.
