# Security Policy

## Supported versions

PaperReader is currently pre-1.0. Security fixes are made on the latest `main` branch; no stable
release line is supported yet.

## Reporting a vulnerability

Please use **Security → Report a vulnerability** in this GitHub repository to open a private security
advisory. Do not disclose a suspected vulnerability in a public issue, discussion, or pull request.

Include affected version/commit, Android version, reproduction steps, expected impact, and a minimal
proof of concept when safe. Do not include copyrighted papers, personal library data, credentials, or
other people's information. Maintainers will acknowledge a complete report as soon as practical and
coordinate disclosure after a fix is available.

High-priority areas include provider/redirect validation, HTML sanitization and WebView isolation,
PDF/content-URI handling, backup parser bounds, plugin trust/signature checks, and app-private file
access.
