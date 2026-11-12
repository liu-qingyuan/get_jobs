# OfferToday HK script adapter

Python standard library + existing `playwright-cli`; no model, copied login profile, Cookie export or browser Agent framework.

```sh
# Only when a connection is missing: explicitly attach ordinary Chrome via the official extension.
python3 scripts/offertoday/offertoday.py connect
# Reuse an existing connection; read loaded public jobs only.
python3 scripts/offertoday/offertoday.py discover
# Runner supplies the approved, private, RUNNING task bundle; do not invent a bundle or approval.
python3 scripts/offertoday/offertoday.py run /private/path/task.json
```

Connection metadata contains only `{session, workspace}` in ignored `.offertoday/connection.json` (0600). `OFFERTODAY_BROWSER_SESSION` / `OFFERTODAY_BROWSER_WORKDIR` override lookup. A metadata file may point to an already-attached workspace; no reattachment is needed for `discover/run`. Those commands never attach, log in or open a new browser. Missing/incompatible sessions stop. Shared Chrome is detached, not closed, when the overall operation ends.

## Execution contract

- HK single-job APPLICATION only, with a reviewed PDF/DOCX, exact initial text, contact/profile confirmation, and frozen hashes. Validation happens before browser access. Facts and résumé versions remain owned by the workbench; no profile auto-rewrite or translation.
- Discovery reads up to 40 currently loaded cards, using the public job-title/company links. It does not scroll, paginate, create tasks, or infer a missing full JD. Opaque job IDs retain case; queries do not distinguish jobs.
- Preflight navigates the existing tab to the approved job itself. Open dialogs and drafts are preserved rather than overwritten. It validates job/company or brand/recruiter, the native account, history baseline, attachment eligibility/capacity, and exact approved bytes. It never chooses a local file or sends anything.
- The driver uses the observed native client (`index.BKzheqN_.js`, `lang-73AtwcZ1.js`) and its actual root store. Unknown versions block; there is no guessed-export/default-store fallback. Job-card Chat/Apply buttons are not used because they can mutate state before opening a composer.
- Only an existing attachment with the exact approved filename, eligible metadata and original-blob SHA256/size may be reused. Unknown/default/converted-preview files are not substitutes. With no matching file, a free attachment slot is required; old files/defaults are not deleted or changed. New file selection auto-uploads and occurs only after the claim.
- `SUBMITTING` must be HTTP-acknowledged before text send or file selection. Text uses `.js-message-input-editor`; the native résumé picker is identified by its résumé SVG, not the shared `open-resume` aria label alone. The approved résumé is explicitly selected; the previous default is not trusted.
- Text and résumé are separate sends. A fresh **direct server-history** own-text record is reported as `CONTACTED`, and its callback must succeed before the attachment phase. A later fresh résumé card must bind the same peer and verified attachment ID; its message ID must differ from the text ID. Then `CV_DELIVERED` reports the approved file hash.
- DOM rows, local Loading/packet-send, editor clearing, upload/save success, or exit code zero are not receipts. A timeout, changed account/peer/page, failed callback, or missing history record retains `UNKNOWN`; no send is retried or restored. Unknown markup blocks instead of selecting another file or invoking AI.

The callback URL/token never enter browser JavaScript or website requests. Results contain only bounded task/receipt metadata, not message history, private profile values, file bytes or credentials. Platform-persisted résumé delivery is not a guarantee of employer reading, hiring outcome, or the native applied-history status.

## Verification boundary

The production driver now uses observed controls and native client bindings, not synthetic auth/conversation/SHA DOM attributes. Live **read-only** navigation and discovery were checked; no real message, upload or application was authorized or performed during development. Sending is verified with intercepted native-client/DOM fixtures, not claimed as live delivery acceptance. Unknown live UI variants remain blocked.

```sh
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s scripts/offertoday -p 'test_offertoday.py' -v
OFFERTODAY_FIXTURE_BROWSER=1 PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s scripts/offertoday -p 'test_browser_fixture.py' -v
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s scripts/workbench -p 'test_report.py' -v
```

The browser tests launch only random isolated fixture sessions and intercept **all** routes before fake OfferToday navigation. Fake server history is separate from local loading state. They never attach to `offertoday-current`, use user profiles, download real résumés or reach the real site. Workflow/safety details and eight design diagrams: `docs/offertoday-v1.md`.
