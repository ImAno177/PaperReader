# Contributing

Status: current contributor workflow for the pre-1.0 `main` branch. Start with a focused issue before
large UI, schema, provider, reader, or dependency changes.

## Ground rules

- Preserve the hard dependency direction `:app -> :logic`. UI must use the public logic facade and
  must not access Room, OkHttp, built-in provider implementations, or reader cache internals.
- Keep `PaperWork`, `PaperManifestation`, provider records, and local artifacts distinct. Similar
  titles are never sufficient for automatic merge.
- Do not render remote HTML, compile untrusted TeX in-process, silently upload papers, or load plugin
  code into the host process.
- Do not add analytics, proprietary SDKs, credentials, private endpoints, or redistribution claims
  without an explicit product and license review.
- Keep production UI English-only until localization is intentionally reopened.

## Development workflow

1. Create a typed branch named `<type>/<kebab-case-summary>` and add the smallest deterministic test
   that proves the behavior.
2. Update exported Room schemas and migrations for persistence changes.
3. Update `docs/SPEC.md`, `docs/ARCHITECTURE.md`, or `docs/EXTENSIONS.md` when a documented decision changes.
4. Update `CHANGELOG.md` for user-visible, public API, schema, security, build, or CI changes. Keep
   unreleased work under `Unreleased` until a tag exists.
5. Run the Markdown and local host gates defined in [`docs/TESTING.md`](docs/TESTING.md).
6. Run the connected gate for Android runtime/UI changes and report the device/API used.

Provider unit tests must use recorded local fixtures. Live API calls belong in an explicit manual
verification, not the deterministic suite. Never weaken `LogicBoundaryTest`, add a lint baseline, or
use destructive Room migration as a shortcut.

Dependabot checks grouped Gradle and GitHub Actions updates at 09:00 ICT on the first and fifteenth of
each month. The schedule in [`.github/dependabot.yml`](.github/dependabot.yml) is authoritative.
Security-update groups stay separate from minor and patch updates; handle urgent fixes outside those
review windows.

Pull requests should explain the user-visible change, public facade/schema impact, tests run, and any
deliberately deferred behavior.
