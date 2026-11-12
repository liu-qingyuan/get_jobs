#!/usr/bin/env python3
"""Script-first OfferToday adapter: `connect`, `discover`, `run TASKFILE`.

Only `connect` may attach to the existing Chrome via the official extension. All
other commands require that same already-open, attached playwright-cli session.
"""
import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
from urllib.parse import urlsplit
import zipfile

# Use the workbench's existing localhost-only, no-redirect reporter.
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'workbench'))
from report import report as report_event

ROOT = Path(__file__).resolve().parents[2]
DETAIL = re.compile(r'/hk/job/([A-Za-z0-9_-]{12,128}={0,2})/?\Z')
SESSION = re.compile(r'[A-Za-z0-9][A-Za-z0-9_-]{0,63}\Z')


class BrowserBlocked(Exception):
    """No verified browser outcome; do not treat CLI process exit as evidence."""


def parse_browser_result(output, returncode):
    """Decode run-code's actual result, never the shell exit status or a page snapshot."""
    if returncode or '### Error' in output:
        raise BrowserBlocked('Browser command failed or returned an error')
    text = output.strip()
    if text.startswith('### Result\n'):
        text = text[len('### Result\n'):].split('\n### ', 1)[0]
    try:
        result = json.loads(text)
    except (ValueError, TypeError) as exc:
        raise BrowserBlocked('Browser returned no structured result') from exc
    if isinstance(result, dict) and set(result) == {'result'} and isinstance(result['result'], str):
        try:
            result = json.loads(result['result'])
        except ValueError as exc:
            raise BrowserBlocked('Browser result is not structured JSON') from exc
    if not isinstance(result, dict) or result.get('isError') or result.get('status') not in {'ok', 'blocked'}:
        raise BrowserBlocked('Browser returned an unrecognized result')
    return result


def job_url(value):
    if not isinstance(value, str):
        raise BrowserBlocked('Incomplete or invalid OfferToday job URL')
    try:
        url = urlsplit(value)
        match = DETAIL.fullmatch(url.path)
        if (url.scheme != 'https' or url.hostname != 'www.offertoday.com' or url.username or url.password
                or url.port not in (None, 443) or url.fragment or not match):
            raise ValueError('unrecognized job detail')
    except ValueError as exc:
        raise BrowserBlocked('Incomplete or invalid OfferToday job URL') from exc
    return 'https://www.offertoday.com/hk/job/' + match[1]


def _connection():
    workspace = Path(os.environ.get('OFFERTODAY_BROWSER_WORKDIR') or ROOT).expanduser().resolve()
    config = workspace / '.offertoday' / 'connection.json'
    if config.is_symlink() or config.parent.is_symlink():
        raise BrowserBlocked('Connection metadata path is a symlink')
    try:
        info = json.loads(config.read_text(encoding='utf-8'))
    except (FileNotFoundError, ValueError, OSError):
        if not (os.environ.get('OFFERTODAY_BROWSER_WORKDIR') and os.environ.get('OFFERTODAY_BROWSER_SESSION')):
            raise BrowserBlocked('Missing manual connection: run connect explicitly with your existing Chrome')
        info = {}
    if not isinstance(info, dict):
        raise BrowserBlocked('Connection metadata is invalid')
    session = os.environ.get('OFFERTODAY_BROWSER_SESSION') or info.get('session')
    declared = os.environ.get('OFFERTODAY_BROWSER_WORKDIR') or info.get('workspace')
    if not isinstance(session, str) or not SESSION.fullmatch(session) or not isinstance(declared, str) or not declared.strip():
        raise BrowserBlocked('Connection session/workspace is invalid')
    selected = Path(declared).expanduser()
    if not selected.is_absolute() or not selected.is_dir():
        raise BrowserBlocked('Connection workspace must be an existing absolute directory')
    return selected.resolve(), session


