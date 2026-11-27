"""Bounded JobsDB candidate Adapter. No application-profile data, global answers, or callbacks."""
import hashlib
import json
import os
from pathlib import Path
import re
import time
from urllib.parse import urljoin, urlsplit

import jobsdb


def validate_source(url):
    if not jobsdb.on_site(url) or urlsplit(url).fragment or not re.fullmatch(r'/(?:jobs|[A-Za-z0-9-]+-jobs)/?', urlsplit(url).path):
        raise ValueError('Expected an authorized HTTPS hk.jobsdb.com job list')


def secure_service_workers(page):
    """Keep persisted worker state untouched while forcing this page's traffic through route guards."""
    session = page.context.new_cdp_session(page)
    session.send('ServiceWorker.enable')
    session.send('ServiceWorker.stopAllWorkers')
    session.send('Network.enable')
    session.send('Network.setBypassServiceWorker', {'bypass': True})
    # Retain the session for the page lifetime; detaching early would remove the bypass.
    setattr(page, '_workbench_read_cdp', session)


def interactive_request_allowed(page_url, method, url):
    trusted_suffixes = ('jobsdb.com', 'seek.com', 'seekcdn.com', 'cloudflare.com', 'hcaptcha.com',
                        'google.com', 'gstatic.com', 'googleapis.com')
    action_path = re.compile(r'(?i)(?:^|[-_/])(?:apply|application|applications|upload|contact|contacts|message|messages|chat)(?:[-_/]|$)')
    auth_write_path = re.compile(r'(?i)^/(?:api/)?(?:oauth/(?:authorize|token|callback)|auth/(?:login|signin|sign-in|session|callback|mfa|challenge|verify)|login|signin|sign-in|session|mfa|password/(?:forgot|reset|verify))/?$')
    parsed = urlsplit(url)
    host = (parsed.hostname or '').lower()
    trusted = any(host == suffix or host.endswith('.' + suffix) for suffix in trusted_suffixes)
    if parsed.scheme != 'https' or not trusted or action_path.search(parsed.path):
        return False
    if method in ('GET', 'HEAD', 'OPTIONS'):
        return True
    current = urlsplit(page_url)
    same_auth_page = (user_action_url(page_url) and (current.hostname or '').lower() == host
                      and auth_write_path.search(parsed.path) is not None)
    same_authorized_navigation = (method == 'POST' and jobsdb.on_site(url)
                      and (current.hostname or '').lower() == host and parsed.path == current.path
                      and re.fullmatch(r'/(?:jobs|[A-Za-z0-9-]+-jobs|job/[0-9]+)/?', parsed.path) is not None)
    cloudflare_flow = re.fullmatch(
        r'/cdn-cgi/challenge-platform/[A-Za-z0-9_.-]{1,32}/[A-Za-z0-9_.-]{1,32}/'
        r'(?:flow/ov1|fo|eb)/[A-Za-z0-9._:%-]{1,512}(?:/[A-Za-z0-9._:%-]{1,512})*',
        parsed.path) is not None
    challenge_write = (((host == 'challenges.cloudflare.com' or host == (current.hostname or '').lower()) and cloudflare_flow)
                       or ((host == 'hcaptcha.com' or host.endswith('.hcaptcha.com'))
                           and re.fullmatch(r'/(?:checksiteconfig|getcaptcha|checkcaptcha/[A-Za-z0-9_-]+)', parsed.path) is not None)
                       or (host.endswith('.google.com')
                           and re.fullmatch(r'/recaptcha/(?:api2|enterprise)/(?:reload|userverify)', parsed.path) is not None))
    return same_auth_page or challenge_write or same_authorized_navigation


def interactive_guard(page):
    """Allows visible login/challenge traffic but blocks application/contact/upload paths and unknown writes."""
    secure_service_workers(page)
    def guard(route):
        request = route.request
        if interactive_request_allowed(page.url, request.method, request.url):
            route.fallback()
        else:
            route.abort()
    page.context.route('**/*', guard)


def user_action_url(url):
    parsed = urlsplit(url)
    host, path = (parsed.hostname or '').lower(), parsed.path.lower()
    return (host in ('id.seek.com', 'auth.seek.com', 'login.seek.com')
            or host.endswith('.id.seek.com')
            or (host == 'hk.jobsdb.com' and re.search(r'/(?:oauth|auth|login|signin|sign-in)(?:/|$)', path) is not None))


def user_action_required(page):
    return bool(getattr(page, '_workbench_user_action', False) or jobsdb.challenge(page) or user_action_url(page.url))


