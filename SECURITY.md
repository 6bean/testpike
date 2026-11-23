# Security Policy

testPike is a self-hosted test-automation platform: it executes user
scripts in browsers, stores credentials for target systems, and calls LLM
APIs. We take its security posture seriously.

## Reporting a vulnerability

**Please do not report security vulnerabilities through public GitHub
issues.**

Report them privately via GitHub's
[security advisory](https://docs.github.com/en/code-security/security-advisories)
flow against this repository, or by the contact route named in the README.

Include, when possible:

- affected version or commit (`git rev-parse HEAD`)
- deployment mode (single node, cluster slot, Docker Compose, bare metal)
- step-by-step reproduction or a proof-of-concept patch
- impact assessment (which trust boundary is crossed)

We aim to acknowledge within 5 business days and to ship a fix or a
mitigation advisory within 30 days for high-severity issues.

## Always private (never post publicly)

- real credentials, tokens, or API keys (yours or anyone's)
- logs or screenshots containing production data
- database dumps

## Supported versions

| Version | Supported          |
| ------- | ------------------ |
| latest  | :white_check_mark: |
| older   | best effort        |

## Notes for operators

- Rotate `SSO_JWT_SECRET`, `ADMIN_INIT_PASSWORD`, `CONFIG_AES_KEY` and the
  storage signing secret immediately after installation — the shipped
  defaults in `.env.example` are development-only values.
- AI write operations always land as drafts behind a one-click human
  confirmation gate; do not automate that confirmation in shared
  environments.
- The credential vault stores target-system secrets encrypted at rest with
  the configured AES key; protect your `.env`.