class ExistingBrowserAdapter:
    """CLI gateway; checks an attached session in its workspace before every action."""

    def __init__(self, workspace, session):
        self.workspace, self.session = Path(workspace).resolve(), session

    def _cli(self, *args, timeout=45):
        try:
            result = subprocess.run(['playwright-cli', '--json', f'-s={self.session}', *args], cwd=self.workspace,
                                    env={**os.environ, 'NO_UPDATE_NOTIFIER': '1'}, capture_output=True,
                                    text=True, timeout=timeout)
        except (OSError, subprocess.TimeoutExpired) as exc:
            raise BrowserBlocked('Playwright CLI unavailable or timed out; verify manually') from exc
        if len(result.stdout) > 300_000 or result.returncode:
            raise BrowserBlocked('Playwright CLI failed or produced excessive output')
        return result.stdout

    def require_attached(self):
        try:
            listing = json.loads(self._cli('list', timeout=20))
        except ValueError as exc:
            raise BrowserBlocked('Cannot verify the existing browser session') from exc
        browsers = listing.get('browsers') if isinstance(listing, dict) else None
        # `list` without --all is scoped by CLI to cwd. Its `workspace` is an opaque ID,
        # not a filesystem path; comparing it to our directory rejects valid attachments.
        if not isinstance(browsers, list) or not any(
                isinstance(browser, dict) and browser.get('name') == self.session
                and browser.get('status') == 'open'
                and browser.get('attached') is True and browser.get('compatible') is True for browser in browsers):
            raise BrowserBlocked('Existing Chrome extension session is not open and attached; use connect manually')

    def action(self, operation, **data):
        self.require_attached()
        template = Path(__file__).with_name('browser.js').read_text(encoding='utf-8')
        source = template.replace('__PAYLOAD__', json.dumps({'operation': operation, **data}, ensure_ascii=False))
        result = parse_browser_result(self._cli('run-code', source, timeout=90), 0)
        if result['status'] == 'blocked':
            raise BrowserBlocked(str(result.get('reason', 'Browser view is unrecognized'))[:200])
        return result


def discover(adapter):
    result = adapter.action('discover')
    cards = result.get('jobs')
    if not isinstance(cards, list) or len(cards) > 40:
        raise BrowserBlocked('Loaded jobs could not be verified')
    jobs = []
    seen = set()
    for item in cards:
        if not isinstance(item, dict):
            raise BrowserBlocked('Loaded job is incomplete')
        url = job_url(item.get('url'))
        fields = {}
        for name, maximum in [('title', 300), ('company', 200), ('description', 30000)]:
            value = item.get(name)
            if not isinstance(value, str) or len(value) > maximum or (name != 'description' and not value.strip()):
                raise BrowserBlocked('Loaded job is missing its public ' + name)
            fields[name] = value.strip()
        if url not in seen:
            jobs.append({'url': url, **fields})
            seen.add(url)
    return {'jobs': jobs, 'note': 'Only currently loaded public jobs; no automatic pagination.'}


def connect():
    workspace = Path(os.environ.get('OFFERTODAY_BROWSER_WORKDIR') or ROOT).expanduser().resolve()
    session = os.environ.get('OFFERTODAY_BROWSER_SESSION') or 'offertoday-current'
    if not SESSION.fullmatch(session):
        raise BrowserBlocked('Invalid session name')
    metadata = workspace / '.offertoday' / 'connection.json'
    if metadata.parent.is_symlink() or metadata.is_symlink():
        raise BrowserBlocked('Connection metadata path is a symlink')
    adapter = ExistingBrowserAdapter(workspace, session)
    # Explicit operator command only: replace any CLI attachment with the official Chrome extension.
    # Never use `open`, CDP, a copied profile, or a separate login browser.
    output = adapter._cli('attach', '--extension=chrome', timeout=60)
    try:
        attach = json.loads(output)
    except ValueError as exc:
        raise BrowserBlocked('Chrome extension attachment was not confirmed') from exc
    if not isinstance(attach, dict) or attach.get('isError') or attach.get('session') != session:
        raise BrowserBlocked('Chrome extension attachment failed')
    adapter.require_attached()
    metadata.parent.mkdir(mode=0o700, exist_ok=True)
    metadata.parent.chmod(0o700)
    fd = os.open(metadata, os.O_WRONLY | os.O_CREAT | os.O_TRUNC | getattr(os, 'O_NOFOLLOW', 0), 0o600)
    with os.fdopen(fd, 'w', encoding='utf-8') as stream:
        json.dump({'session': session, 'workspace': str(workspace)}, stream)
    metadata.chmod(0o600)
    return {'status': 'connected', 'session': session}


def _required(value, label):
    if not isinstance(value, str) or not value or len(value) > 6000:
        raise BrowserBlocked('Approved task is missing ' + label)
    return value


def _hash(value):
    return hashlib.sha256(value).hexdigest()