def solve_user_action(page, browser):
    """Use the pinned Scrapling solver on this user-owned visible page; never enters credentials."""
    if not jobsdb.challenge(page):
        return
    try:
        browser.solve(page)
    except Exception:
        # A challenge that the local solver cannot clear stays a bounded checkpoint, not a retry loop.
        if jobsdb.challenge(page):
            raise jobsdb.VerificationRequired('JobsDB automatic platform verification did not complete', page.url)


def read_only(page):
    # Installed by Scrapling page_setup BEFORE navigation, not after the list's scripts run.
    secure_service_workers(page)
    def observe(response):
        try:
            if ((response.status in (401, 403) and response.request.is_navigation_request())
                    or response.headers.get('cf-mitigated') == 'challenge'):
                setattr(page, '_workbench_user_action', True)
        except Exception:
            pass
    page.on('response', observe)
    def guard(route):
        request = route.request
        url = request.url
        permitted = False
        if request.method == 'GET':
            if jobsdb.on_site(url) and request.resource_type in ('script', 'stylesheet', 'image', 'font'):
                permitted = re.fullmatch(r'/(?:_next/static|assets)/[^?#]+\.(?:js|css|png|jpg|jpeg|svg|webp|ico|woff2?)', urlsplit(url).path) is not None
            elif request.is_navigation_request():
                try:
                    if user_action_url(url):
                        pass
                    elif jobsdb.on_site(url) and urlsplit(url).path.startswith('/job/'):
                        jobsdb.job_id(url)
                    elif jobsdb.on_site(url):
                        validate_source(url)
                    else:
                        raise ValueError('Unsupported redirect')
                    permitted = True
                except ValueError:
                    pass
        if permitted:
            route.fallback()
        else:
            route.abort()
    page.context.route('**/*', guard)


def read_candidates(bundle, browser, deadline=None):
    if bundle.get('kind') != 'CANDIDATE_READ' or bundle.get('source', {}).get('channel') != 'JOBSDB':
        raise ValueError('Expected a JobsDB read-only task')
    if any(key in bundle for key in ('token', 'task', 'resumePath', 'callbackUrl')):
        raise ValueError('Read-only task must not include application capabilities or materials')
    source, limits = bundle['source'], bundle['limits']
    validate_source(source['url'])
    slots, seconds = limits['candidates'], limits['seconds']
    if type(slots) is not int or not 1 <= slots <= 40 or type(seconds) is not int or not 1 <= seconds <= 120 or limits.get('modelTokens') != 0:
        raise ValueError('Invalid JobsDB read resource envelope')
    end = time.monotonic() + seconds if deadline is None else deadline

    def remaining(page):
        value = int((end - time.monotonic()) * 1000)
        if value <= 0:
            raise TimeoutError('JobsDB read time budget exhausted; checkpoint retained')
        page.set_default_timeout(min(value, 2000))
        page.set_default_navigation_timeout(value)

    def read(page):
        if user_action_required(page):
            raise jobsdb.VerificationRequired('JobsDB login or verification is required', page.url, max(0, int(end - time.monotonic())))
        remaining(page)
        if page.url != source['url']:
            # No redirects to another search or channel; recognized login redirects are checkpoints above.
            raise RuntimeError('JobsDB list identity changed or session is unavailable')
        page.locator(jobsdb.CARDS).first.wait_for()
        links = page.locator(jobsdb.CARDS).all()
        if len(links) > 200:
            raise RuntimeError('JobsDB loaded list exceeds bounded schema; not truncated silently')
        rows = {}
        for link in links:
            remaining(page)
            try:
                id = jobsdb.job_id(urljoin(page.url, link.get_attribute('href') or ''))
                rows.setdefault(id, {'url': jobsdb.SITE + '/job/' + id})
            except ValueError:
                continue
        fingerprint = hashlib.sha256(json.dumps(list(rows)).encode()).hexdigest()
        cursor = source.get('cursor', '')
        offset = 0
        if cursor:
            parts = cursor.split(':')
            if len(parts) != 2 or parts[0] != fingerprint or not parts[1].isdigit():
                raise RuntimeError('JobsDB loaded list changed; start a new read-only turn')
            offset = int(parts[1])
            if offset >= len(rows):
                raise ValueError('Invalid JobsDB read cursor')
        output = {'jobs': [], 'note': 'Only loaded list entries and their full details; no application actions. Production coverage unverified.'}
        selected = list(rows.values())[offset:offset + slots]
        skipped = []
        for row in selected:
            remaining(page)
            try:
                response = page.goto(row['url'], wait_until='domcontentloaded')
                challenged = (response is not None and response.headers.get('cf-mitigated') == 'challenge') or user_action_required(page)
                if challenged:
                    raise jobsdb.VerificationRequired('JobsDB login or verification remains', row['url'], max(0, int(end - time.monotonic())))
                if jobsdb.job_id(page.url) != jobsdb.job_id(row['url']):
                    skipped.append(row['url']); continue
                title = page.locator('[data-automation="job-detail-title"]')
                company = page.locator('[data-automation="advertiser-name"]')
                if title.count() != 1 or company.count() != 1:
                    skipped.append(row['url']); continue
                description = page.locator('[data-automation="jobAdDetails"]')
                content = description.inner_text() if description.count() == 1 else ''
                complete = 120 <= len(content) <= 30000
                output['jobs'].append(dict(row, title=title.inner_text().strip(), company=company.inner_text().strip(),
                                           description=content if len(content) <= 30000 else '', descriptionComplete=complete))
            except jobsdb.VerificationRequired:
                raise
            except Exception:
                skipped.append(row['url'])
        if skipped:
            output['skipped'] = skipped
        remaining(page)
        if offset + len(selected) < len(rows):
            output['nextUrl'] = source['url']
            output['cursor'] = fingerprint + ':' + str(offset + len(selected))
        return output

    return browser.run(source['url'], read, setup=read_only)


