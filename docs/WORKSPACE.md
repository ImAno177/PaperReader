# Development workspace

Status: implemented. This workspace pins related repositories without combining their Android
modules, Git histories, or release permissions. Host-only builds do not require submodule checkout.

## Get the repositories

From an existing PaperReader checkout:

```powershell
git submodule update --init --recursive
git submodule status
```

| Checkout | Repository and purpose |
| --- | --- |
| `external/sources` | [Official sources](https://github.com/ImAno177/PaperReader-sources), the four provider APKs and signed store |
| `external/source-sample` | [Source sample](https://github.com/ImAno177/PaperReader-source-sample), a source SDK example |
| `external/theme-sample` | [Theme sample](https://github.com/ImAno177/PaperReader-theme-sample), a declarative theme SDK example |
| `external/site` | [Product site](https://github.com/ImAno177/paperreader-site), the public website and user guides |

Use each repository's own instructions and checks. Commit and push a related repository's changes
there first, then update its gitlink in PaperReader. Do not advance every submodule to an unreviewed
remote head or add provider modules to the host's `settings.gradle.kts`.

## Verify Android compatibility

Use the JDK and SDK versions in the [host testing guide](TESTING.md). This command runs JVM tests,
debug lint, and debug assembly in all three extension repositories against this checkout's SDK:

```powershell
.\tools\verify-extensions.ps1
```

It does not install APKs or use release signing keys. Default extension builds have no trusted host
certificate configured. For a runnable debug pair, follow the exact certificate setup in
[local host development](EXTENSIONS.md#local-host-development); the host and extension must both
verify the other package's actual certificate.

The site keeps its own package manager and deployment workflow; see its checked-out README for
local commands. Host Markdown lint excludes `external/` because each Git repository owns its docs.
