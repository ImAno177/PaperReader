# Security policy

Status: current reporting policy for the latest `main` branch.

## Supported versions

PaperReader is currently pre-1.0. Security fixes are made on the latest `main` branch; no stable
release line is supported yet.

## Reporting a vulnerability

Please use **Security > Report a vulnerability** in this GitHub repository to open a private security
advisory. Do not disclose a suspected vulnerability in a public issue, discussion, or pull request.

Include affected version/commit, Android version, reproduction steps, expected impact, and a minimal
proof of concept when safe. Do not include copyrighted papers, personal library data, credentials, or
other people's information. Maintainers will acknowledge a complete report as soon as practical and
coordinate disclosure after a fix is available.

High-priority areas include provider/redirect validation, HTML sanitization and WebView isolation,
PDF/content-URI handling, backup parser bounds, plugin trust/signature checks, and app-private file
access.

## Current hardening baseline

The Android host disables cleartext traffic and application backup, uses a `singleInstance` launcher with
an empty task affinity, and never enables WebView remote debugging. The Google search WebView runs in
an isolated process without file/content access, JavaScript bridges, mixed content, or third-party
cookies. It only follows Google resources and hands canonical HTTPS arXiv URLs to the native import
pipeline.

PaperReader does not claim arXiv HTTPS URLs as Android App Links. The project cannot publish
`arxiv.org/.well-known/assetlinks.json`, so accepting those links as verified app links would create a
spoofing surface. Users can still use the in-app Google search or Android's share/import actions.

## MobSF triage

MobSF runs on every default-branch push and pull request. Findings are reviewed against the host's
local-first threat model rather than treated as automatic defects:

- database migration SQL is fixed, compile-time schema DDL with no user-controlled input;
- `VISIBLE`, `INVISIBLE`, and `GONE` only switch loading, search, and reader controls and never hide
  secrets;
- certificate pinning, certificate-transparency checks, SafetyNet, root detection, and screenshot
  blocking are not enabled because the app has no backend credentials or privileged account data,
  supports rotating public provider endpoints and community extensions, and users must be able to
  capture paper content;
- test-only manifests are scanned separately and explicitly disable backup.

Those documented, non-applicable recommendations may be marked `false positive` or `won't fix` in
GitHub Code Scanning with the reason recorded in the alert comment. New high or critical findings
outside this list block a release.