def user_action_target(bundle, value=None):
    source = bundle['source']['url']
    if not value or value == source:
        return source
    try:
        return jobsdb.SITE + '/job/' + jobsdb.job_id(value)
    except ValueError:
        return source


def wait_for_user_access(bundle, browser):
    source = bundle['source']
    target = user_action_target(bundle, bundle.get('userActionUrl'))
    def wait(page):
        # The pinned Scrapling solver automatically clears CF/CAPTCHA on this user-owned profile.
        # Login or an unsolvable platform check remains a bounded checkpoint; no credentials are read or entered.
        attempts = redirects = 0
        deadline = time.monotonic() + 120
        while True:
            if jobsdb.cancellation_requested():
                raise KeyboardInterrupt()
            if time.monotonic() >= deadline:
                raise jobsdb.VerificationRequired('JobsDB interactive verification exceeded its bounded wait', page.url)
            if not user_action_required(page) and page.url == target:
                return True
            if not user_action_required(page) and jobsdb.on_site(page.url):
                redirects += 1
                if redirects > 3:
                    raise jobsdb.VerificationRequired('JobsDB kept leaving the authorized target; checkpoint retained', page.url)
                page.goto(target, wait_until='domcontentloaded')
                continue
            if jobsdb.challenge(page) and attempts < 2:
                attempts += 1
                try:
                    solve_user_action(page, browser)
                except jobsdb.VerificationRequired:
                    if attempts >= 2:
                        raise
                continue
            raise jobsdb.VerificationRequired('JobsDB login or platform verification requires the visible browser', page.url)
    return browser.run_interactive(target, wait, setup=interactive_guard)


def validate_resume(bundle):
    attempt = bundle.get('readAttemptId')
    if bundle.get('resumeReadId') != attempt or not isinstance(attempt, str) or not re.fullmatch(r'[A-Za-z0-9._:-]{1,120}', attempt):
        raise ValueError('JobsDB resume identity does not match the pending read')


def resume_candidates(bundle, interactive_browser, read_browser):
    validate_resume(bundle)
    wait_for_user_access(bundle, interactive_browser)
    started = time.monotonic()
    return read_candidates(bundle, read_browser, deadline=started + bundle['limits']['seconds'])


def execute(task_file, data):
    file = Path(task_file)
    if file.is_symlink() or not file.is_file() or file.stat().st_size > 2 * 1024 * 1024:
        raise ValueError('Invalid private read task file')
    bundle = json.loads(file.read_text())
    result = Path(bundle['resultPath'])
    if result.is_symlink() or result.parent.resolve() != file.parent.resolve():
        raise ValueError('Read result must stay in the private task directory')
    # Missing connection is not permission to create a new login/session.
    if not (data / 'scrapling-profile').is_dir():
        raise RuntimeError('JobsDB independent session missing; explicitly login with the original CLI first')
    try:
        with jobsdb.profile_lock(data), jobsdb.Browser(data, read_only=True, screenshots=False) as browser:
            output = read_candidates(bundle, browser)
    except jobsdb.VerificationRequired as error:
        # Fixed control data only; never persist challenge text, credentials or page content.
        attempt = bundle.get('readAttemptId')
        if not isinstance(attempt, str) or not re.fullmatch(r'[A-Za-z0-9._:-]{1,120}', attempt):
            raise ValueError('Missing bounded JobsDB read attempt identity')
        output = {'userActionRequired': {'interactionId': attempt,
            'targetUrl': user_action_target(bundle, error.target_url),
            'message': 'JobsDB 要求用户在可见浏览器完成平台验证或登录'}}
        remaining = bundle['limits']['seconds'] if error.remaining_seconds is None else error.remaining_seconds
        output['userActionRequired']['remainingSeconds'] = max(0, int(remaining))
    except RuntimeError as error:
        # A persisted Service Worker can block the strict read-only browser before navigation.
        # Keep the same bounded user-action/resume path instead of treating it as an unknown error.
        if 'Service Worker' not in str(error) and 'read-only access' not in str(error): raise
        attempt = bundle.get('readAttemptId')
        if not isinstance(attempt, str) or not re.fullmatch(r'[A-Za-z0-9._:-]{1,120}', attempt):
            raise ValueError('Missing bounded JobsDB read attempt identity')
        output = {'userActionRequired': {'interactionId': attempt,
            'targetUrl': user_action_target(bundle, bundle.get('userActionUrl')),
            'message': 'JobsDB 现有会话需在可见浏览器完成平台验证或登录；严格只读守卫不会被放宽',
            'remainingSeconds': max(0, int(bundle['limits']['seconds']))}}
    result.write_text(json.dumps(output, ensure_ascii=False))
    return 0


