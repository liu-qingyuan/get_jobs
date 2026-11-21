"""Bounded JobsDB candidate Adapter. No application-profile data, global answers, or callbacks."""
import hashlib
import json
from pathlib import Path
import re
import time
from urllib.parse import urljoin, urlsplit

import jobsdb


def validate_source(url):
    if not jobsdb.on_site(url) or urlsplit(url).fragment or not re.fullmatch(r'/(?:jobs|[A-Za-z0-9-]+-jobs)/?', urlsplit(url).path):
        raise ValueError('Expected an authorized HTTPS hk.jobsdb.com job list')


def read_only(page):
    # Installed by Scrapling page_setup BEFORE navigation, not after the list's scripts run.
    def guard(route):
        request = route.request
        url = request.url
        permitted = False
        if request.method == 'GET' and jobsdb.on_site(url):
            if request.resource_type in ('script', 'stylesheet', 'image', 'font'):
                permitted = re.fullmatch(r'/(?:_next/static|assets)/[^?#]+\.(?:js|css|png|jpg|jpeg|svg|webp|ico|woff2?)', urlsplit(url).path) is not None
            elif request.is_navigation_request():
                try:
                    if urlsplit(url).path.startswith('/job/'):
                        jobsdb.job_id(url)
                    else:
                        validate_source(url)
                    permitted = True
                except ValueError:
                    pass
        if permitted:
            route.fallback()
        else:
            route.abort()
    page.context.route('**/*', guard)


def read_candidates(bundle, browser):
    if bundle.get('kind') != 'CANDIDATE_READ' or bundle.get('source', {}).get('channel') != 'JOBSDB':
        raise ValueError('Expected a JobsDB read-only task')
    if any(key in bundle for key in ('token', 'task', 'resumePath', 'callbackUrl')):
        raise ValueError('Read-only task must not include application capabilities or materials')
    source, limits = bundle['source'], bundle['limits']
    validate_source(source['url'])
    slots, seconds = limits['candidates'], limits['seconds']
    if type(slots) is not int or not 1 <= slots <= 40 or type(seconds) is not int or not 1 <= seconds <= 120 or limits.get('modelTokens') != 0:
        raise ValueError('Invalid JobsDB read resource envelope')
    end = time.monotonic() + seconds

    def remaining(page):
        value = int((end - time.monotonic()) * 1000)
        if value <= 0:
            raise TimeoutError('JobsDB read time budget exhausted; checkpoint retained')
        page.set_default_timeout(min(value, 2000))
        page.set_default_navigation_timeout(value)

    def read(page):
        remaining(page)
        if page.url != source['url']:
            # No redirects to a login page, other search or another channel.
            raise RuntimeError('JobsDB list identity changed or session is unavailable')
        page.locator(jobsdb.CARDS).first.wait_for()
        links = page.locator(jobsdb.CARDS).all()
        if len(links) > 200:
            raise RuntimeError('JobsDB loaded list exceeds bounded schema; not truncated silently')
        rows = {}
        for link in links:
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
        for row in selected:
            remaining(page)
            page.goto(row['url'], wait_until='domcontentloaded')
            if jobsdb.job_id(page.url) != jobsdb.job_id(row['url']):
                raise RuntimeError('JobsDB detail changed job identity')
            title = page.locator('[data-automation="job-detail-title"]')
            company = page.locator('[data-automation="advertiser-name"]')
            if title.count() != 1 or company.count() != 1:
                raise RuntimeError('JobsDB detail title/company schema unavailable; no invented candidate')
            description = page.locator('[data-automation="jobAdDetails"]')
            content = description.inner_text() if description.count() == 1 else ''
            complete = 120 <= len(content) <= 30000
            output['jobs'].append(dict(row, title=title.inner_text().strip(), company=company.inner_text().strip(),
                                       description=content if len(content) <= 30000 else '', descriptionComplete=complete))
        if offset + len(selected) < len(rows):
            output['nextUrl'] = source['url']
            output['cursor'] = fingerprint + ':' + str(offset + len(selected))
        return output

    return browser.run(source['url'], read, setup=read_only)


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
    with jobsdb.profile_lock(data), jobsdb.Browser(data, read_only=True) as browser:
        output = read_candidates(bundle, browser)
    result.write_text(json.dumps(output, ensure_ascii=False))
    return 0
