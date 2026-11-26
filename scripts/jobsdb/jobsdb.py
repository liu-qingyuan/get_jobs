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
    def claim(self, id):
        return self.db.execute("INSERT OR IGNORE INTO applications(job_id,status) VALUES(?,'UNKNOWN')", (id,)).rowcount == 1
    def finish(self, id, state):
        if state not in ('SUBMITTED', 'UNKNOWN'):
            raise ValueError('Only SUBMITTED or UNKNOWN may finish a claim')
        if self.db.execute('UPDATE applications SET status=?,updated_at=CURRENT_TIMESTAMP WHERE job_id=?', (state,id)).rowcount != 1:
            raise ValueError('Missing prior claim')
    def history(self):
        return ['\t'.join(row) for row in self.db.execute('SELECT job_id,status,updated_at FROM applications ORDER BY updated_at DESC')]

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
        with deadline():
            options = {'page_setup': setup} if setup is not None else {}
            self.session.fetch(url, solve_cloudflare=setup is None, page_action=perform, **options)
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
    def prepare_automatic(self, resume, answers):
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
            if self.button('Submit application') is not None:
                if self.invalid(): return self._needs('Review contains validation errors')
                if not self.page.get_by_text(resume.name, exact=True).count():
                    return self._needs('Expected resume absent from review')
                return 'REVIEW'
            upload = self.page.locator('#resume-fileFile')
            if upload.count():
                choice = self.page.get_by_role('radio',name=resume.name,exact=True)
                if choice.count() == 0:
                    upload.set_input_files(str(resume))
                    try: choice.wait_for(timeout=30000)
                    except Exception: return self._needs('Resume upload not confirmed')
                if choice.count() != 1: return self._needs('Ambiguous resume selection')
                choice.check()
                self.page.get_by_role('radio',name="Don't include a cover letter",exact=True).check()
                if not choice.is_checked(): return self._needs('Resume selection failed')
            elif self.page.locator('[name^="questionnaire."]').count():
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
                            item.set_checked(any(label in options for label in selected))
                        continue
                    if not isinstance(answers[name],str):
                        return self._needs('Single-choice question requires one exact label: '+name)
                    if first.evaluate('(e)=>e.tagName') == 'SELECT':
                        if group.count()!=1: return self._needs('Ambiguous select: '+name)
                        try: first.select_option(label=answers[name])
                        except Exception: return self._needs('Unavailable answer: '+name)
                    elif first.get_attribute('type') == 'radio':
                        # A radio's associated label is the answer, not the question.
                        matches = [item for item in group.all() if item.evaluate('(e)=>[...e.labels].map(l=>l.innerText.trim()).includes('+json.dumps(answers[name])+')')]
                        if len(matches)!=1: return self._needs('Unavailable radio answer: '+name)
                        matches[0].check()
                    else: return self._needs('Unsupported question control: '+name)
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

@contextmanager
def profile_lock(data):
    data.mkdir(parents=True, exist_ok=True)
    with (data / 'run.lock').open('a') as lock:
        try: fcntl.lockf(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError: raise RuntimeError('Another JobsDB command is using this profile')
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
    attempt, authorization = task.get('attemptId'), task.get('authorizationId')
    if not isinstance(attempt, str) or not attempt or not isinstance(authorization, str) or not authorization:
        raise ValueError('Missing attempt or authorization identity')
    url = task.get('url'); job = job_id(url)
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


def run_single(task, browser, store, data):
    """One frozen, workbench-approved JobsDB application. No batch, prompts, reruns or answer inference."""
    task, job, resume, answers, result = load_single(task)
    payload = {
        'kind': 'JOBSDB_SINGLE_APPLY', 'attemptId': task['attemptId'], 'authorizationId': task['authorizationId'],
        'jobId': job, 'url': task['url'], 'resumeSha256': task['resumeSha256'],
        'answersSha256': task['answersSha256'], 'profileHash': task.get('profileHash', ''),
        'contactHash': task.get('contactHash', ''), 'status': 'RUNNING', 'startedAt': time.time(),
    }
    def save():
        temporary = result.with_suffix(result.suffix + '.tmp')
        temporary.write_text(json.dumps(payload, ensure_ascii=False, indent=2))
        try: os.chmod(temporary, 0o600)
        except OSError: pass
        temporary.replace(result)
    save()
    if store.contains(job):
        payload.update(status='SKIPPED', reason='previous submitted/unknown attempt'); save(); return 0
    outcome = {'state': 'ERROR'}
    def action(page):
        flow = Flow(page, task['url'], browser.solve)
        try:
            state = flow.prepare_automatic(resume, answers)
        except Exception as exc:
            payload.update(status='ERROR', reason=(str(exc).splitlines() or ['form preparation failed'])[0]); save(); return
        payload['reason'] = flow.reason
        if state != 'REVIEW':
            login = not on_site(page.url) or re.search(r'/(?:login|sign-in|signin|auth)(?:/|\?|$)', page.url, re.I)
            payload.update(status='LOGIN_REQUIRED' if login else 'NEEDS_INPUT',
                           reason=flow.reason or ('Saved session requires login' if login else 'Form requires user input'))
            save(); return
        payload['reviewTextSha256'] = hashlib.sha256(page.locator('body').inner_text().encode()).hexdigest()
        if not store.claim(job):
            payload.update(status='SKIPPED', reason='already claimed or previously attempted'); save(); return
        payload.update(status='UNKNOWN', claimedAt=time.time(), reason='submission claimed; no receipt yet'); save()
        try:
            submitted = flow.submit()
        except Exception:
            submitted = 'UNKNOWN'
        submitted = 'SUBMITTED' if submitted == 'SUBMITTED' else 'UNKNOWN'
        store.finish(job, submitted)
        payload.update(status=submitted, finishedAt=time.time(), reason='')
        try:
            payload['receipt'] = {'url': page.url, 'textSha256': hashlib.sha256(page.locator('body').inner_text().encode()).hexdigest(), 'at': time.time()}
        except Exception:
            payload['receipt'] = {'url': page.url, 'at': time.time()}
        save()
        outcome['state'] = submitted
    try:
        browser.run(task['url'], action)
    except Exception as exc:
        if payload['status'] not in ('SUBMITTED', 'UNKNOWN'):
            payload.update(status='ERROR', reason=(str(exc).splitlines() or ['action failed'])[0])
        else:
            payload['evidenceError'] = (str(exc).splitlines() or ['evidence failed'])[0]
        save()
    return 0 if payload['status'] in ('SUBMITTED', 'SKIPPED') else 2


def execute_single(task_file, data):
    data = Path(data)
    task = json.loads(Path(task_file).read_text())
    with profile_lock(data), Store(data / 'applications.db') as store:
        with Browser(data) as browser:
            return run_single(task, browser, store, data)


def main(argv=None, *, supervise_resume_process=False):
    parser = argparse.ArgumentParser(description='JobsDB HK — isolated Scrapling + Chrome CLI; no Boss runtime')
    subs = parser.add_subparsers(dest='command', required=True)
    for name in ('login','history','help'): subs.add_parser(name)
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
