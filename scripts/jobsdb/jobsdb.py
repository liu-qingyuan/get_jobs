#!/usr/bin/env python3
"""JobsDB-only CLI. No Spring, shared browser, service, or account secrets in logs."""
import argparse
from contextlib import contextmanager
import fcntl
import json
from pathlib import Path
import re
import signal
import sqlite3
import sys
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

def challenge(page):
    title = page.title().lower()
    if any(s in title for s in ('just a moment', '请稍候', '請稍候', 'security verification')):
        return True
    return first_visible(page.locator("iframe[src*='challenges.cloudflare.com'], iframe[src*='recaptcha'], iframe[src*='hcaptcha']")) is not None or first_visible(page.get_by_text(re.compile(r'^(Verify you are human|Performing security verification|Security check)$', re.I))) is not None

@contextmanager
def deadline(seconds=90):
    def expired(*_): raise TimeoutError('CF/navigation timed out; command not retried')
    previous = signal.signal(signal.SIGALRM, expired)
    signal.alarm(seconds)
    try: yield
    finally:
        signal.alarm(0)
        signal.signal(signal.SIGALRM, previous)

class Browser:
    """Owns one pinned Scrapling session. Never attaches to another browser."""
    def __init__(self, data):
        self.data = data
    def __enter__(self):
        from scrapling.fetchers import StealthySession
        self.session = StealthySession(headless=False, real_chrome=True, google_search=False,
            user_data_dir=str(self.data / 'scrapling-profile'), timeout=30000, retries=1)
        self.session.__enter__()
        return self
    def __exit__(self, *args):
        self.session.__exit__(*args)
    def solve(self, page):
        if challenge(page):
            # Pinned private seam: preserves the current form instead of re-fetching it.
            with deadline(): self.session._cloudflare_solver(page)
        if challenge(page): raise RuntimeError('CF challenge remains; no automatic retry')
    def run(self, url, action):
        result, errors = [], []
        def perform(page):
            signal.alarm(0)  # User login/form input is not subject to the navigation deadline.
            try:
                if challenge(page): raise RuntimeError('CF challenge remains')
                result.append(action(page))
            except Exception as exc:
                errors.append(exc)  # Scrapling otherwise logs and swallows action exceptions.
            finally:
                screenshot(page, self.data, 'last-page')
        with deadline():
            self.session.fetch(url, solve_cloudflare=True, page_action=perform)
        if errors: raise errors[0]
        if not result: raise RuntimeError('Browser action did not run')
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

def main(argv=None):
    parser = argparse.ArgumentParser(description='JobsDB HK — isolated Scrapling + Chrome CLI; no Boss runtime')
    subs = parser.add_subparsers(dest='command', required=True)
    for name in ('login','history','help'): subs.add_parser(name)
    find = subs.add_parser('search')
    find.add_argument('keywords'); find.add_argument('pages', type=int, choices=range(1,6), nargs='?', default=1)
    for name in ('prepare','apply'):
        subs.add_parser(name).add_argument('url')
    bulk = subs.add_parser('batch')
    bulk.add_argument('--config', required=True)
    args = parser.parse_args(argv or ['help'])
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
    def terminate(*_): raise KeyboardInterrupt()
    signal.signal(signal.SIGTERM, terminate)
    try: sys.exit(main(sys.argv[1:]))
    except KeyboardInterrupt:
        print('CANCELLED', file=sys.stderr); sys.exit(130)
    except Exception as exc:
        print('FAILED: '+type(exc).__name__+': '+str(exc).splitlines()[0], file=sys.stderr)
        sys.exit(2)
