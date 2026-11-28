#!/usr/bin/env python3
"""JobsDB-only CLI. No Spring, shared browser, service, or account secrets in logs."""
import argparse
from contextlib import contextmanager
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import signal
import sqlite3
import stat
import subprocess
import sys
import time
from urllib.parse import urljoin, urlencode, urlsplit
from urllib.error import HTTPError

ROOT = Path(__file__).resolve().parents[2]
DATA = ROOT / '.jobsdb'
SITE = 'https://hk.jobsdb.com'
CARDS = "a[data-automation='jobTitle'], a[data-automation='job-title']"

class Store:
    def __init__(self, path):
        self.db = sqlite3.connect(path, timeout=5, isolation_level=None)
        self.db.execute('CREATE TABLE IF NOT EXISTS applications (job_id TEXT PRIMARY KEY, status TEXT NOT NULL, updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP)')
    def __enter__(self): return self
    def __exit__(self, *_): self.db.close()
    def contains(self, id):
        return self.db.execute('SELECT 1 FROM applications WHERE job_id=?', (id,)).fetchone() is not None
    def attempt(self, job):
        return prior_attempt(self.db, job)

    def claim(self, id, attempt_id=None, source='cli'):
        self.db.execute('BEGIN IMMEDIATE')
        with self.db:
            inserted = self.db.execute("INSERT OR IGNORE INTO applications(job_id,status) VALUES(?,'UNKNOWN')", (id,)).rowcount
            if inserted and attempt_id is not None:
                self.db.execute("""CREATE TABLE IF NOT EXISTS claim_provenance (
                    job_id TEXT PRIMARY KEY REFERENCES applications(job_id), attempt_id TEXT NOT NULL,
                    claimed_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP, source TEXT NOT NULL)""")
                self.db.execute('INSERT INTO claim_provenance(job_id,attempt_id,source) VALUES(?,?,?)',
                                (id, attempt_id, source))
            return inserted == 1
    def finish(self, id, state):
        if state not in ('SUBMITTED', 'UNKNOWN'):
            raise ValueError('Only SUBMITTED or UNKNOWN may finish a claim')
        if self.db.execute('UPDATE applications SET status=?,updated_at=CURRENT_TIMESTAMP WHERE job_id=?', (state,id)).rowcount != 1:
            raise ValueError('Missing prior claim')
    def history(self):
        return ['\t'.join(row) for row in self.db.execute('SELECT job_id,status,updated_at FROM applications ORDER BY updated_at DESC')]

def prior_attempt(db, job):
    row = db.execute('SELECT status,updated_at FROM applications WHERE job_id=?', (job,)).fetchone()
    if row is None:
        return None
    prior = dict(jobId=job, status=row[0], updatedAt=row[1])
    if db.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name='claim_provenance'").fetchone():
        claim = db.execute('SELECT attempt_id,claimed_at,source FROM claim_provenance WHERE job_id=?', (job,)).fetchone()
        if claim:
            prior.update(zip(('attemptId', 'claimedAt', 'source'), claim))
    return prior


def history_json(data, job=None):
    """A bounded read-only view for the workbench, including while the profile is locked."""
    ledger = Path(data) / 'applications.db'
    rows = []
    try:
        ledger.stat()
    except FileNotFoundError:
        return {'schemaVersion': 1, 'attempts': rows}
    with sqlite3.connect(ledger.resolve().as_uri() + '?mode=ro', uri=True) as db:
        db.execute('BEGIN')
        ids = db.execute('SELECT job_id FROM applications ORDER BY updated_at DESC LIMIT 100') if job is None else [(job,)]
        for (job_id_,) in ids:
            prior = prior_attempt(db, job_id_)
            if prior is None:
                continue
            row = dict(id=job_id_, status=prior['status'], updated_at=prior['updatedAt'],
                       provenance='ORIGINAL_LEDGER', provenanceNote='原账本未记录执行入口或匹配结论；不补造 task 或 claim')
            if 'attemptId' in prior:
                row.update(attemptId=prior['attemptId'], claimedAt=prior['claimedAt'],
                           provenance=prior['source'], provenanceNote='原生提交占位')
            elif (job_id_, prior['status'], prior['updatedAt']) == ('96118137', 'SUBMITTED', '2026-11-26 11:49:02'):
                # Only this exact historical receipt has documented provenance (ADR 0003).
                row.update(provenance='DIRECT_FROZEN_CLI', provenanceNote='SUBMITTED in original ledger — direct frozen CLI run; workbench bridge not exercised。材料 MATERIAL_EXCEPTION 不是匹配 verdict；不补造 scope、package、预算或 claim。')
            rows.append(row)
    return {'schemaVersion': 1, 'attempts': rows}


def on_site(url):
    u = urlsplit(url)
    return u.scheme == 'https' and u.hostname == 'hk.jobsdb.com' and not u.username and not u.password and u.port in (None, 443)

def job_id(url):
    if not on_site(url) or not re.fullmatch(r'/job/[0-9]+/?', urlsplit(url).path):
        raise ValueError('Expected https://hk.jobsdb.com/job/<numeric-id>')
    return urlsplit(url).path.split('/')[2]

def first_visible(locator):
    return next((item for item in locator.all() if item.is_visible()), None)

class VerificationRequired(RuntimeError):
    """A recognizable platform challenge, not merely a timeout or missing job cards."""
    def __init__(self, message, target_url=None, remaining_seconds=None):
        super().__init__(message)
        self.target_url = target_url
        self.remaining_seconds = remaining_seconds


_cancellation_requested = False


def request_cancellation(*_):
    """Keep cancellation observable if the browser driver swallows the signal exception."""
    global _cancellation_requested
    _cancellation_requested = True
    raise KeyboardInterrupt()


def cancellation_requested():
    return _cancellation_requested


_RESUME_WORKER = 'GET_JOBS_JOBSDB_RESUME_WORKER'