def _validate_bundle(bundle):
    if not isinstance(bundle, dict):
        raise BrowserBlocked('Malformed task bundle')
    task = bundle.get('task')
    if not isinstance(task, dict) or task.get('kind') != 'APPLICATION' or task.get('channel') != 'OFFERTODAY' or task.get('region') != 'HK' or task.get('state') != 'RUNNING':
        raise BrowserBlocked('Only one running HK OfferToday application is supported')
    target = job_url(task.get('url'))
    if target != task.get('url') or target.rsplit('/', 1)[-1] != task.get('platformJobId'):
        raise BrowserBlocked('Task does not match its canonical platform job ID')
    for key in ('taskId', 'token', 'callbackUrl', 'resumePath'):
        _required(bundle.get(key), key)
    for key in ('attemptId', 'title', 'company'):
        _required(task.get(key), key)
    if bundle.get('reporterPath') != str(ROOT / 'scripts' / 'workbench' / 'report.py'):
        raise BrowserBlocked('Task must use the existing local workbench reporter')
    snapshot = task.get('snapshot')
    if not isinstance(snapshot, dict) or snapshot.get('contactVerified') is not True or not isinstance(snapshot.get('facts'), list):
        raise BrowserBlocked('Contact/profile/facts approval is incomplete')
    message = _required(snapshot.get('initialMessage'), 'exact approved message')
    if not message.strip() or _hash(message.encode('utf-8')) != snapshot.get('initialMessageSha256'):
        raise BrowserBlocked('Exact approved message hash is missing or changed')
    resume = snapshot.get('resume')
    if not isinstance(resume, dict) or resume.get('approved') is not True:
        raise BrowserBlocked('Resume is not approved')
    filename = _required(resume.get('filename'), 'resume filename')
    if len(filename) > 200 or filename in {'.', '..'} or '/' in filename or '\\' in filename or any(ord(c) < 32 for c in filename):
        raise BrowserBlocked('Unsafe original resume filename')
    extension = Path(filename).suffix.lower()
    if extension not in ('.pdf', '.docx'):
        raise BrowserBlocked('Only an approved PDF/DOCX attachment can be sent')
    path = Path(bundle['resumePath'])
    if not path.is_absolute() or not path.is_file() or any(p.is_symlink() for p in (path, *path.parents)) or path.stat().st_size > 10 * 1024 * 1024:
        raise BrowserBlocked('Frozen resume file is unavailable or unsafe')
    data = path.read_bytes()
    if len(data) > 10 * 1024 * 1024 or _hash(data) != resume.get('sha256') or not data:
        raise BrowserBlocked('Frozen resume hash mismatch')
    if extension == '.pdf':
        valid = data.startswith(b'%PDF-') and b'%%EOF' in data[-1024:]
    else:
        try:
            with zipfile.ZipFile(io.BytesIO(data)) as doc:
                valid = {'[Content_Types].xml', 'word/document.xml'} <= set(doc.namelist())
        except (OSError, ValueError, zipfile.BadZipFile):
            valid = False
    if not valid:
        raise BrowserBlocked('Approved attachment is not a valid PDF/DOCX')
    return task, snapshot, filename, data


def _event(bundle_file, bundle, name, message, state, evidence=''):
    response = report_event(bundle_file, name, message, evidence)
    if response.get('taskId') != bundle['taskId'] or response.get('state') != state:
        raise BrowserBlocked('Workbench did not acknowledge the expected task state')


def _preflight(result, task):
    if (not isinstance(result, dict) or result.get('jobId') != task['platformJobId']
            or not isinstance(result.get('conversationId'), str) or not result['conversationId']
            or len(result['conversationId']) > 256 or not isinstance(result.get('baselineIds'), list)
            or len(result['baselineIds']) > 500
            or any(not isinstance(value, str) or not value or len(value) > 256 for value in result['baselineIds'])
            or len(result['baselineIds']) != len(set(result['baselineIds']))):
        raise BrowserBlocked('Composer/job identity could not be verified before claim')
    return result


def _receipt(value, preflight, task, snapshot, kind):
    if not isinstance(value, dict) or value.get('jobId') != task['platformJobId'] or value.get('conversationId') != preflight['conversationId']:
        raise BrowserBlocked('Receipt does not belong to this job/conversation')
    mid = value.get('messageId')
    if not isinstance(mid, str) or not mid or len(mid) > 256 or mid in preflight['baselineIds']:
        raise BrowserBlocked('No fresh self-message receipt after send')
    proof = value.get('proof')
    if not isinstance(proof, str) or not proof or len(proof) > 500:
        raise BrowserBlocked('Receipt lacks bounded proof')
    digest = 'initialMessageSha256' if kind == 'contact' else 'resumeSha256'
    expected = snapshot['initialMessageSha256'] if kind == 'contact' else snapshot['resume']['sha256']
    if value.get(digest) != expected:
        raise BrowserBlocked('Receipt does not match approved message or attachment')
    return json.dumps({'jobId': task['platformJobId'], 'attemptId': task['attemptId'],
                       'conversationId': preflight['conversationId'], 'messageId': mid,
                       digest: expected, 'proof': proof}, ensure_ascii=False)


