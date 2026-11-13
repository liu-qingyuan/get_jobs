"""Focused tests for the script Interface; no real account/session is used."""
import hashlib
from http.server import BaseHTTPRequestHandler, HTTPServer
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import unittest
from unittest.mock import patch

from offertoday import BrowserBlocked, ExistingBrowserAdapter, discover, job_url, main, parse_browser_result, run


class BrowserResultTest(unittest.TestCase):
    def test_error_section_even_with_exit_zero_is_not_a_success(self):
        with self.assertRaises(BrowserBlocked):
            parse_browser_result('### Error\nTimeout waiting for browser\n', 0)
        self.assertEqual({'status': 'ok', 'jobs': []},
                         parse_browser_result('### Result\n{"status":"ok","jobs":[]}\n', 0))
        self.assertEqual({'status': 'ok', 'jobs': []},
                         parse_browser_result(json.dumps({'result': json.dumps({'status': 'ok', 'jobs': []})}), 0))
        with self.assertRaises(BrowserBlocked):
            parse_browser_result('{"isError":true,"error":"script failed"}', 0)


class DiscoveryCLITest(unittest.TestCase):
    def test_current_workspace_listing_uses_an_opaque_id_not_a_directory(self):
        with tempfile.TemporaryDirectory() as directory:
            browser = ExistingBrowserAdapter(Path(directory), 'fixture-only')
            listing = {'browsers': [{'name': 'fixture-only', 'workspace': '0123456789abcdef', 'status': 'open',
                                     'attached': True, 'compatible': True}]}
            with patch.object(browser, '_cli', return_value=json.dumps(listing)) as cli:
                browser.require_attached()
                cli.assert_called_once_with('list', timeout=20)

    def test_incomplete_job_or_untrusted_host_is_not_discovery_success(self):
        class Browser:
            def __init__(self, item):
                self.item = item
            def action(self, operation):
                self.operation = operation
                return {'jobs': [self.item]}
        item = {'url': 'https://www.offertoday.com/hk/job/AbCdEfGhIjKlMnOpQrStUv==',
                'title': 'Fixture engineer', 'company': '', 'description': ''}
        with self.assertRaises(BrowserBlocked):
            discover(Browser(item))
        item['company'] = 'Fixture co'
        item['description'] = 'Public description'
        item['url'] = 'https://www.offertoday.com.evil.test/hk/job/AbCdEfGhIjKlMnOpQrStUv=='
        with self.assertRaises(BrowserBlocked):
            discover(Browser(item))
        self.assertEqual('https://www.offertoday.com/hk/job/AbCdEfGhIjKlMnOpQrStUv==',
                         job_url('https://www.offertoday.com/hk/job/AbCdEfGhIjKlMnOpQrStUv==?ref=test'))


    def test_loaded_public_cards_need_not_contain_the_full_job_description(self):
        item = {'url': 'https://www.offertoday.com/hk/job/AbCdEfGhIjKlMnOpQrStUv==',
                'title': 'Fixture engineer', 'company': 'Fixture co', 'description': ''}
        class Browser:
            def action(self, operation):
                return {'jobs': [item]}
        self.assertEqual([item], discover(Browser())['jobs'])

    def test_private_connection_can_reuse_a_browser_already_attached_in_another_workspace(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            existing = root / 'existing-cli-workspace'
            existing.mkdir()
            (root / '.offertoday').mkdir()
            (root / '.offertoday' / 'connection.json').write_text(json.dumps({'session': 'fixture-only', 'workspace': str(existing)}))
            with patch('offertoday.ROOT', root), patch.dict(os.environ, {'OFFERTODAY_BROWSER_WORKDIR': '', 'OFFERTODAY_BROWSER_SESSION': ''}), \
                    patch('offertoday.ExistingBrowserAdapter') as browser, patch('builtins.print'):
                browser.return_value.action.return_value = {'jobs': []}
                self.assertEqual(0, main(['discover']))
                browser.assert_called_once_with(existing, 'fixture-only')

    def test_bounded_candidate_command_uses_only_read_operation_and_writes_result_file(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); task = root / 'read.json'; result = root / 'result.json'
            task.write_text(json.dumps({'kind':'CANDIDATE_READ','source':{'channel':'OFFERTODAY',
                'url':'https://www.offertoday.com/hk/search?q=fixture','cursor':''},
                'limits':{'candidates':2,'seconds':10,'modelTokens':0,'outbound':0},'resultPath':str(result)}))
            with patch('offertoday._connection', return_value=(root, 'fixture-only')), patch('offertoday.ExistingBrowserAdapter') as browser, patch('builtins.print'):
                browser.return_value.action.return_value = {'jobs':[], 'cursor':'', 'nextUrl':''}
                self.assertEqual(0, main(['read-candidates', str(task)]))
                browser.return_value.action.assert_called_once_with('candidate_page', sourceUrl='https://www.offertoday.com/hk/search?q=fixture', maxJobs=2, cursor='')
                self.assertEqual([], json.loads(result.read_text())['jobs'])

    def test_connect_explicitly_attaches_extension_and_writes_private_metadata(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            cli = root / 'playwright-cli'
            cli.write_text('#!/usr/bin/env python3\nimport json, os, sys\n'
                           'with open(os.environ["CLI_LOG"], "a") as f: f.write(json.dumps(sys.argv[1:]) + "\\n")\n'
                           'if "list" in sys.argv: print(json.dumps({"browsers":[{"name":"fixture-only",'
                           '"workspace":os.environ["OFFERTODAY_BROWSER_WORKDIR"],"status":"open",'
                           '"attached":True,"compatible":True}]}))\n'
                           'else: print(json.dumps({"session":"fixture-only"}))\n')
            cli.chmod(0o700)
            log = root / 'commands.jsonl'
            env = {**os.environ, 'PATH': f'{root}:{os.environ["PATH"]}', 'CLI_LOG': str(log),
                   'OFFERTODAY_BROWSER_WORKDIR': str(root), 'OFFERTODAY_BROWSER_SESSION': 'fixture-only'}
            result = subprocess.run([sys.executable, str(Path(__file__).with_name('offertoday.py')), 'connect'],
                                    env=env, text=True, capture_output=True, timeout=10)
            self.assertEqual(0, result.returncode, result.stderr)
            calls = [json.loads(line) for line in log.read_text().splitlines()]
            self.assertEqual(1, sum('attach' in call and '--extension=chrome' in call for call in calls))
            self.assertFalse(any('open' in call or '--cdp=chrome' in call for call in calls))
            metadata = root / '.offertoday' / 'connection.json'
            self.assertEqual({'session': 'fixture-only', 'workspace': str(root)}, json.loads(metadata.read_text()))
            self.assertEqual(0o600, metadata.stat().st_mode & 0o777)

    def test_uses_only_attached_session_and_publishes_canonical_loaded_jobs(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            (root / '.offertoday').mkdir()
            (root / '.offertoday' / 'connection.json').write_text(json.dumps({'session': 'fixture-only', 'workspace': str(root)}))
            (root / 'playwright-cli').write_text(
                '#!/usr/bin/env python3\n'
                'import json, os, sys\n'
                'with open(os.environ["CLI_LOG"], "a") as f: f.write(json.dumps(sys.argv[1:]) + "\\n")\n'
                'if "list" in sys.argv:\n'
                ' print(json.dumps({"browsers":[{"name":"fixture-only", "workspace":os.environ["OFFERTODAY_BROWSER_WORKDIR"],'
                ' "status":"open", "attached":True, "compatible":True}]}))\n'
                'elif "run-code" in sys.argv:\n'
                ' print(json.dumps({"status":"ok","jobs":[{"url":"https://www.offertoday.com/hk/job/AbCdEfGhIjKlMnOpQrStUv==?from=recommend",'
                ' "title":"Engineer", "company":"Fixture Company", "description":"Public job description"}]}))\n'
            )
            (root / 'playwright-cli').chmod(0o700)
            log = root / 'commands.jsonl'
            env = {**os.environ, 'PATH': f'{root}:{os.environ["PATH"]}', 'CLI_LOG': str(log),
                   'OFFERTODAY_BROWSER_WORKDIR': str(root), 'OFFERTODAY_BROWSER_SESSION': 'fixture-only',
                   'NO_UPDATE_NOTIFIER': '1'}
            result = subprocess.run([sys.executable, str(Path(__file__).with_name('offertoday.py')), 'discover'],
                                    env=env, text=True, capture_output=True, timeout=10)
            self.assertEqual(0, result.returncode, result.stderr + (' CALLS=' + log.read_text() if log.exists() else ''))
            response = json.loads(result.stdout)
            self.assertEqual([{'url': 'https://www.offertoday.com/hk/job/AbCdEfGhIjKlMnOpQrStUv==',
                               'title': 'Engineer', 'company': 'Fixture Company', 'description': 'Public job description'}],
                             response['jobs'])
            self.assertIn('loaded', response['note'].lower())
            calls = [json.loads(line) for line in log.read_text().splitlines()]
            self.assertEqual(['list', 'run-code'], [next(part for part in call if part in ('list', 'run-code')) for call in calls])
            self.assertTrue(all('attach' not in call and 'open' not in call for call in calls))


class ApplicationTest(unittest.TestCase):
    def test_hash_mismatch_blocks_without_touching_browser_or_claiming(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            resume = root / 'frozen.pdf'
            resume.write_bytes(b'%PDF-1.4\nfixture\n%%EOF')
            message = 'Exact approved introduction.'
            events = []
            class Handler(BaseHTTPRequestHandler):
                def do_POST(self):
                    payload = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
                    events.append(payload)
                    data = json.dumps({'id': 'task-fixture', 'state': payload['type']}).encode()
                    self.send_response(200); self.end_headers(); self.wfile.write(data)
                def log_message(self, *args):
                    pass
            with HTTPServer(('127.0.0.1', 0), Handler) as server:
                thread = threading.Thread(target=server.serve_forever, daemon=True); thread.start()
                try:
                    taskfile = root / 'task.json'
                    taskfile.write_text(json.dumps({
                        'taskId': 'task-fixture', 'token': 'fixture-only',
                        'callbackUrl': f'http://127.0.0.1:{server.server_port}/task',
                        'reporterPath': str(Path(__file__).resolve().parents[1] / 'workbench' / 'report.py'),
                        'resumePath': str(resume),
                        'task': {'kind': 'APPLICATION', 'channel': 'OFFERTODAY', 'region': 'HK', 'state': 'RUNNING',
                                 'platformJobId': 'AbCdEfGhIjKlMnOpQrStUv==', 'attemptId': 'attempt-fixture',
                                 'url': 'https://www.offertoday.com/hk/job/AbCdEfGhIjKlMnOpQrStUv==',
                                 'title': 'Fixture engineer', 'company': 'Fixture co',
                                 'snapshot': {'resume': {'approved': True, 'filename': 'frozen.pdf',
                                                         'sha256': '0' * 64},
                                              'initialMessage': message, 'initialMessageSha256': hashlib.sha256(message.encode()).hexdigest(),
                                              'contactVerified': True, 'facts': []}}}))
                    class FakeBrowser:
                        def action(self, *args, **kwargs):
                            self.fail('Invalid file should never reach browser')
                    self.assertEqual({'status': 'NEEDS_INPUT'}, run(taskfile, FakeBrowser()))
                    self.assertEqual(['NEEDS_INPUT'], [event['type'] for event in events])
                finally:
                    server.shutdown(); thread.join()


class ReceiptTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name).resolve()
        self.data = b'%PDF-1.4\nfixture\n%%EOF'
        self.sha = hashlib.sha256(self.data).hexdigest()
        self.message = 'Exact approved introduction.'
        self.message_sha = hashlib.sha256(self.message.encode()).hexdigest()
        resume = self.root / 'sha-name.pdf'
        resume.write_bytes(self.data)
        self.events = []
        events = self.events
        class Handler(BaseHTTPRequestHandler):
            def do_POST(self):
                payload = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
                events.append(payload)
                state = {'SUBMITTING': getattr(outer, 'claim_response', 'UNKNOWN'), 'CONTACTED': getattr(outer, 'contact_response', 'UNKNOWN'), 'CV_DELIVERED': 'SUBMITTED',
                         'PROGRESS': 'UNKNOWN' if 'SUBMITTING' in [event['type'] for event in events] else 'RUNNING',
                         'NEEDS_INPUT': 'NEEDS_INPUT'}[payload['type']]
                data = json.dumps({'id': 'task-fixture', 'state': state}).encode()
                self.send_response(200); self.end_headers(); self.wfile.write(data)
            def log_message(self, *args):
                pass
        outer = self
        self.server = HTTPServer(('127.0.0.1', 0), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True); self.thread.start()
        self.bundle = self.root / 'task.json'
        self.bundle.write_text(json.dumps({
            'taskId': 'task-fixture', 'token': 'fixture-only',
            'callbackUrl': f'http://127.0.0.1:{self.server.server_port}/task',
            'reporterPath': str(Path(__file__).resolve().parents[1] / 'workbench' / 'report.py'),
            'resumePath': str(resume),
            'task': {'kind': 'APPLICATION', 'channel': 'OFFERTODAY', 'region': 'HK', 'state': 'RUNNING',
                     'platformJobId': 'AbCdEfGhIjKlMnOpQrStUv==', 'attemptId': 'attempt-fixture',
                     'url': 'https://www.offertoday.com/hk/job/AbCdEfGhIjKlMnOpQrStUv==',
                     'title': 'Fixture engineer', 'company': 'Fixture co',
                     'snapshot': {'resume': {'approved': True, 'filename': 'approved.pdf', 'sha256': self.sha},
                                  'initialMessage': self.message, 'initialMessageSha256': self.message_sha,
                                  'contactVerified': True, 'facts': []}}}))

    def tearDown(self):
        self.server.shutdown(); self.thread.join(); self.server.server_close(); self.tmp.cleanup()

    def test_unapproved_or_unsafe_materials_block_before_browser(self):
        original = json.loads(self.bundle.read_text())
        unsafe_path = self.root / 'unsafe-alias.pdf'
        unsafe_path.symlink_to(self.root / 'sha-name.pdf')
        changes = [
            ('pdf renamed to docx', lambda b: b['task']['snapshot']['resume'].update(filename='approved.docx')),
            ('text filename', lambda b: b['task']['snapshot']['resume'].update(filename='approved.txt')),
            ('symlink', lambda b: b.update(resumePath=str(unsafe_path))),
            ('untrusted host', lambda b: b['task'].update(url='https://www.offertoday.com.evil.test/hk/job/AbCdEfGhIjKlMnOpQrStUv==')),
            ('unapproved', lambda b: b['task']['snapshot']['resume'].update(approved=False)),
            ('changed message', lambda b: b['task']['snapshot'].update(initialMessageSha256='0' * 64)),
            ('contact not verified', lambda b: b['task']['snapshot'].update(contactVerified=False)),
        ]
        class NeverBrowser:
            def action(self, *args, **kwargs):
                raise AssertionError('Invalid approval/material must not reach browser')
        for name, change in changes:
            with self.subTest(name=name):
                self.events.clear()
                bundle = json.loads(json.dumps(original))
                change(bundle)
                self.bundle.write_text(json.dumps(bundle))
                self.assertEqual({'status': 'NEEDS_INPUT'}, run(self.bundle, NeverBrowser()))
                self.assertEqual(['NEEDS_INPUT'], [event['type'] for event in self.events])

    def test_missing_manual_connection_reports_needs_input_before_any_claim(self):
        with patch.dict(os.environ, {'OFFERTODAY_BROWSER_WORKDIR': str(self.root)}, clear=False):
            os.environ.pop('OFFERTODAY_BROWSER_SESSION', None)
            self.assertEqual({'status': 'NEEDS_INPUT'}, run(self.bundle))
        self.assertEqual(['NEEDS_INPUT'], [event['type'] for event in self.events])

    def test_claim_without_exact_unknown_ack_does_not_send_or_retry(self):
        self.claim_response = 'RUNNING'
        class Browser:
            calls = []
            def action(self, op, **values):
                self.calls.append(op)
                if op == 'preflight':
                    return {'jobId': 'AbCdEfGhIjKlMnOpQrStUv==', 'conversationId': 'conversation-fixture', 'baselineIds': []}
                raise AssertionError('No send permitted without UNKNOWN acknowledgement')
        browser = Browser()
        self.assertEqual({'status': 'UNKNOWN'}, run(self.bundle, browser))
        self.assertEqual(['preflight'], browser.calls)
        self.assertEqual(['PROGRESS', 'SUBMITTING', 'PROGRESS'], [event['type'] for event in self.events])

    def test_browser_uncertainty_after_claim_reports_progress_and_does_not_retry(self):
        class Browser:
            calls = []
            def action(self, op, **values):
                self.calls.append(op)
                if op == 'preflight':
                    return {'jobId': 'AbCdEfGhIjKlMnOpQrStUv==', 'conversationId': 'conversation-fixture', 'baselineIds': []}
                raise BrowserBlocked('Browser result unknown after one click')
        browser = Browser()
        self.assertEqual({'status': 'UNKNOWN'}, run(self.bundle, browser))
        self.assertEqual(['preflight', 'send_text'], browser.calls)
        self.assertEqual(['PROGRESS', 'SUBMITTING', 'PROGRESS'], [event['type'] for event in self.events])

    def test_old_message_or_wrong_cv_hash_never_becomes_submitted(self):
        for message_id, cv_hash, expected in [('old-message', self.sha, ['PROGRESS', 'SUBMITTING', 'PROGRESS']),
                                               ('fresh-message', '0' * 64, ['PROGRESS', 'SUBMITTING', 'CONTACTED', 'PROGRESS'])]:
            with self.subTest(message_id=message_id, cv_hash=cv_hash):
                self.events.clear()
                class Browser:
                    calls = []
                    def action(inner, op, **values):
                        inner.calls.append(op)
                        if op == 'preflight':
                            return {'jobId': 'AbCdEfGhIjKlMnOpQrStUv==', 'conversationId': 'conversation-fixture', 'baselineIds': ['old-message']}
                        contact = {'jobId': 'AbCdEfGhIjKlMnOpQrStUv==', 'conversationId': 'conversation-fixture',
                                   'messageId': message_id, 'initialMessageSha256': self.message_sha, 'proof': 'new self-message'}
                        return {'contact': contact, 'cv': {'jobId': contact['jobId'], 'conversationId': contact['conversationId'],
                                'messageId': 'new-resume-' + message_id, 'resumeSha256': cv_hash, 'proof': 'attached CV receipt'}}
                browser = Browser()
                self.assertEqual({'status': 'UNKNOWN'}, run(self.bundle, browser))
                self.assertEqual(['preflight', 'send_text'] + (['send_resume'] if message_id != 'old-message' else []), browser.calls)
                self.assertEqual(expected, [event['type'] for event in self.events])

    def test_readonly_preflight_receives_frozen_attachment_identity_without_local_path(self):
        class Browser:
            before = None
            def action(self, op, **values):
                if op == 'preflight':
                    self.before = values
                    return {'jobId': 'AbCdEfGhIjKlMnOpQrStUv==', 'conversationId': 'conversation-fixture', 'baselineIds': []}
                raise BrowserBlocked('Controlled stop before any fixture send')
        browser = Browser()
        run(self.bundle, browser)
        self.assertEqual('approved.pdf', browser.before.get('resumeFilename'))
        self.assertEqual(self.sha, browser.before.get('resumeSha256'))
        self.assertEqual(len(self.data), browser.before.get('resumeSize'))
        self.assertNotIn('resumePath', browser.before)
        self.assertNotIn('token', browser.before)
        self.assertNotIn('callbackUrl', browser.before)

    def test_contact_is_durably_reported_before_the_separate_resume_step_can_fail(self):
        events, message_sha = self.events, self.message_sha
        class Browser:
            calls = []
            def action(self, op, **values):
                self.calls.append(op)
                if op == 'preflight':
                    return {'jobId': 'AbCdEfGhIjKlMnOpQrStUv==', 'conversationId': 'conversation-fixture', 'baselineIds': []}
                if op == 'send_text':
                    return {'contact': {'jobId': 'AbCdEfGhIjKlMnOpQrStUv==', 'conversationId': 'conversation-fixture',
                                        'messageId': 'new-text-message', 'initialMessageSha256': message_sha,
                                        'proof': 'Persisted approved text'}}
                if op == 'send_resume':
                    assert any(event['type'] == 'CONTACTED' for event in events), 'Do not upload before contact receipt is acknowledged'
                raise BrowserBlocked('Resume picker failed after text delivery')
        browser = Browser()
        self.assertEqual({'status': 'UNKNOWN'}, run(self.bundle, browser))
        self.assertEqual(['PROGRESS', 'SUBMITTING', 'CONTACTED', 'PROGRESS'], [event['type'] for event in self.events])
        self.assertEqual(['preflight', 'send_text', 'send_resume'], browser.calls)

    def test_unacknowledged_contact_receipt_stops_before_any_resume_action(self):
        self.contact_response = 'RUNNING'
        message_sha = self.message_sha
        class Browser:
            calls = []
            def action(self, op, **values):
                self.calls.append(op)
                if op == 'preflight':
                    return {'jobId': 'AbCdEfGhIjKlMnOpQrStUv==', 'conversationId': 'conversation-fixture', 'baselineIds': []}
                if op == 'send_text':
                    return {'contact': {'jobId': 'AbCdEfGhIjKlMnOpQrStUv==', 'conversationId': 'conversation-fixture',
                                        'messageId': 'fresh-text', 'initialMessageSha256': message_sha,
                                        'proof': 'Persisted text, callback not acknowledged'}}
                raise AssertionError('No attachment action after a rejected contact callback')
        browser = Browser()
        self.assertEqual({'status': 'UNKNOWN'}, run(self.bundle, browser))
        self.assertEqual(['preflight', 'send_text'], browser.calls)
        self.assertFalse(any(event['type'] == 'CV_DELIVERED' for event in self.events))

    def test_text_message_id_cannot_be_reused_as_a_resume_receipt(self):
        message_sha, resume_sha = self.message_sha, self.sha
        class Browser:
            def action(self, op, **values):
                if op == 'preflight':
                    return {'jobId': 'AbCdEfGhIjKlMnOpQrStUv==', 'conversationId': 'conversation-fixture', 'baselineIds': []}
                identity = {'jobId': 'AbCdEfGhIjKlMnOpQrStUv==', 'conversationId': 'conversation-fixture',
                            'messageId': 'text-only-message', 'proof': 'Same text acknowledgement reused'}
                return {'contact': dict(identity, initialMessageSha256=message_sha),
                        'cv': dict(identity, resumeSha256=resume_sha)}
        self.assertEqual({'status': 'UNKNOWN'}, run(self.bundle, Browser()))
        self.assertEqual(['PROGRESS', 'SUBMITTING', 'CONTACTED', 'PROGRESS'], [event['type'] for event in self.events])

    def test_contact_and_cv_can_be_separate_new_messages_in_the_same_conversation(self):
        message_sha, resume_sha = self.message_sha, self.sha
        class Browser:
            def action(self, op, **values):
                if op == 'preflight':
                    return {'jobId': 'AbCdEfGhIjKlMnOpQrStUv==', 'conversationId': 'conversation-fixture', 'baselineIds': ['old-message']}
                return {'contact': {'jobId': 'AbCdEfGhIjKlMnOpQrStUv==', 'conversationId': 'conversation-fixture',
                                    'messageId': 'new-text-message', 'initialMessageSha256': message_sha,
                                    'proof': 'New approved text acknowledged'},
                        'cv': {'jobId': 'AbCdEfGhIjKlMnOpQrStUv==', 'conversationId': 'conversation-fixture',
                               'messageId': 'new-resume-message', 'resumeSha256': resume_sha,
                               'proof': 'Separate approved resume acknowledged'}}
        self.assertEqual({'status': 'SUBMITTED'}, run(self.bundle, Browser()))
        self.assertEqual(['PROGRESS', 'SUBMITTING', 'CONTACTED', 'CV_DELIVERED'], [event['type'] for event in self.events])
        self.assertNotEqual(json.loads(self.events[2]['evidence'])['messageId'], json.loads(self.events[3]['evidence'])['messageId'])

    def test_only_contact_receipt_never_counts_as_cv_delivery(self):
        class Browser:
            calls = []
            def action(self, op, **values):
                self.calls.append(op)
                if op == 'preflight':
                    return {'jobId': 'AbCdEfGhIjKlMnOpQrStUv==', 'conversationId': 'conversation-fixture', 'baselineIds': ['old-message']}
                if op == 'send_resume':
                    assert Path(values['resumePath']).name == 'approved.pdf'
                    assert hashlib.sha256(Path(values['resumePath']).read_bytes()).hexdigest() == values['resumeSha256']
                    return {'cv': None}
                assert op == 'send_text'
                return {'contact': {'jobId': 'AbCdEfGhIjKlMnOpQrStUv==', 'conversationId': 'conversation-fixture',
                                    'messageId': 'fresh-message', 'initialMessageSha256': self.message_sha,
                                    'proof': 'fresh self-message in approved conversation'}, 'cv': None}
        browser = Browser()
        browser.message_sha = self.message_sha
        self.assertEqual({'status': 'UNKNOWN'}, run(self.bundle, browser))
        self.assertEqual(['preflight', 'send_text', 'send_resume'], browser.calls)
        self.assertEqual(['PROGRESS', 'SUBMITTING', 'CONTACTED', 'PROGRESS'], [event['type'] for event in self.events])
        evidence = json.loads(self.events[2]['evidence'])
        self.assertEqual('attempt-fixture', evidence['attemptId'])
        self.assertEqual(self.message_sha, evidence['initialMessageSha256'])


if __name__ == '__main__':
    unittest.main()
