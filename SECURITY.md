# Security Policy

## Supported versions

ChapterVault has not had a stable release yet. Security fixes are applied to the `master` branch only.

| Version | Supported |
|---------|-----------|
| `master` (unreleased) | Yes |
| Earlier commits | No |

## Reporting a vulnerability

**Please do not report security vulnerabilities through public GitHub issues.**

To report a vulnerability, use one of the following:

- **GitHub private advisory:** [Report a vulnerability](https://github.com/DevKoenv/ChapterVault/security/advisories/new)
- **Email:** security@koenv.dev - include "ChapterVault Security" in the subject line

Please include:

- A description of the vulnerability and its potential impact
- Steps to reproduce or a proof-of-concept
- The version or commit hash you tested against
- Any suggested mitigations, if you have them

You will receive an acknowledgement within 72 hours. We aim to release a fix within 14 days for critical issues.

## Scope

In-scope for reports:

- Authentication and session management flaws
- Authorisation bypass (e.g. a USER accessing ADMIN-only routes)
- SQL injection or path traversal in the API or storage layer
- Sensitive data exposure (credentials, session tokens in logs or responses)
- SSRF via connector HTTP requests

Out of scope:

- Vulnerabilities requiring physical access to the server
- Self-XSS in a future frontend
- Denial-of-service through normal API usage (rate limiting is a planned feature)
- Security of third-party dependencies - report these directly upstream

## Disclosure policy

We follow coordinated disclosure. We ask that you give us reasonable time to address the issue before any public disclosure.
