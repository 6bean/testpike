# Contributing to testPike

Thanks for your interest in improving testPike! This document is the short
version; the code of conduct applies to all interactions.

## Before you start

- For bugs and features, open an issue first so we can align on scope.
  Security issues should follow [SECURITY.md](SECURITY.md) instead of a
  public issue.
- For large changes (new services, schema migrations, public API shape),
  open a draft PR early and describe the design in words before code.

## Development environment

The fastest path is the guided installer: see `INSTALL.md` (English) or
`README.md` for the one-command Docker Compose stack. Requirements:
Java 21, Maven (wrapper included), Node 20+, Python 3.11+, PostgreSQL 15+,
Redis, and Docker for the executor stack.

## Ground rules the CI enforces

- **Language**: source comments, commit messages and identifiers are in
  English. Product UI text lives in `frontend/src/utils/locales/`.
- **Commits**: Conventional Commits, `type(scope): capability` form,
  e.g. `feat(judge): add historical run list door`.
- **No secrets, no internal identifiers**: personal paths, private
  hostnames and credentials must not appear in tracked files.
  `python3 tools/scrub_check.py` verifies this.
- **No duplication**: identical logic blocks beyond the stock ledger fail
  `tools/code_duplication_gate.py`; extract a shared component instead.
- **Comment hygiene**: `python3 tools/comment_hygiene.py check --tokens`
  must stay green.
- **Tests**: every behavior change ships with a test. Silent fallbacks
  that hide failures are treated as defects: fail loudly, degrade with an
  explicit on-screen message.

## Pull requests

1. Branch from `main`; keep one logical change per PR.
2. Run the public gates before requesting review:
   `python3 tools/scrub_check.py && python3 tools/comment_hygiene.py check --tokens && python3 tools/term_lint.py`
   (the same three the CI static-gates job runs).
3. For frontend changes, run `npm run type-check && npm run test:unit`
   inside `frontend/`, and exercise the real page where practical.
4. CI must be green; maintainers squash-merge.

## Reporting bugs

Include: version or commit, steps, expected vs actual, and browser/service
logs (redact tokens). Screenshots welcome.
