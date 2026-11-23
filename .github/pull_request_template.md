## What changes?

One logical change per PR. Link the issue if one exists.

## Verification

- [ ] `python3 tools/scrub_check.py`, `python3 tools/comment_hygiene.py check --tokens` and `python3 tools/term_lint.py` pass
- [ ] Unit/E2E tests added or updated for behavior changes
- [ ] UI changes: checked on the worst-case data volume (long names, empty state,
      thousands of rows) and in both themes

## Notes for reviewers
