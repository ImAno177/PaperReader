# Contributing

Thanks for helping improve PaperReader. The project is pre-1.0, so start with a focused issue before
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

1. Create a small branch and add the smallest deterministic test that proves the behavior.
2. Update exported Room schemas and migrations for persistence changes.
3. Update `docs/SPEC.md` or `docs/ARCHITECTURE.md` when a documented decision changes.
4. Run the required gate:

   ```powershell
   .\gradlew.bat :logic:testDebugUnitTest :logic:lintDebug `
     :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
   ```

5. Run connected tests for Android runtime/UI changes and report the device/API used.

Provider unit tests must use recorded local fixtures. Live API calls belong in an explicit manual
verification, not the deterministic suite. Never weaken `LogicBoundaryTest`, add a lint baseline, or
use destructive Room migration as a shortcut.

Pull requests should explain the user-visible change, public facade/schema impact, tests run, and any
deliberately deferred behavior.
