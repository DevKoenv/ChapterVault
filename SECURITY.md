# Security Policy

## Supported Versions

| Version | Supported          |
|---------|--------------------|
| 0.1.x   | :white_check_mark: |
| 0.2.x   | :white_check_mark: |
| 0.3.x   | :white_check_mark: |

## Reporting a Vulnerability

We take security seriously. If you discover a security vulnerability, please report it responsibly.

### How to Report

**Do NOT create a public GitHub issue for security vulnerabilities.**

Instead, please send an email to: [security@koenv.dev](mailto:security@koenv.dev)

Or use GitHub's private vulnerability reporting feature:

1. Go to the Security tab of this repository
2. Click "Report a vulnerability"
3. Fill out the form with details

### What to Include

Please include the following information in your report:

- Type of vulnerability (e.g., XSS, SQL injection, authentication bypass)
- Full path of the affected source file(s)
- Step-by-step instructions to reproduce the issue
- Proof-of-concept or exploit code (if possible)
- Impact assessment
- Any suggested fixes

### What to Expect

- **Acknowledgment**: We will acknowledge receipt of your report within 48 hours
- **Assessment**: We will investigate and assess the vulnerability within 7 days
- **Resolution**: We will work on a fix and coordinate disclosure
- **Credit**: We will credit you in the release notes (unless you prefer anonymity)

### Scope

The following are in scope:

- The ChapterVault application itself
- Official Docker images
- API endpoints
- Authentication/authorization issues
- Data exposure vulnerabilities

The following are out of scope:

- Third-party dependencies (please report to them directly)
- Social engineering attacks
- Physical attacks
- Denial of service attacks

## Security Best Practices

When deploying ChapterVault:

1. **Use HTTPS** - Deploy behind a reverse proxy (nginx, Traefik) with TLS
2. **Secure database** - Use strong passwords, don't expose database ports
3. **Regular updates** - Keep ChapterVault and dependencies updated
4. **Limit access** - Use firewall rules to restrict access
5. **Monitor logs** - Watch for suspicious activity

## Known Security Considerations

- The application does not have built-in authentication - use a reverse proxy for access control
- OPDS endpoints are publicly accessible by default
- Downloaded files are stored in plain format on disk