def run(bundle_file, adapter=None):
    """One approved attempt. After any claim uncertainty, never send again or return success."""
    try:
        bundle = json.loads(Path(bundle_file).read_text(encoding='utf-8'))
    except (OSError, ValueError) as exc:
        raise BrowserBlocked('Cannot read approved task bundle') from exc
    try:
        task, snapshot, filename, data = _validate_bundle(bundle)
    except (BrowserBlocked, OSError) as exc:
        # A malformed callback cannot be reported; never proceed to the browser.
        try:
            _event(bundle_file, bundle, 'NEEDS_INPUT', str(exc), 'NEEDS_INPUT')
        except (Exception,):
            pass
        return {'status': 'NEEDS_INPUT'}
    # No browser call until the entire local authorization/material contract passed.
    claim_attempted = False
    try:
        browser = adapter or ExistingBrowserAdapter(*_connection())
        preflight = _preflight(browser.action('preflight', jobId=task['platformJobId'], title=task['title'], company=task['company'],
                                             resumeFilename=filename, resumeSha256=snapshot['resume']['sha256'], resumeSize=len(data)), task)
        _event(bundle_file, bundle, 'PROGRESS', 'Approved materials and read-only job/composer preflight verified', 'RUNNING')
        with tempfile.TemporaryDirectory(prefix='offertoday-', dir=Path(bundle_file).resolve().parent) as temp:
            staged = Path(temp) / filename
            with staged.open('xb') as stream:
                stream.write(data)
            staged.chmod(0o600)
            if _hash(staged.read_bytes()) != snapshot['resume']['sha256']:
                raise BrowserBlocked('Staged resume hash mismatch')
            claim_attempted = True
            _event(bundle_file, bundle, 'SUBMITTING', 'Claim before one OfferToday contact/CV send', 'UNKNOWN')
            outcome = browser.action('send_text', jobId=task['platformJobId'], title=task['title'], company=task['company'],
                                     preflight=preflight, message=snapshot['initialMessage'],
                                     messageSha256=snapshot['initialMessageSha256'])
            contact = outcome.get('contact') if isinstance(outcome, dict) else None
            if not contact:
                raise BrowserBlocked('No fresh self-message acknowledgement')
            contact_evidence = _receipt(contact, preflight, task, snapshot, 'contact')
            _event(bundle_file, bundle, 'CONTACTED', 'Fresh same-job approved initial message acknowledged', 'UNKNOWN', contact_evidence)
            outcome = browser.action('send_resume', jobId=task['platformJobId'], title=task['title'], company=task['company'],
                                     preflight=preflight, contactMessageId=contact['messageId'], resumePath=str(staged),
                                     resumeFilename=filename, resumeSha256=snapshot['resume']['sha256'], resumeSize=len(data))
            cv = outcome.get('cv') if isinstance(outcome, dict) else None
            if not cv:
                raise BrowserBlocked('No confirmed delivery of approved attachment')
            if not isinstance(cv, dict) or cv.get('messageId') == contact['messageId']:
                raise BrowserBlocked('Text acknowledgement is not a separate resume delivery receipt')
            cv_evidence = _receipt(cv, preflight, task, snapshot, 'cv')
            _event(bundle_file, bundle, 'CV_DELIVERED', 'Fresh same-conversation approved CV delivery acknowledged', 'SUBMITTED', cv_evidence)
            return {'status': 'SUBMITTED'}
    except (BrowserBlocked, OSError, ValueError, KeyError) as exc:
        if claim_attempted:
            try:
                _event(bundle_file, bundle, 'PROGRESS', 'Send/receipt uncertain; retain UNKNOWN; never retry', 'UNKNOWN')
            except (Exception,):
                pass
            return {'status': 'UNKNOWN'}
        try:
            _event(bundle_file, bundle, 'NEEDS_INPUT', str(exc), 'NEEDS_INPUT')
        except (Exception,):
            pass
        return {'status': 'NEEDS_INPUT'}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='command', required=True)
    sub.add_parser('connect')
    sub.add_parser('discover')
    run_parser = sub.add_parser('run')
    run_parser.add_argument('taskFile', type=Path)
    args = parser.parse_args(argv)
    try:
        if args.command == 'connect':
            result = connect()
        elif args.command == 'discover':
            result = discover(ExistingBrowserAdapter(*_connection()))
        else:
            result = run(args.taskFile)
        print(json.dumps(result, ensure_ascii=False))
        return 0
    except (BrowserBlocked, OSError) as exc:
        print('NEEDS_INPUT: ' + str(exc)[:200], file=sys.stderr)
        return 2


if __name__ == '__main__':
    sys.exit(main())