def supervise_resume(task_file, data_dir, *, grace_seconds=2):
    """Keep the CLI outside Playwright so SIGTERM can reap the entire browser worker."""
    environment = os.environ.copy()
    environment[_RESUME_WORKER] = '1'
    command = [sys.executable, str(Path(__file__).resolve()), 'resume-read', str(task_file),
               '--data-dir', str(data_dir)]
    worker = subprocess.Popen(command, env=environment, start_new_session=True)
    stopping_at = None

    def stop(*_):
        nonlocal stopping_at
        if stopping_at is None:
            stopping_at = time.monotonic()
            try:
                os.killpg(worker.pid, signal.SIGTERM)
            except ProcessLookupError:
                pass

    previous = signal.signal(signal.SIGTERM, stop)
    try:
        while True:
            try:
                status = worker.wait(timeout=0.1)
                if stopping_at is not None:
                    print('CANCELLED', file=sys.stderr)
                    return 130
                return status
            except subprocess.TimeoutExpired:
                if stopping_at is None or time.monotonic() - stopping_at < grace_seconds:
                    continue
                try:
                    os.killpg(worker.pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass
                worker.wait()
                print('CANCELLED', file=sys.stderr)
                return 130
    except KeyboardInterrupt:
        stop()
        try:
            worker.wait(timeout=grace_seconds)
        except subprocess.TimeoutExpired:
            try:
                os.killpg(worker.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
            worker.wait()
        print('CANCELLED', file=sys.stderr)
        return 130
    finally:
        signal.signal(signal.SIGTERM, previous)


def challenge(page):
    title = page.title().lower()
    if any(s in title for s in ('just a moment', '请稍候', '請稍候', 'security verification',
                                'access restricted', 'access denied', 'attention required',
                                'unusual traffic', 'you have been blocked')):
        return True
    return first_visible(page.locator("iframe[src*='challenges.cloudflare.com'], iframe[src*='recaptcha'], iframe[src*='hcaptcha']")) is not None or first_visible(page.get_by_text(re.compile(r'^(Verify you are human|Performing security verification|Security check)$', re.I))) is not None

@contextmanager
def deadline(seconds=90):
    def expired(*_): raise TimeoutError('CF/navigation timed out; command not retried')
    previous = signal.signal(signal.SIGALRM, expired)
    remaining = signal.alarm(0)
    started = time.monotonic()
    signal.alarm(seconds)
    try: yield
    finally:
        signal.alarm(0)
        signal.signal(signal.SIGALRM, previous)
        if remaining:
            signal.alarm(max(1, int(remaining - (time.monotonic() - started))))

def require_resume_profile(profile):
    """A visible handoff may retain a real worker directory, but never follows linked or unknown profile state."""
    try:
        if not stat.S_ISDIR(profile.lstat().st_mode):
            raise OSError('Not an independent profile directory')
        with os.scandir(profile) as children:
            for child in children:
                mode = child.stat(follow_symlinks=False).st_mode
                if stat.S_ISLNK(mode):
                    raise OSError('Linked profile entry cannot be verified without traversal')
                if not stat.S_ISDIR(mode):
                    continue
                worker = Path(child.path) / 'Service Worker'
                try:
                    worker_mode = worker.lstat().st_mode
                except FileNotFoundError:
                    continue
                if stat.S_ISLNK(worker_mode) or not stat.S_ISDIR(worker_mode):
                    raise OSError('Linked or invalid Service Worker state cannot be opened safely')
    except OSError as error:
        raise RuntimeError('JobsDB independent profile metadata unavailable; read-only access refused; original session untouched') from error


def require_read_profile(profile):
    """Metadata only: unknown/unreadable/linked state is not worker absence."""
    try:
        if not stat.S_ISDIR(profile.lstat().st_mode):
            raise OSError('Not an independent profile directory')
        with os.scandir(profile) as children:
            for child in children:
                mode = child.stat(follow_symlinks=False).st_mode
                if stat.S_ISLNK(mode):
                    raise OSError('Linked profile entry cannot be verified without traversal')
                if not stat.S_ISDIR(mode):
                    continue
                try:
                    (Path(child.path) / 'Service Worker').lstat()
                except FileNotFoundError:
                    continue
                raise RuntimeError('JobsDB existing Service Worker state prevents verified read-only access; original session untouched')
    except OSError as error:
        raise RuntimeError('JobsDB independent profile metadata unavailable; read-only access refused; original session untouched') from error


class Browser:
    """Owns one pinned Scrapling session. Never attaches to another browser."""
    def __init__(self, data, *, read_only=False, allow_existing_worker=False, screenshots=True):
        self.data, self.read_only = data, read_only
        self.allow_existing_worker, self.screenshots = allow_existing_worker, screenshots
        self.observed_account = None
    def _visible_account(self, page):
        for selector in ('li.nav-figure', '.nav-figure', '[class*="nav-user"]', '[class*="user-name"]'):
            try:
                loc = page.locator(selector).first
                if loc.count() and loc.is_visible():
                    text = loc.inner_text().strip().split('\n')[0].strip()
                    if text: return text
            except Exception:
                pass
        return ''
    def __enter__(self):
        # Ordinary strict reads still refuse persisted workers. A resume may use the same profile only
        # with browser-level blocking plus per-page CDP bypass installed before navigation.
        if self.read_only:
            (require_resume_profile if self.allow_existing_worker else require_read_profile)(self.data / 'scrapling-profile')
        from scrapling.fetchers import StealthySession
        options = {'no_viewport': True, 'viewport': None, 'screen': None,
                   'device_scale_factor': None, 'is_mobile': None}
        flags = []
        if self.read_only:
            options['service_workers'] = 'block'
            flags.append('--disable-features=ServiceWorker')
        self.session = StealthySession(headless=False, real_chrome=True, google_search=False,
            user_data_dir=str(self.data / 'scrapling-profile'), timeout=30000, retries=1,
            additional_args=options, extra_flags=flags)
        self.session.__enter__()
        return self
    def __exit__(self, *args):
        self.session.__exit__(*args)
    def solve(self, page):
        if challenge(page):
            # Pinned private seam: preserves the current form instead of re-fetching it.
            with deadline(): self.session._cloudflare_solver(page)
        if challenge(page): raise RuntimeError('CF challenge remains; no automatic retry')
    def observe_role_requirements(self, page, task):
        """Navigate read-only to the role-requirements page; never claim or upload."""
        job = job_id(task['url'])
        self.observed_account = self.observed_account or self._visible_account(page)
        target = 'https://hk.jobsdb.com/job/' + job + '/apply/role-requirements'
        try:
            page.goto(target, wait_until='domcontentloaded', timeout=30000)
            self.solve(page)
            page.locator('[name^="questionnaire."]').first.wait_for(timeout=15000)
        except Exception as exc:
            raise ValueError('CAPABILITY_UNVERIFIED: role-requirements not readable without entering the application') from exc
        if not on_site(page.url) or not re.fullmatch(r'/job/' + job + r'/apply/role-requirements/?', urlsplit(page.url).path):
            raise ValueError('CAPABILITY_UNVERIFIED: role-requirements redirected away from the exact job')
        return self.read_role_requirements(page, job)

    def read_role_requirements(self, page, job):
        """Read the complete questionnaire schema from an already-reached same-job page."""
        if not on_site(page.url) or not re.fullmatch(r'/job/' + job + r'/apply/.*', urlsplit(page.url).path):
            raise ValueError('CAPABILITY_UNVERIFIED: page is not the exact job application')
        if page.locator('input:not([type=hidden]), select, textarea').evaluate_all(
                "(es)=>es.some(e=>e.getClientRects().length && !e.disabled && !e.name.startsWith('questionnaire.'))"):
            raise ValueError('Unidentified questionnaire control')
        names = page.locator('[name^="questionnaire."]').evaluate_all('(es)=>[...new Set(es.map(e=>e.name))]')
        questions = []
        for name in names:
            group = page.locator('[name=' + json.dumps(name) + ']')
            first = group.first
            tag = first.evaluate('(e)=>e.tagName.toLowerCase()')
            input_type = (first.get_attribute('type') or '').lower()
            if tag == 'select' and group.count() == 1:
                kind = 'select'
                options = first.locator('option').evaluate_all('(os)=>os.filter(o=>o.value).map(o=>o.textContent.trim())')
            elif input_type in ('radio', 'checkbox'):
                kind = input_type
                if any(item.get_attribute('type') != kind for item in group.all()):
                    raise ValueError('Mixed question controls')
                options = group.evaluate_all('(es)=>es.map(e=>e.labels && e.labels.length ? e.labels[0].innerText.trim() : "")')
            else:
                raise ValueError('CAPABILITY_UNVERIFIED: unsupported role-requirements control')
            label = first.evaluate("""(e) => {
              const fs = e.closest('fieldset');
              const legend = fs && fs.querySelector('legend');
              if (legend && legend.innerText.trim()) return legend.innerText.trim();
              const labelled = e.getAttribute('aria-labelledby');
              if (labelled) { const n = document.getElementById(labelled); if (n && n.innerText.trim()) return n.innerText.trim(); }
              return '';
            }""")
            if not label and kind == 'select' and first.evaluate('(e)=>e.labels && e.labels.length'):
                label = first.evaluate("""e => {
                    const label = e.labels[0].cloneNode(true);
                    label.querySelectorAll('select').forEach(control => control.remove());
                    return label.textContent.trim();
                }""")
            if not label or not options or not all(options) or len(set(options)) != len(options):
                raise ValueError('CAPABILITY_UNVERIFIED: role-requirements question identity/options incomplete')
            required = first.evaluate("(e)=>!!e.required || e.getAttribute('aria-required')==='true' || !!(e.closest('fieldset') && e.closest('fieldset').querySelector('[required]'))")
            questions.append({'name': name, 'label': label, 'type': kind, 'required': bool(required),
                              'options': options})
        if not questions:
            raise ValueError('CAPABILITY_UNVERIFIED: no role-requirements questions')
        return {'complete': True, 'jobId': job, 'questions': questions}

    def observe_execution_context(self, page, task):
        """Observe the exact job/account/session association for the frozen task."""
        job = job_id(task['url'])
        if not on_site(page.url) or not re.fullmatch(r'/job/' + job + r'(?:/.*)?', urlsplit(page.url).path):
            raise ValueError('CAPABILITY_UNVERIFIED: execution page is not the exact job')
        # The form has no full JD. Read the canonical detail in a separate tab so
        # a new check neither reuses the task digest nor navigates the form away.
        detail = page.context.new_page()
        try:
            response = detail.goto(SITE + '/job/' + job, wait_until='domcontentloaded')
            if (response is None or not response.ok or job_id(detail.url) != job or challenge(detail)):
                raise ValueError('Live job detail is unavailable')
            body = detail.locator('[data-automation="jobAdDetails"]')
            body.first.wait_for(state='visible', timeout=5000)
            jd = body.inner_text().strip() if body.count() == 1 else ''
            if not jd:
                raise ValueError('Live job description missing or ambiguous')
            account = self._visible_account(detail)
            if not account:
                raise ValueError('CAPABILITY_UNVERIFIED: account identity is not observable')
            data = getattr(self, 'data', None)
            session_key = account + '|' + job + '|' + (profile_fingerprint(data) if data is not None else detail.url)
            account_hash = hashlib.sha256(account.encode()).hexdigest()
            session_hash = hashlib.sha256(session_key.encode()).hexdigest()
            return {'jdSha256': hashlib.sha256(jd.encode()).hexdigest(),
                    'accountHash': account_hash, 'sessionHash': session_hash}
        finally:
            detail.close()

    def run(self, url, action, *, setup=None):
        result, errors = [], []
        def perform(page):
            signal.alarm(0)  # User login/form input is not subject to the navigation deadline.
            try:
                if challenge(page):
                    if self.read_only:
                        raise VerificationRequired('CF challenge remains', page.url)
                    raise RuntimeError('CF challenge remains')
                result.append(action(page))
            except Exception as exc:
                errors.append(exc)  # Scrapling otherwise logs and swallows action exceptions.
            finally:
                if self.screenshots:
                    screenshot(page, self.data, 'last-page')
        try:
            with deadline():
                options = {'page_setup': setup} if setup is not None else {}
                self.session.fetch(url, solve_cloudflare=setup is None, page_action=perform, **options)
        except RuntimeError as exc:
            # Scrapling/Patchright can fail while removing its response listener after the page
            # action (no running event loop). The action's own result/error is authoritative; do
            # not discard a completed pre-submit observation or turn it into a fake success.
            if 'no running event loop' not in str(exc) or not (result or errors):
                raise
            print('Scrapling listener cleanup warning: ' + str(exc), file=sys.stderr)
        if errors: raise errors[0]
        if not result: raise RuntimeError('Browser action did not run')
        return result[0]
    def run_interactive(self, url, action, *, setup=None):
        """Visible user-owned step: observe completion, never invoke the automatic challenge solver."""
        result, errors = [], []
        def perform(page):
            signal.alarm(0)
            try: result.append(action(page))
            except Exception as exc: errors.append(exc)
            finally:
                if self.screenshots:
                    screenshot(page, self.data, 'last-page')
        with deadline():
            options = {'page_setup': setup} if setup is not None else {}
            self.session.fetch(url, solve_cloudflare=False, page_action=perform, **options)
        if errors: raise errors[0]
        if not result: raise RuntimeError('Interactive browser action did not run')
        return result[0]

class Flow:
    def __init__(self, page, url, solve):
        self.page, self.id, self.solve = page, job_id(url), solve
        self.selected_attachment = None
        self.application_receipt = None
        self.received_attachment = None
    def active(self):
        return on_site(self.page.url) and re.fullmatch('/job/' + self.id + r'(?:/.*)?', urlsplit(self.page.url).path) is not None
    def application(self):
        return self.active() and re.fullmatch('/job/' + self.id + r'/apply(?:/.*)?', urlsplit(self.page.url).path) is not None
    def invalid(self):
        return first_visible(self.page.locator("[aria-invalid='true'],[data-automation='validation-error']")) is not None or self.page.evaluate("() => [...document.querySelectorAll('input,select,textarea')].some(e => e.getClientRects().length && !e.disabled && !e.checkValidity())")
    def button(self, name):
        return first_visible(self.page.get_by_role('button', name=re.compile('^'+name+'$', re.I)))
    def open(self):
        state = self.enter()
        return self.advance() if state == 'ENTERED' else state

    def enter(self):
        self.solve(self.page)
        if not self.active() or self.invalid(): return 'NEEDS_INPUT'
        if first_visible(self.page.locator("[data-automation='applied-badge']")): return 'ALREADY_APPLIED'
        # Live JobsDB overrides the link's accessible name with 'Apply for <role>'.
        labelled = self.page.locator("a[data-automation='job-detail-apply']").filter(
            has_text=re.compile(r'^\s*Quick apply\s*$', re.I))
        quick = labelled.or_(self.page.get_by_role('link', name=re.compile('^Quick apply$', re.I))).or_(self.page.get_by_role('button', name=re.compile('^Quick apply$', re.I)))
        try: quick.first.wait_for()
        except Exception: return 'SKIPPED'
        button = first_visible(quick)
        if button is None: return 'SKIPPED'
        href = button.get_attribute('href')
        if href:
            target = urljoin(self.page.url, href)
            if not on_site(target) or not re.fullmatch('/job/'+self.id+r'/apply(?:/.*)?', urlsplit(target).path): return 'SKIPPED'
        button.click()
        try: self.page.wait_for_url('**/job/'+self.id+'/apply**')
        except Exception: return 'NEEDS_INPUT'
        return 'ENTERED'
    def advance(self):
        self.solve(self.page)
        if self.active() and not self.application(): return self.open()
        for _ in range(8):
            self.solve(self.page)
            if not self.application() or self.invalid(): return 'NEEDS_INPUT'
            if self.button('Submit application') is not None: return 'REVIEW'
            button = self.button('(Continue|Next)')
            if button is None or not button.is_enabled(): return 'NEEDS_INPUT'
            before = self.page.url + '\n' + self.page.locator('body').inner_text()
            button.click()
            try:
                self.page.wait_for_function("before => location.href + '\\n' + document.body.innerText !== before", arg=before)
            except Exception: return 'NEEDS_INPUT'
        return 'NEEDS_INPUT'
    def review_resume_id(self):
        if not self.application() or not self.page.url.rstrip('/').endswith('/review'):
            return None
        checked = self.page.locator('input[type="radio"][name="document-select"]:checked')
        return checked.get_attribute('value') if checked.count() == 1 else None

    def prepare_automatic(self, resume, answers, *, before_upload=None, before_disclosure=None, on_questions=None):
        """Reach review using an immutable hash-named resume and explicit questionnaire answers.

        No stdin and no submission. Missing/unknown controls return NEEDS_INPUT.
        Caller verifies the resume bytes and owns the submit claim.
        """
        self.reason = ''
        state = self.enter()
        if state != 'ENTERED': return state
        for _ in range(8):
            self.solve(self.page)
            if not self.application(): return self._needs('Application left the expected job')
            # Navigation commits before the SPA replaces the previous step's controls.
            try:
                if self.page.url.endswith('/profile'):
                    self.page.get_by_text("Your Jobsdb Profile is part of your application. Make sure it's up-to-date.",exact=True).wait_for(timeout=15000)
                elif self.page.url.endswith('/role-requirements'):
                    self.page.locator('[name^="questionnaire."]').first.wait_for(timeout=15000)
                elif self.page.url.endswith('/review'):
                    self.page.get_by_role('button',name='Submit application',exact=True).wait_for(timeout=15000)
                self.page.get_by_role('button', name=re.compile(r'^(Continue|Next|Submit application)$',re.I)).first.wait_for(timeout=15000)
            except Exception: return self._needs('Form not ready or session expired')
            if self.page.url.endswith('/role-requirements') and on_questions is not None:
                on_questions(self.page)

            if self.button('Submit application') is not None:
                if self.invalid(): return self._needs('Review contains validation errors')
                if self.page.get_by_text(resume.name, exact=True).count() != 1:
                    return self._needs('Expected resume absent from review')
                return 'REVIEW'
            upload = self.page.locator('#resume-fileFile')
            if upload.count():
                choice = self.page.get_by_role('radio',name=resume.name,exact=True)
                if before_upload:
                    # Previously uploaded files may have the same approved filename.
                    # Identify only the new radio created by this upload of checked bytes.
                    selector = 'input[type="radio"][name="document-select"][value]'
                    previous = self.page.locator(selector).evaluate_all('(es) => es.map(e => e.value)')
                    new_choices = self.page.locator(selector + ''.join(':not([value=' + json.dumps(v) + '])' for v in previous))
                    contents = before_upload()
                    upload.set_input_files(dict(name=resume.name, mimeType='application/vnd.openxmlformats-officedocument.wordprocessingml.document', buffer=contents))
                    new_choices.first.wait_for(state='attached', timeout=30000)
                    if new_choices.count() != 1:
                        return self._needs('Ambiguous uploaded resume identity')
                    resume_id = new_choices.get_attribute('value')
                    if not resume_id or resume_id == resume.name:
                        return self._needs('Uploaded resume has no independent platform identity')
                    choice = choice.and_(self.page.locator(selector + '[value=' + json.dumps(resume_id) + ']'))
                elif choice.count() == 0:
                    upload.set_input_files(str(resume))
                    try: choice.wait_for(timeout=30000)
                    except Exception: return self._needs('Resume upload not confirmed')
                if choice.count() != 1: return self._needs('Ambiguous resume selection')
                if before_disclosure: before_disclosure()
                choice.check()
                if before_disclosure: before_disclosure()
                self.page.get_by_role('radio',name="Don't include a cover letter",exact=True).check()
                if not choice.is_checked(): return self._needs('Resume selection failed')
                self.selected_attachment = {'name': resume.name, 'selected': True}
                if before_upload:
                    self.selected_attachment['resumeId'] = resume_id
            elif self.page.locator('[name^="questionnaire."]').count():
                if on_questions and not self.page.url.endswith('/role-requirements'):
                    return self._needs('Unexpected questionnaire outside the verified step')
                controls = self.page.locator('[name^="questionnaire."]')
                names = controls.evaluate_all('(es)=>[...new Set(es.map(e=>e.name))]')
                for name in names:
                    if name not in answers: return self._needs('Unconfigured employer question: '+name)
                    group = self.page.locator('[name='+json.dumps(name)+']')
                    first = group.first
                    if first.get_attribute('type') == 'checkbox':
                        selected = answers[name]
                        if not isinstance(selected, list) or not selected or any(not isinstance(label,str) or not label for label in selected) or len(set(selected)) != len(selected):
                            return self._needs('Checkbox answers require unique exact labels: '+name)
                        items=group.all()
                        if any(item.get_attribute('type') != 'checkbox' for item in items):
                            return self._needs('Mixed question controls: '+name)
                        labels=[item.evaluate('(e)=>[...e.labels].map(l=>l.innerText.trim())') for item in items]
                        if any(sum(label in options for options in labels)!=1 for label in selected):
                            return self._needs('Unavailable checkbox answer: '+name)
                        for item,options in zip(items,labels):
                            if before_disclosure: before_disclosure()
                            checked = any(label in options for label in selected)
                            item.set_checked(checked)
                            if item.is_checked() != checked: return self._needs('Checkbox selection changed')
                        continue
                    if not isinstance(answers[name],str):
                        return self._needs('Single-choice question requires one exact label: '+name)
                    if first.evaluate('(e)=>e.tagName') == 'SELECT':
                        if group.count()!=1: return self._needs('Ambiguous select: '+name)
                        if before_disclosure: before_disclosure()
                        try:
                            first.select_option(label=answers[name])
                            if first.locator('option:checked').inner_text().strip() != answers[name]:
                                return self._needs('Selected answer changed')
                        except Exception: return self._needs('Unavailable answer: '+name)
                    elif first.get_attribute('type') == 'radio':
                        # A radio's associated label is the answer, not the question.
                        matches = [item for item in group.all() if item.evaluate('(e)=>[...e.labels].map(l=>l.innerText.trim()).includes('+json.dumps(answers[name])+')')]
                        if len(matches)!=1: return self._needs('Unavailable radio answer: '+name)
                        if before_disclosure: before_disclosure()
                        matches[0].check()
                        if not matches[0].is_checked(): return self._needs('Radio answer selection changed')
                    else: return self._needs('Unsupported question control: '+name)
                if on_questions:
                    # An answer can reveal another question or change an earlier selection.
                    on_questions(self.page)
                    for name in names:
                        group = self.page.locator('[name='+json.dumps(name)+']')
                        selected = group.evaluate_all("""els => els.flatMap(e =>
                            e.tagName === 'SELECT' ? Array.from(e.selectedOptions, o => o.textContent.trim()) :
                            e.checked ? [e.labels[0].innerText.trim()] : [])""")
                        expected = answers[name] if isinstance(answers[name], list) else [answers[name]]
                        if sorted(selected) != sorted(expected):
                            return self._needs('Live selected answers differ from approval')
            elif not self.page.url.endswith('/profile'):
                return self._needs('Unrecognised application step')
            if self.invalid(): return self._needs('Validation errors after filling')
            button=self.button('(Continue|Next)')
            if button is None or not button.is_enabled(): return self._needs('Continue unavailable')
            before=self.page.url
            # The profile SPA can expose Continue before its handler is ready.
            # Only this non-submitting step gets one bounded second click.
            attempts = 2 if before.endswith('/profile') else 1
            for attempt in range(attempts):
                if before_disclosure: before_disclosure()
                button.click()
                try:
                    self.page.wait_for_url(lambda u: u != before,timeout=15000)
                    break
                except Exception:
                    if self.page.url != before or self.invalid() or attempt+1 == attempts:
                        return self._needs('Form did not advance from '+before+'; now '+self.page.url)
                    button=self.button('(Continue|Next)')
                    if button is None or not button.is_enabled(): return self._needs('Continue unavailable')
        return self._needs('Application exceeded step limit')

    def _needs(self, reason):
        self.reason = reason
        return 'NEEDS_INPUT'

    def submit(self):
        # No solving, navigation replay or second click after a submission claim.
        if not self.application() or challenge(self.page) or self.invalid(): return 'NEEDS_INPUT'
        button = self.button('Submit application')
        if button is None or not button.is_enabled(): return 'NEEDS_INPUT'
        try:
            button.click()
            self.page.get_by_text(re.compile(r'^(Your application has been (submitted|sent)(?: to [^\r\n]+)?|Application (submitted|sent)|Successfully submitted)[.!]?$', re.I)).first.wait_for()
            return 'SUBMITTED' if self.active() else 'UNKNOWN'
        except Exception: return 'UNKNOWN'

def screenshot(page, data, name):
    try:
        directory = data / 'screenshots'
        directory.mkdir(exist_ok=True)
        page.screenshot(path=str(directory / (name + '.png')))
    except Exception:
        print('Screenshot unavailable', file=sys.stderr)

def prompt(message):
    print(message, flush=True)
    line = sys.stdin.readline()
    return line.strip() if line else None

class ProfileBusy(RuntimeError):
    pass


@contextmanager
def profile_lock(data):
    data.mkdir(parents=True, exist_ok=True)
    with (data / 'run.lock').open('a') as lock:
        try: fcntl.lockf(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError: raise ProfileBusy('Another JobsDB command is using this profile')
        yield

def collect_jobs(page):
    if not on_site(page.url): raise RuntimeError('Search left JobsDB')
    page.locator(CARDS).first.wait_for(timeout=15000)
    rows = {}
    for link in page.locator(CARDS).all():
        try:
            id = job_id(urljoin(page.url, link.get_attribute('href') or ''))
            rows.setdefault(id, {'id':id, 'title':link.inner_text().strip(), 'url':SITE+'/job/'+id})
        except ValueError: continue
    return list(rows.values())

def search(browser, store, keywords, pages, data):
    rows = {}
    for number in range(1, pages+1):
        url = SITE+'/jobs?'+urlencode({'keywords':keywords,'page':number})
        for row in browser.run(url, collect_jobs): rows.setdefault(row['id'], row)
    for row in rows.values():
        row['attempted'] = store.contains(row['id'])
        print(row['id']+'\t'+row['title']+'\t'+row['url'])
    path = data / 'search.json'
    path.with_suffix('.tmp').write_text(json.dumps(list(rows.values()), ensure_ascii=False, indent=2))
    path.with_suffix('.tmp').replace(path)
    print('FOUND='+str(len(rows)))

def login(page):
    print('CF_PASSED: JobsDB 页面已打开。请点击 Sign in 完成登录。', flush=True)
    answer = prompt('完成登录后回到此终端按 Enter 保存并关闭；输入 cancel 取消。')
    if answer is None or answer.lower() == 'cancel':
        print('CANCELLED: no login confirmation received')
        return 2
    if challenge(page):
        print('NEEDS_INPUT: verification still visible')
        return 2
    print('SESSION_SAVED: browser profile retained; account login must be confirmed on the site.')
    return 0

def application(page, browser, store, url, command, data):
    flow = Flow(page, url, browser.solve)
    state = flow.open()
    while state == 'NEEDS_INPUT':
        if prompt('请在窗口完成登录或补充简历/答案；勿手动提交。输入 continue 检查，其他输入取消。') != 'continue':
            print('CANCELLED'); return 2
        state = flow.advance()
    print('STATE='+state)
    if state != 'REVIEW': return 0
    screenshot(page, data, flow.id+'-review')
    if command == 'prepare':
        prompt('PREPARED: 此命令不提交。检查表单后按 Enter 关闭。')
        return 0
    if prompt('核对岗位、简历和答案；确认请输入 SUBMIT '+flow.id) != 'SUBMIT '+flow.id:
        print('CANCELLED'); return 2
    if not store.claim(flow.id):
        print('SKIPPED: already claimed'); return 0
    outcome = flow.submit()
    if outcome != 'SUBMITTED': outcome = 'UNKNOWN'
    store.finish(flow.id, outcome)
    screenshot(page, data, flow.id+'-'+outcome)
    print('RESULT='+outcome)
    return 0 if outcome == 'SUBMITTED' else 2

def _valid_single_answer(value):
    if isinstance(value, str): return bool(value.strip())
    return isinstance(value, list) and bool(value) and all(isinstance(label, str) and label.strip() for label in value) and len(set(value)) == len(value)


def load_single(source):
    task = source if isinstance(source, dict) else json.loads(Path(source).read_text())
    if task.get('kind') != 'JOBSDB_SINGLE_APPLY': raise ValueError('Expected a frozen JobsDB single-job task')
    if type(task.get('schemaVersion')) is not int or task['schemaVersion'] != 1:
        raise ValueError('Unsupported JobsDB single-task schema version')
    attempt, authorization = task.get('attemptId'), task.get('authorizationId')
    if not isinstance(attempt, str) or not attempt or authorization is not None and (not isinstance(authorization, str) or not authorization):
        raise ValueError('Missing attempt or invalid authorization identity')
    url = task.get('url'); job = job_id(url)
    if task.get('jobId') != job:
        raise ValueError('Task job ID differs from its canonical URL')
    resume = Path(task['resumePath']).expanduser().resolve()
    if not resume.is_file(): raise ValueError('Frozen resume file is missing')
    digest = hashlib.sha256(resume.read_bytes()).hexdigest()
    if digest != task.get('resumeSha256'): raise ValueError('Frozen resume SHA256 mismatch')
    answers = task.get('answers')
    if not isinstance(answers, dict) or any(not isinstance(k, str) or not k.startswith('questionnaire.') or not _valid_single_answer(v) for k, v in answers.items()):
        raise ValueError('answers must map questionnaire names to an exact label or unique checkbox labels')
    canonical = json.dumps(answers, sort_keys=True, separators=(',', ':'), ensure_ascii=False).encode()
    if hashlib.sha256(canonical).hexdigest() != task.get('answersSha256'): raise ValueError('Frozen answers SHA256 mismatch')
    result = Path(task['resultPath']); result.parent.mkdir(parents=True, exist_ok=True)
    return task, job, resume, answers, result


def profile_fingerprint(data):
    """Stable isolation-profile identity; account/session is verified separately by observation.

    Chrome rewrites Local State/Preferences during a normal run, so hashing those bytes would
    invalidate the task after the pre-submit upload. The resolved profile root is the stable
    association; never read credentials, cookies or browser content here.
    """
    return hashlib.sha256(str(Path(data).resolve()).encode()).hexdigest()


def single_permission(task, phase, context, requirements=None, attachment=None):
    """Require a fresh, attributed acknowledgement; neither an ID nor an old HTTP 200 is permission."""
    import urllib.request
    endpoint = urlsplit(task.get('callbackUrl', ''))
    if (endpoint.scheme != 'http' or endpoint.hostname not in {'localhost', '127.0.0.1', '::1'}
            or endpoint.username or endpoint.password or endpoint.query or endpoint.fragment
            or endpoint.path != '/api/workbench/tasks/' + task['attemptId'] + '/agent-event'
            or not task.get('token') or task.get('taskId') != task['attemptId'] or task.get('stage') != 'APPLY'):
        raise ValueError('Missing current task/stage permission callback')
    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, *args, **kwargs): return None
    fields = ('attemptId', 'authorizationId', 'jobId', 'url', 'resumeSha256', 'answersSha256', 'profileHash', 'contactHash')
    payload = {'type':'VALIDATE', 'action':'APPLY', 'phase':phase, 'message':'JobsDB fresh ' + phase + ' fence',
               'binding':{key:task.get(key) for key in fields}, 'context':context}
    if requirements is not None:
        payload.update(roleRequirements=requirements, attachment=attachment)
    if phase == 'UPLOAD':
        payload.update(type='SUBMITTING')
        payload['binding'].update({key:task.get(key) for key in ('taskId','stage','executionResourceId','answersSource')})
    request = urllib.request.Request(task['callbackUrl'], json.dumps(payload).encode(),
              headers={'Content-Type':'application/json', 'Authorization':'Bearer ' + task['token']})
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
    with opener.open(request, timeout=10) as response:
        acknowledged = json.load(response)
    if phase == 'UPLOAD':
        claim = acknowledged.get('outboundClaim')
        dispatch = acknowledged.get('jobsDbDispatch', {})
        if (acknowledged.get('id') != task['taskId'] or acknowledged.get('attemptId') != task['attemptId']
                or acknowledged.get('state') != 'UNKNOWN' or not isinstance(claim, str) or not claim
                or acknowledged.get('executionResourceId') != task['executionResourceId']
                or dispatch.get('authorizationId') != claim
                or any(dispatch.get(key) != value for key, value in payload['binding'].items() if key != 'authorizationId')):
            raise ValueError('Unconfirmed or misbound disclosure grant')
        return claim
    expected = {'taskId':task['taskId'], 'attemptId':task['attemptId'], 'authorizationId':task['authorizationId'],
                'executionResourceId':task.get('executionResourceId'), 'action':'APPLY', 'phase':phase, 'status':'CONFIRMED'}
    if (acknowledged.get('id') != task['taskId'] or acknowledged.get('state') != 'UNKNOWN'
            or acknowledged.get('validatedPermission') != expected):
        raise ValueError('Unconfirmed or misbound task/stage permission')


def record_received_attachment(result, application, received):
    """Join the current native submit receipt to the application's received document.
    Trusted platform readers supply these independent observations. The current
    success-page reader has neither a native application ID nor a received-file record.
    """
    if not isinstance(application, dict) or not isinstance(received, dict):
        return
    if (result['status'] != 'SUBMITTED' or result['deliveryOutcome']['attemptDisposition'] != 'claimed'
            or application.get('jobId') != result['jobId'] or application.get('attemptId') != result['attemptId']
            or not isinstance(application.get('applicationId'), str) or not application['applicationId'].strip()
            or not isinstance(received.get('receiptId'), str) or not received['receiptId'].strip()
            or received.get('attemptId', result['attemptId']) != result['attemptId']
            or received.get('applicationId') != application['applicationId']
            or received.get('jobId') != result['jobId'] or received.get('sha256') != result['resumeSha256']):
        return
    result['receipt']['application'] = application
    result['receipt']['receivedAttachment'] = received
    result['deliveryOutcome']['cvActuallyReceived'] = 'confirmed with evidence'


def run_single(task, browser, store, data):
    """One frozen, workbench-approved JobsDB application. No batch, prompts, reruns or answer inference."""
    task, job, resume, answers, result = load_single(task)
    payload = {
        'schemaVersion': 1, 'kind': 'JOBSDB_SINGLE_APPLY', 'taskId': task.get('taskId'), 'attemptId': task['attemptId'], 'authorizationId': task.get('authorizationId', ''),
        'jobId': job, 'url': task['url'], 'resumeSha256': task['resumeSha256'],
        'answersSha256': task['answersSha256'], 'profileHash': task.get('profileHash', ''),
        'contactHash': task.get('contactHash', ''), 'status': 'RUNNING', 'startedAt': time.time(),
    }
    def save():
        temporary = result.with_suffix(result.suffix + '.tmp')
        with temporary.open('w', encoding='utf-8') as output:
            os.fchmod(output.fileno(), 0o600)
            json.dump(payload, output, ensure_ascii=False, indent=2)
            output.flush()
            os.fsync(output.fileno())
        temporary.replace(result)
        directory = os.open(result.parent, os.O_RDONLY)
        try: os.fsync(directory)
        finally: os.close(directory)
    def step(name):
        payload.setdefault('formSteps', []).append(name)
        save()

    outcome = payload['deliveryOutcome'] = {
        'messageSent': 'not applicable', 'cvActuallyReceived': 'not attempted', 'attemptDisposition': 'blocked'}
    prior = store.attempt(job)
    if prior:
        outcome['attemptDisposition'] = 'duplicate'
        payload.update(status='SKIPPED', previousAttempt=prior, reason='previous submitted/unknown attempt')
        # Reopening the same frozen file must not overwrite the original receipt.
        if result.exists():
            print(json.dumps(payload, ensure_ascii=False))
        else:
            save()
        return 0
    if result.is_file():
        previous = json.loads(result.read_text())
        if (previous.get('attemptId') == task['attemptId'] and previous.get('jobId') == job
                and previous.get('status') == 'UNKNOWN'):
            payload.update(status='SKIPPED', reason='Possible disclosure on original attempt',
                           previousAttempt={'attemptId': task['attemptId'], 'deliveryOutcome': previous.get('deliveryOutcome')})
            outcome['attemptDisposition'] = 'duplicate'
            print(json.dumps(payload, ensure_ascii=False))
            return 0
    save()
    if (task.get('taskId') != task['attemptId'] or task.get('stage') != 'APPLY'
            or not task.get('callbackUrl') or not task.get('token') or not task.get('executionResourceId')):
        payload.update(status='STOPPED_BEFORE_EFFECT', reason='Missing current task/stage permission')
        save()
        return 2

    def action(page):
        requirements = None
        def context():
            load_single(task)
            if profile_fingerprint(data) != task.get('profileHash'):
                raise ValueError('Profile association changed')
            observed = browser.observe_execution_context(page, task)
            if not observed.get('jdSha256') or observed['jdSha256'] != task.get('jdSha256'):
                raise ValueError('Live job description differs from approval')
            return observed

        def before_upload():
            if outcome['cvActuallyReceived'] != 'not attempted':
                raise ValueError('Disclosure already reserved')
            step('quick apply entered')
            contents = resume.read_bytes()
            if hashlib.sha256(contents).hexdigest() != task['resumeSha256']:
                raise ValueError('Approved attachment changed')
            observed = context()
            # A lost acknowledgement can hide a committed disclosure reservation.
            try:
                task['authorizationId'] = single_permission(task, 'UPLOAD', observed)
            except HTTPError as exc:
                if 400 <= exc.code < 500:
                    raise
                outcome['cvActuallyReceived'] = 'unknown'
                raise
            except Exception:
                outcome['cvActuallyReceived'] = 'unknown'
                raise
            outcome['cvActuallyReceived'] = 'unknown'
            payload.update(status='UNKNOWN', authorizationId=task['authorizationId'], reason='Possible resume disclosure')
            save()
            return contents

        def recheck():
            if not task.get('authorizationId') or outcome['cvActuallyReceived'] != 'unknown':
                raise ValueError('No disclosure reservation for this form')
            single_permission(task, 'CHECK', context())

        def on_questions(qpage):
            nonlocal requirements
            if not flow.selected_attachment:
                raise ValueError('Missing selected attachment')
            if requirements is None:
                step('approved resume selected')
            live = browser.read_role_requirements(qpage, job)
            if requirements is not None:
                if requirements != live:
                    raise ValueError('Questionnaire changed after selecting answers')
                recheck()
                return
            requirements = live
            attachment = dict(flow.selected_attachment, sha256=task['resumeSha256'])
            payload.update(observedRequirements=requirements, attachment=attachment)
            step('questionnaire read')
            single_permission(task, 'QUESTIONS', context(), requirements, attachment)

        try:
            context()
            flow = Flow(page, task['url'], browser.solve)
            state = flow.prepare_automatic(resume, answers, before_upload=before_upload,
                                           before_disclosure=recheck, on_questions=on_questions)
            if state != 'REVIEW' or not requirements or not payload.get('attachment'):
                raise ValueError(flow.reason or 'Missing complete questionnaire or approved attachment at review')
            payload['reviewTextSha256'] = hashlib.sha256(page.locator('body').inner_text().encode()).hexdigest()
            attachment = payload['attachment']
            def verify_review_attachment():
                observed = flow.review_resume_id()
                if not observed or observed != attachment.get('resumeId'):
                    raise ValueError('Review does not identify the uploaded approved resume')
                attachment['reviewResumeId'] = observed
            observed = context()
            verify_review_attachment()
            single_permission(task, 'CLAIM', observed, requirements, attachment)
            verify_review_attachment()
            step('questions and attachment approved at review')
            if not store.claim(job, task['attemptId'], task.get('entry', 'cli')):
                outcome['attemptDisposition'] = 'duplicate'
                payload.update(status='SKIPPED', previousAttempt=store.attempt(job), reason='Concurrent native claim')
                save()
                return
            outcome['attemptDisposition'] = 'claimed'
            payload.update(claimedAt=store.attempt(job)['claimedAt'], reason='Native claim committed')
            step('native claim committed')
            single_permission(task, 'SUBMIT', context())
            verify_review_attachment()
            submitted = 'SUBMITTED' if flow.submit() == 'SUBMITTED' else 'UNKNOWN'
            payload.update(status=submitted, finishedAt=time.time(), reason='')
            payload['receipt'] = {'url': page.url, 'at': time.time(),
                                  'textSha256': hashlib.sha256(page.locator('body').inner_text().encode()).hexdigest()}
            record_received_attachment(payload, flow.application_receipt, flow.received_attachment)
            # Keep the native claim UNKNOWN until the bound receipt survives a restart.
            save()
            store.finish(job, submitted)
            return
        except Exception as exc:
            payload.update(status='STOPPED_BEFORE_EFFECT' if outcome['cvActuallyReceived'] == 'not attempted' else 'UNKNOWN',
                           reason=(str(exc).splitlines() or ['Form verification failed'])[0])
        save()
    try:
        if browser is None:
            with Browser(data) as browser:
                browser.run(task['url'], action)
        else:
            browser.run(task['url'], action)
    except Exception as exc:
        import traceback
        traceback.print_exc()
        if payload['status'] not in ('SUBMITTED', 'UNKNOWN'):
            payload.update(status='ERROR', reason=(str(exc).splitlines() or ['action failed'])[0])
        else:
            payload['evidenceError'] = (str(exc).splitlines() or ['evidence failed'])[0]
        save()
    return 0 if payload['status'] in ('SUBMITTED', 'SKIPPED') else 2


def execute_single(task_file, data):
    data = Path(data)
    task = json.loads(Path(task_file).read_text())
    source_file = os.environ.get('WORKBENCH_TASK_FILE')
    task['entry'] = 'workbench' if source_file and Path(source_file).resolve() == Path(task_file).resolve() else 'cli'
    try:
        with profile_lock(data), Store(data / 'applications.db') as store:
            return run_single(task, None, store, data)
    except ProfileBusy:
        # The lock holder owns the result file. A competing CLI only reports its
        # observation on stdout and never opens the profile or overwrites evidence.
        job = job_id(task['url'])
        prior = None
        ledger = data / 'applications.db'
        if ledger.exists():
            with sqlite3.connect(ledger.resolve().as_uri() + '?mode=ro', uri=True) as db:
                prior = prior_attempt(db, job)
        print(json.dumps({'schemaVersion': 1, 'kind': 'JOBSDB_SINGLE_APPLY', 'taskId': task.get('taskId'),
                          'attemptId': task.get('attemptId'), 'status': 'SKIPPED' if prior else 'STOPPED_BEFORE_EFFECT', 'jobId': job,
                          'previousAttempt': prior, 'deliveryOutcome': {'messageSent': 'not applicable',
                          'cvActuallyReceived': 'not attempted', 'attemptDisposition': 'duplicate' if prior else 'blocked'}}))
        return 0 if prior else 2


def main(argv=None, *, supervise_resume_process=False):
    parser = argparse.ArgumentParser(description='JobsDB HK — isolated Scrapling + Chrome CLI; no Boss runtime')
    subs = parser.add_subparsers(dest='command', required=True)
    for name in ('login','help'): subs.add_parser(name)
    history = subs.add_parser('history')
    history.add_argument('--json', action='store_true')
    history.add_argument('--data-dir', type=Path, default=DATA)
    history.add_argument('--job-id')
    find = subs.add_parser('search')
    find.add_argument('keywords'); find.add_argument('pages', type=int, choices=range(1,6), nargs='?', default=1)
    for name in ('prepare','apply'):
        subs.add_parser(name).add_argument('url')
    bulk = subs.add_parser('batch')
    bulk.add_argument('--config', required=True)
    single = subs.add_parser('apply-single', help='One frozen workbench-approved JobsDB application; never batch, prompt or retry')
    single.add_argument('task_file')
    single.add_argument('--data-dir', default=str(DATA))
    read = subs.add_parser('read-candidates', help='Bounded workbench read; never apply or load global answers')
    read.add_argument('task_file')
    read.add_argument('--data-dir', required=True, type=Path)
    resume_read = subs.add_parser('resume-read', help='Visible user verification, then continue the same bounded read; never apply')
    resume_read.add_argument('task_file')
    resume_read.add_argument('--data-dir', required=True, type=Path)
    args = parser.parse_args(argv or ['help'])
    if args.command == 'history' and args.json:
        print(json.dumps(history_json(args.data_dir, args.job_id), ensure_ascii=False))
        return 0
    if args.command == 'read-candidates':
        from workbench_read import execute
        return execute(args.task_file, args.data_dir)
    if args.command == 'resume-read':
        if supervise_resume_process and os.environ.get(_RESUME_WORKER) != '1':
            return supervise_resume(args.task_file, args.data_dir)
        from workbench_read import execute_resume
        return execute_resume(args.task_file, args.data_dir)
    if args.command == 'apply-single':
        return execute_single(args.task_file, args.data_dir)
    if args.command == 'help': parser.print_help(); return 0
    id = job_id(args.url) if args.command in ('prepare','apply') else None
    if args.command == 'search' and not args.keywords.strip(): parser.error('keywords must not be blank')
    with profile_lock(DATA), Store(DATA / 'applications.db') as store:
        if args.command == 'history':
            print('\n'.join(store.history())); return 0
        if id and store.contains(id):
            print('SKIPPED: previous submitted/unknown attempt for '+id); return 0
        config = None
        if args.command == 'batch':
            from batch import load_config
            config = load_config(args.config)
        with Browser(DATA) as browser:
            if args.command == 'batch':
                from batch import run
                report = run(config, browser, store, DATA)
                return 0 if report['status'] == 'FINISHED' else 2
            if args.command == 'login': return browser.run(SITE+'/', login)
            if args.command == 'search':
                search(browser, store, args.keywords, args.pages, DATA); return 0
            return browser.run(SITE+'/job/'+id, lambda page: application(page,browser,store,args.url,args.command,DATA))

if __name__ == '__main__':
    signal.signal(signal.SIGTERM, request_cancellation)
    try: sys.exit(main(sys.argv[1:], supervise_resume_process=True))
    except KeyboardInterrupt:
        print('CANCELLED', file=sys.stderr); sys.exit(130)
    except Exception as exc:
        print('FAILED: '+type(exc).__name__+': '+str(exc).splitlines()[0], file=sys.stderr)
        sys.exit(2)
