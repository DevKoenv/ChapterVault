# Security Policy

## Supported versions

Only the latest `1.x` release receives security fixes. Upgrading within `1.x` is supported and automatic (see the
[operations guide](docs/operations.md#upgrading)); there is no back-porting to older minor versions.

| Version | Supported |
|---------|-----------|
| Latest `1.x` release | Yes |
| Older `1.x` releases | No, please upgrade |
| Pre-release builds, `-rc` tags, `master` between releases | No |

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

## What the server defends against

The [security and trust model](docs/security.md) describes what ChapterVault protects, what is public on purpose
(`/health`, `/swagger`, series covers) and what is the operator's job (TLS, a reverse proxy, who can reach the port).

## Scope

In-scope for reports:

- Authentication and session management flaws
- Authorisation bypass (e.g. a USER or GUEST performing an action its permissions do not allow)
- Brute-force protection that can be bypassed
- SQL injection or path traversal in the API or storage layer
- Sensitive data exposure (credentials, session tokens in logs or responses)
- SSRF via connector or notification HTTP requests
- Deleting or overwriting files outside the data directory

Out of scope:

- Vulnerabilities requiring physical access to the server
- Anything that needs an attacker to already control an installed extension. Extensions run with the full privileges
  of the server by design (see the trust model); malicious extension code is not a ChapterVault vulnerability
- Self-XSS in a future frontend
- Traffic volume denial of service (put a reverse proxy in front); bugs where one request can exhaust the server's
  memory, disk or CPU are in scope
- Exposure caused by running the server on plain HTTP across an untrusted network
- Security of third-party dependencies - report these directly upstream

## Disclosure policy

We follow coordinated disclosure. We ask that you give us reasonable time to address the issue before any public disclosure.