def write_result(path, output):
    """Publish one complete result, then create a hash-bound marker after the result name is durable."""
    payload = json.dumps(output, ensure_ascii=False).encode()
    temporary = path.with_name(path.name + '.next')
    with temporary.open('xb') as stream:
        stream.write(payload)
        stream.flush()
        os.fsync(stream.fileno())
    os.replace(temporary, path)
    directory = os.open(path.parent, os.O_RDONLY)
    try:
        os.fsync(directory)
    finally:
        os.close(directory)
    marker = path.with_name(path.name + '.published')
    with marker.open('x') as stream:
        stream.write(hashlib.sha256(payload).hexdigest())
        stream.flush()
        os.fsync(stream.fileno())
    directory = os.open(path.parent, os.O_RDONLY)
    try:
        os.fsync(directory)
    finally:
        os.close(directory)


def execute_resume(task_file, data):
    file = Path(task_file)
    if file.is_symlink() or not file.is_file() or file.stat().st_size > 2 * 1024 * 1024:
        raise ValueError('Invalid private read task file')
    bundle = json.loads(file.read_text())
    result = Path(bundle['resultPath'])
    if result.is_symlink() or result.parent.resolve() != file.parent.resolve():
        raise ValueError('Read result must stay in the private task directory')
    if not (data / 'scrapling-profile').is_dir():
        raise RuntimeError('JobsDB independent session missing; cannot resume the visible user step')
    validate_resume(bundle)
    remaining_seconds = bundle['limits']['seconds']
    strict_started = False
    with jobsdb.profile_lock(data):
        try:
            with jobsdb.Browser(data, read_only=True, allow_existing_worker=True, screenshots=False) as browser:
                wait_for_user_access(bundle, browser)
                # Reuse the solved persistent context so the platform clearance survives, but replace the
                # interactive route with the strict read route before any authorized page is read.
                browser.session.context.unroute('**/*')
                strict_started = True
                started = time.monotonic()
                started_epoch = int(time.time() * 1000)
                target_url = user_action_target(bundle, bundle.get('userActionUrl'))
                result.write_text(json.dumps({'readAttemptInProgress': {'interactionId': bundle['readAttemptId'],
                    'targetUrl': target_url, 'startedAtEpochMillis': started_epoch}}, ensure_ascii=False))
                output = read_candidates(bundle, browser, deadline=started + remaining_seconds)
                output['readAttemptCompleted'] = {'interactionId': bundle['readAttemptId'], 'targetUrl': target_url,
                    'startedAtEpochMillis': started_epoch, 'completedAtEpochMillis': int(time.time() * 1000),
                    'budgetSeconds': remaining_seconds}
        except jobsdb.VerificationRequired as error:
            if strict_started:
                if error.remaining_seconds is not None:
                    remaining_seconds = min(remaining_seconds, max(0, int(error.remaining_seconds)))
                else:
                    remaining_seconds -= max(1, int(time.monotonic() - started + 0.999))
                output = {'userActionRequired': {'interactionId': bundle['readAttemptId'],
                    'targetUrl': user_action_target(bundle, error.target_url), 'remainingSeconds': max(0, remaining_seconds),
                    'message': ('JobsDB 本页只读时间预算已耗尽；保留断点且不重置预算' if remaining_seconds <= 0
                        else 'JobsDB 仍要求平台验证或登录；保留同一断点')}}
            else:
                output = {'userActionRequired': {'interactionId': bundle['readAttemptId'],
                    'targetUrl': user_action_target(bundle, bundle.get('userActionUrl')),
                    'remainingSeconds': remaining_seconds,
                    'message': 'JobsDB 自动验证或登录未完成；保留同一断点且不消耗严格读取时间'}}
    write_result(result, output)
    return 0
