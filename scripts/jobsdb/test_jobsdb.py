import io
import os
import signal
import sqlite3
import subprocess
import tempfile
import unittest
from contextlib import contextmanager
from pathlib import Path
import jobsdb

class StoreTests(unittest.TestCase):
    def test_legacy_ledger_and_unknown_survive_reopen(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / 'applications.db'
            with sqlite3.connect(path) as db:
                db.execute('CREATE TABLE applications(job_id TEXT PRIMARY KEY,status TEXT NOT NULL,updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP)')
                db.execute("INSERT INTO applications(job_id,status) VALUES('123','SUBMITTED')")
            with jobsdb.Store(path) as store:
                self.assertTrue(store.contains('123'))
                self.assertFalse(store.claim('123'))
                self.assertTrue(store.claim('456'))
            with jobsdb.Store(path) as store:
                self.assertFalse(store.claim('456'))
                self.assertIn('456\tUNKNOWN', '\n'.join(store.history()))

    def test_controlled_claim_preserves_first_attempt_on_restart(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / 'applications.db'
            with jobsdb.Store(path) as store:
                self.assertTrue(store.claim('123', 'attempt-1', 'workbench'))
            with jobsdb.Store(path) as store:
                self.assertFalse(store.claim('123', 'attempt-2', 'cli'))
                prior = store.attempt('123')
                self.assertEqual(('UNKNOWN', 'attempt-1', 'workbench'),
                                 (prior['status'], prior['attemptId'], prior['source']))
                self.assertTrue(prior['claimedAt'])

    def test_provenance_failure_rolls_back_the_native_claim(self):
        with tempfile.TemporaryDirectory() as tmp, jobsdb.Store(Path(tmp)/'applications.db') as store:
            store.claim('100', 'first', 'cli')
            store.db.execute("""CREATE TRIGGER reject_provenance BEFORE INSERT ON claim_provenance
                                BEGIN SELECT RAISE(ABORT, 'fixture storage failure'); END""")
            with self.assertRaises(sqlite3.IntegrityError):
                store.claim('101', 'second', 'workbench')
            self.assertFalse(store.contains('101'))
            self.assertEqual('first', store.attempt('100')['attemptId'])

    def test_versioned_history_exact_lookup_includes_rows_outside_display_window(self):
        with tempfile.TemporaryDirectory() as tmp:
            data = Path(tmp)
            self.assertEqual({'schemaVersion': 1, 'attempts': []}, jobsdb.history_json(data))
            self.assertFalse((data/'applications.db').exists())
            with jobsdb.Store(data/'applications.db') as store:
                store.claim('1', 'first', 'cli')
                store.db.execute("UPDATE applications SET updated_at='2000-01-01' WHERE job_id='1'")
                for job in range(2, 102):
                    store.claim(str(job))
            self.assertEqual(100, len(jobsdb.history_json(data)['attempts']))
            prior, = jobsdb.history_json(data, '1')['attempts']
            self.assertEqual(('UNKNOWN', 'cli', 'first'), (prior['status'], prior['provenance'], prior['attemptId']))

    def test_competing_claim_and_invalid_finish(self):
        with tempfile.TemporaryDirectory() as tmp:
            path=Path(tmp)/'db'
            with jobsdb.Store(path) as a, jobsdb.Store(path) as b:
                self.assertTrue(a.claim('123'))
                self.assertFalse(b.claim('123'))
                with self.assertRaises(ValueError): a.finish('123','REVIEW')
                with self.assertRaises(ValueError): a.finish('999','SUBMITTED')
                a.finish('123','SUBMITTED')
                self.assertIn('SUBMITTED','\n'.join(b.history()))
    def test_nonempty_authorization_alone_never_grants_worker_permission(self):
        import hashlib, json
        with tempfile.TemporaryDirectory() as tmp:
            data = Path(tmp); resume = data/'cv.docx'; resume.write_bytes(b'fixture')
            task = {'schemaVersion':1, 'kind':'JOBSDB_SINGLE_APPLY', 'attemptId':'unconfirmed', 'jobId':'123', 'authorizationId':'not-empty',
                    'url':'https://hk.jobsdb.com/job/123', 'resumePath':str(resume),
                    'resumeSha256':hashlib.sha256(resume.read_bytes()).hexdigest(), 'answers':{},
                    'answersSha256':hashlib.sha256(b'{}').hexdigest(), 'resultPath':str(data/'result.json')}
            class NoPermissionBrowser:
                def run(self, *_): raise AssertionError('Unconfirmed worker must never open browser')
            with jobsdb.Store(data/'applications.db') as store:
                self.assertEqual(2, jobsdb.run_single(task, NoPermissionBrowser(), store, data))
                self.assertFalse(store.contains('123'))
            self.assertEqual('STOPPED_BEFORE_EFFECT', json.loads((data/'result.json').read_text())['status'])

    def test_cli_unknown_skip_never_starts_browser(self):
        from unittest.mock import patch
        with tempfile.TemporaryDirectory() as tmp:
            data=Path(tmp)
            with jobsdb.Store(data/'applications.db') as store: store.claim('123')
            with patch.object(jobsdb,'DATA',data), patch.object(jobsdb,'Browser',side_effect=AssertionError('must not start')):
                self.assertEqual(0,jobsdb.main(['apply','https://hk.jobsdb.com/job/123']))

class UrlTests(unittest.TestCase):
    def test_strict_jobsdb_url(self):
        self.assertEqual('123', jobsdb.job_id('https://hk.jobsdb.com/job/123?ref=x'))
        for url in ('http://hk.jobsdb.com/job/123', 'https://evil.test/job/123',
                    'https://hk.jobsdb.com:444/job/123', 'https://u@hk.jobsdb.com/job/123',
                    'https://hk.jobsdb.com/job/abc', 'https://hk.jobsdb.com/job/123/apply'):
            with self.assertRaises(ValueError): jobsdb.job_id(url)


class ResumeSupervisorTests(unittest.TestCase):
    def test_sigterm_reaps_the_isolated_resume_worker_group(self):
        from unittest.mock import patch

        class Worker:
            pid = 424242
            waits = 0

            def wait(self, timeout=None):
                if timeout is None:
                    return -signal.SIGKILL
                self.waits += 1
                if self.waits == 1:
                    os.kill(os.getpid(), signal.SIGTERM)
                raise subprocess.TimeoutExpired('resume-worker', timeout)

        worker = Worker()
        with patch.object(jobsdb.subprocess, 'Popen', return_value=worker) as launch, \
                patch.object(jobsdb.os, 'killpg') as kill_group:
            self.assertEqual(130, jobsdb.supervise_resume('/private/task.json', '/private/data', grace_seconds=0))

        self.assertEqual([signal.SIGTERM, signal.SIGKILL], [call.args[1] for call in kill_group.call_args_list])
        options = launch.call_args.kwargs
        self.assertTrue(options['start_new_session'])
        self.assertEqual('1', options['env'][jobsdb._RESUME_WORKER])


class BrowserTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        from patchright.sync_api import sync_playwright
        cls.pw = sync_playwright().start()
        cls.browser = cls.pw.chromium.launch(channel='chrome', headless=True)
    @classmethod
    def tearDownClass(cls):
        cls.browser.close(); cls.pw.stop()
    def setUp(self):
        self.context = self.browser.new_context()
        self.context.route('**/*', self.route)
        self.page = self.context.new_page()
        self.page.set_default_timeout(1500)
        self.page.set_default_navigation_timeout(3000)
        self.url = 'https://hk.jobsdb.com/job/12345678'
        self.detail = '<li class="nav-figure">Qingyuan Liu</li><h1>Engineer</h1><div data-automation="jobAdDetails">Engineer JD</div><a href="/job/12345678/apply/start">Quick apply</a>'
        self.requirements = '''<fieldset><legend>Which right to work?</legend>
        <label><input type="radio" name="questionnaire.HK_Q_3217_V_4" value="a" required> IANG</label>
        <label><input type="radio" name="questionnaire.HK_Q_3217_V_4" value="b"> Citizen</label></fieldset>
        <label>How many years?<select name="questionnaire.HK_Q_D3FEB" required><option value="">Choose</option><option>No experience</option><option>1 year</option></select></label>
        <button onclick="location.href='/job/12345678/apply/review'">Continue</button>'''
        self.start = '<button onclick="location.href=\'/job/12345678/apply/review\'">Continue</button>'
        self.success = '<h1>Your application has been submitted</h1>'
        self.review_document = ''
        self.uploads = self.submissions = self.external = 0
    def tearDown(self): self.context.close()
    def route(self, route):
        from urllib.parse import urlsplit
        url = route.request.url
        path = urlsplit(url).path
        if not url.startswith('https://hk.jobsdb.com/'):
            self.external += 1; route.fulfill(body='External'); return
        if path == '/fixture-upload':
            self.uploads += 1
            route.fulfill(body='fixture upload only')
            return
        if path.endswith('/success'):
            self.submissions += 1; body = self.success
        elif path.endswith('/apply/role-requirements'):
            body = self.requirements
        elif path.endswith('/review'):
            body = self.review_document + '<p>Review and submit</p><button onclick="location.href=\'/job/12345678/apply/success\'">Submit application</button>'
        elif path.endswith('/profile'): body = self.profile
        elif path.endswith('/start'): body = self.start
        else: body = self.detail
        route.fulfill(content_type='text/html', body=body)
    def flow(self):
        self.page.goto(self.url)
        return jobsdb.Flow(self.page,self.url,lambda p: None)
    def test_observer_reads_same_job_questions_and_context_without_submitting(self):
        reader = jobsdb.Browser.__new__(jobsdb.Browser)
        reader.solve = lambda p: None
        reader.observed_account = None
        task = {'url': self.url, 'description': 'Engineer JD'}
        self.page.goto(self.url)
        requirements = reader.observe_role_requirements(self.page, task)
        self.assertTrue(requirements['complete'])
        self.assertEqual('12345678', requirements['jobId'])
        by_name = {q['name']: q for q in requirements['questions']}
        self.assertEqual('radio', by_name['questionnaire.HK_Q_3217_V_4']['type'])
        self.assertEqual('Which right to work?', by_name['questionnaire.HK_Q_3217_V_4']['label'])
        self.assertEqual(['IANG', 'Citizen'], by_name['questionnaire.HK_Q_3217_V_4']['options'])
        self.assertEqual('select', by_name['questionnaire.HK_Q_D3FEB']['type'])
        self.assertEqual(['No experience', '1 year'], by_name['questionnaire.HK_Q_D3FEB']['options'])
        self.assertEqual('How many years?', by_name['questionnaire.HK_Q_D3FEB']['label'])
        task['jdSha256'] = 'e'*64
        context = reader.observe_execution_context(self.page, task)
        import hashlib
        self.assertEqual(hashlib.sha256(b'Engineer JD').hexdigest(), context['jdSha256'])
        self.assertRegex(context['jdSha256'], r'^[a-f0-9]{64}$')
        self.assertRegex(context['accountHash'], r'^[a-f0-9]{64}$')
        self.assertRegex(context['sessionHash'], r'^[a-f0-9]{64}$')
        self.assertEqual(0, self.submissions)

    def test_observer_stops_when_questions_require_entering_application(self):
        reader = jobsdb.Browser.__new__(jobsdb.Browser)
        reader.solve = lambda p: None
        reader.observed_account = None
        self.requirements = '<h1>Choose documents</h1><button>Continue</button>'
        with self.assertRaises(ValueError) as caught:
            reader.observe_role_requirements(self.page, {'url': self.url, 'description': 'Engineer JD'})
        self.assertIn('CAPABILITY_UNVERIFIED', str(caught.exception))

    def test_quick_apply_with_different_accessible_name(self):
        self.detail = '<a data-automation="job-detail-apply" aria-label="Apply for Engineer at Employer" href="/job/12345678/apply/start"><span>Quick apply</span></a>'
        self.assertEqual('REVIEW', self.flow().open())
        self.assertEqual(0, self.submissions)

    def test_labelled_external_apply_is_not_followed(self):
        self.detail = '<a data-automation="job-detail-apply" aria-label="Apply for Engineer" href="https://external.test/apply"><span>Quick apply</span></a>'
        self.assertEqual('SKIPPED', self.flow().open())
        self.assertEqual(0, self.external)

    def test_prepare_then_exactly_one_submit(self):
        flow = self.flow()
        self.assertEqual('REVIEW',flow.open())
        self.assertEqual(0,self.submissions)
        self.assertEqual('SUBMITTED',flow.submit())
        self.assertEqual(1,self.submissions)
    def test_missing_answers_resume_and_validation(self):
        self.start = '<select id="answer" required><option value="">Choose</option><option value="yes">Yes</option></select><button onclick="location.href=\'/job/12345678/apply/review\'">Next</button>'
        flow=self.flow()
        self.assertEqual('NEEDS_INPUT',flow.open())
        self.assertEqual('', self.page.locator('#answer').input_value())
        self.page.locator('#answer').select_option('yes')
        self.assertEqual('REVIEW',flow.advance())
        self.page.locator('body').evaluate("e=>e.insertAdjacentHTML('afterbegin','<input aria-invalid=\"true\">')")
        self.assertEqual('NEEDS_INPUT',flow.submit())
        self.assertEqual(0,self.submissions)
    def test_automatic_prepare_selects_configured_resume_without_stdin(self):
        self.review_document = '<p>English_abcd.docx</p>'
        self.start = """<label><input type="radio" name="document-select" value="old" checked>old.pdf</label>
        <label><input type="radio" name="document-select" value="new">English_abcd.docx</label>
        <input id="resume-fileFile" type="file">
        <label><input type="radio" name="coverLetter-method" value="none">Don't include a cover letter</label>
        <button onclick="if(document.querySelector('[value=new]').checked) location.href='/job/12345678/apply/review'">Continue</button>"""
        flow=self.flow()
        self.assertEqual('REVIEW', flow.prepare_automatic(Path('/tmp/English_abcd.docx'), {}))
        self.assertEqual(0,self.submissions)

    def test_automatic_questions_use_exact_configured_answers(self):
        self.review_document='<p>English_abcd.docx</p>'
        self.start="""<select name="questionnaire.salary"><option value="">Choose</option><option value="20">$20K</option></select>
        <label><input type="radio" name="questionnaire.visa">IANG</label>
        <button onclick="if(document.querySelector('select').value==='20' && document.querySelector('input').checked) location.href='/job/12345678/apply/review'">Continue</button>"""
        flow=self.flow()
        self.assertEqual('REVIEW',flow.prepare_automatic(Path('/tmp/English_abcd.docx'),{'questionnaire.salary':'$20K','questionnaire.visa':'IANG'}))
        self.assertEqual(0,self.submissions)

    def test_automatic_checkbox_answers_clear_stale_choices(self):
        self.review_document='<p>English_abcd.docx</p>'
        self.start="""<label><input type="checkbox" name="questionnaire.languages" value="python">Python</label>
        <label><input type="checkbox" name="questionnaire.languages" value="java" checked>Java</label>
        <button onclick="if(document.querySelector('[value=python]').checked && !document.querySelector('[value=java]').checked) location.href='/job/12345678/apply/review'">Continue</button>"""
        flow=self.flow()
        self.assertEqual('REVIEW',flow.prepare_automatic(Path('/tmp/English_abcd.docx'),{'questionnaire.languages':['Python']}))
        self.assertEqual(0,self.submissions)

    def test_automatic_checkbox_unknown_or_ambiguous_answers_stop(self):
        for answer in [['Rust'], ['Python','Python'], 'Python', []]:
            with self.subTest(answer=answer):
                self.start='<label><input type="checkbox" name="questionnaire.lang">Python</label><button>Continue</button>'
                flow=self.flow()
                self.assertEqual('NEEDS_INPUT',flow.prepare_automatic(Path('/tmp/cv.docx'),{'questionnaire.lang':answer}))
                self.assertFalse(self.page.locator('input').is_checked())
                self.assertEqual(0,self.submissions)
        self.start='<label><input type="checkbox" name="questionnaire.lang">Python</label><label><input type="checkbox" name="questionnaire.lang">Python</label><button>Continue</button>'
        flow=self.flow()
        self.assertEqual('NEEDS_INPUT',flow.prepare_automatic(Path('/tmp/cv.docx'),{'questionnaire.lang':['Python']}))
        self.assertIn('Unavailable checkbox',flow.reason)

    def single_task(self, data):
        import hashlib
        resume = data/'English_abcd.docx'; resume.write_bytes(b'frozen resume bytes')
        return {'schemaVersion':1, 'kind':'JOBSDB_SINGLE_APPLY', 'taskId':'attempt-1', 'attemptId':'attempt-1',
                'executionResourceId':'resource-1', 'stage':'APPLY', 'token':'current-credential', 'jobId':'12345678',
                'url':self.url, 'resumePath':str(resume), 'resumeSha256':hashlib.sha256(resume.read_bytes()).hexdigest(),
                'answers':{}, 'answersSha256':hashlib.sha256(b'{}').hexdigest(), 'profileHash':jobsdb.profile_fingerprint(data),
                'contactHash':'b'*64, 'jdSha256':hashlib.sha256(b'Engineer JD').hexdigest(), 'resultPath':str(data/'result.json')}

    def b1_upload_start(self):
        return """<input id="resume-fileFile" type="file" onchange="fetch('/fixture-upload',{method:'POST'}).then(()=>document.body.insertAdjacentHTML('afterbegin','<label><input type=radio name=document-select value=new>English_abcd.docx</label>'))">
        <label><input type="radio" name="coverLetter-method" value="none">Don't include a cover letter</label>
        <button onclick="if(document.querySelector('[value=new]').checked) location.href='/job/12345678/apply/role-requirements'">Continue</button>"""

    def b1_answers(self):
        return {'questionnaire.HK_Q_3217_V_4':'IANG','questionnaire.HK_Q_D3FEB':'No experience'}

    def single_browser(self, task, *, observer=True):
        page = self.page
        class FixtureBrowser(jobsdb.Browser):
            def solve(self, _): return None
            def run(self, url, action): page.goto(url); return action(page)
        browser = FixtureBrowser(Path(task['resumePath']).parent)
        if not observer:
            browser.observe_execution_context = None
        return browser

    @contextmanager
    def permission_server(self, task, data, *, reject=None, wrong_ack=False):
        import json, threading
        from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
        calls = []
        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *_): pass
            def do_POST(self):
                event = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
                with sqlite3.connect(data/'applications.db') as db:
                    claimed = db.execute('SELECT 1 FROM applications WHERE job_id=?', (task['jobId'],)).fetchone() is not None
                calls.append((event['phase'], claimed))
                assert self.headers['Authorization'] == 'Bearer ' + task['token']
                if event['phase'] != 'UPLOAD': assert event['binding']['authorizationId'] == task['authorizationId']
                unknown = event['phase'] == 'QUESTIONS' and any(q['name'] not in task['answers'] for q in event['roleRequirements']['questions'])
                if event['phase'] == reject or unknown:
                    self.send_response(409); self.end_headers(); return
                if event['phase'] == 'UPLOAD':
                    assert event['type']=='SUBMITTING'
                    ack = {'id':task['taskId'], 'attemptId':task['attemptId'], 'state':'UNKNOWN', 'outboundClaim':'auth-1',
                           'executionResourceId':task['executionResourceId'], 'jobsDbDispatch':dict(event['binding'], authorizationId='auth-1')}
                    if wrong_ack: ack['attemptId'] = 'stale-attempt'
                else:
                    ack = {'id':task['taskId'], 'state':'UNKNOWN', 'validatedPermission':{
                        'taskId':task['taskId'], 'attemptId':task['attemptId'], 'authorizationId':task['authorizationId'],
                        'executionResourceId':task['executionResourceId'], 'action':'APPLY', 'phase':event['phase'], 'status':'CONFIRMED'}}
                    if wrong_ack: ack['validatedPermission']['attemptId'] = 'stale-attempt'
                self.send_response(200); self.end_headers(); self.wfile.write(json.dumps(ack).encode())
        server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        task['callbackUrl'] = f'http://127.0.0.1:{server.server_port}/api/workbench/tasks/{task["attemptId"]}/agent-event'
        thread = threading.Thread(target=server.serve_forever, daemon=True); thread.start()
        try: yield calls
        finally: server.shutdown(); server.server_close(); thread.join()

    def test_single_apply_freezes_bindings_claims_once_and_never_retries(self):
        import hashlib, json, tempfile
        self.review_document = '<label><input type="radio" name="document-select" value="new" checked>English_abcd.docx</label>'
        self.start = self.b1_upload_start()
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp); task = self.single_task(tmp); result = Path(task['resultPath'])
            task['answers'] = self.b1_answers()
            task['answersSha256'] = hashlib.sha256(json.dumps(task['answers'], sort_keys=True, separators=(',', ':')).encode()).hexdigest()
            uploaded_with_claim = []
            def upload_route(route):
                if route.request.url.endswith('/fixture-upload'):
                    with sqlite3.connect(tmp/'applications.db') as db:
                        uploaded_with_claim.append(db.execute("SELECT status FROM applications WHERE job_id='12345678'").fetchone())
                    route.fulfill(body='fixture upload only')
                else: self.route(route)
            self.context.route('**/*', upload_route)
            with jobsdb.Store(tmp/'applications.db') as store, self.permission_server(task, tmp) as calls:
                self.assertEqual(0, jobsdb.run_single(task, self.single_browser(task), store, tmp))
                self.assertEqual([None], uploaded_with_claim, 'B1 allows pre-submit upload before the native ledger claim')
                self.assertEqual(('UPLOAD', False), calls[0])
                self.assertLess(calls.index(('QUESTIONS', False)), calls.index(('CLAIM', False)))
                self.assertEqual('unknown', json.loads(result.read_text())['deliveryOutcome']['cvActuallyReceived'])
                self.assertTrue(all(claimed for phase, claimed in calls if phase == 'SUBMIT'), 'Native claim must precede final submit')
                self.assertEqual('SUBMIT', calls[-1][0])
                payload = json.loads(result.read_text())
                self.assertEqual(['quick apply entered', 'approved resume selected', 'questionnaire read',
                                  'questions and attachment approved at review', 'native claim committed'], payload['formSteps'])
                self.assertEqual(1, payload['schemaVersion'])
                self.assertEqual('new', payload['attachment']['reviewResumeId'])
                self.assertEqual('SUBMITTED', payload['status'])
                self.assertEqual('12345678', payload['jobId'])
                self.assertEqual(task['resumeSha256'], payload['resumeSha256'])
                self.assertEqual(task['answersSha256'], payload['answersSha256'])
                self.assertEqual(task['profileHash'], payload['profileHash'])
                self.assertEqual(task['contactHash'], payload['contactHash'])
                self.assertTrue(payload['receipt']['textSha256'])
                self.assertEqual(1, self.submissions)
                self.assertTrue(store.contains('12345678'))
                second = dict(task, attemptId='attempt-2', authorizationId='auth-2', resultPath=str(tmp/'second.json'))
                self.assertEqual(0, jobsdb.run_single(second, self.single_browser(task), store, tmp))
                self.assertEqual('SKIPPED', json.loads((tmp/'second.json').read_text())['status'])
                self.assertEqual(1, self.submissions)

    def test_new_job_uploads_approved_bytes_even_when_old_job_used_the_same_filename(self):
        import hashlib, json
        self.review_document = '<label><input type="radio" name="document-select" value="new" checked>English_abcd.docx</label>'
        self.start = '<label><input type="radio" name="document-select" value="prior-job" checked>English_abcd.docx</label>' + self.b1_upload_start()
        with tempfile.TemporaryDirectory() as tmp:
            data = Path(tmp)
            task = self.single_task(data)
            task['answers'] = self.b1_answers()
            task['answersSha256'] = hashlib.sha256(json.dumps(task['answers'], sort_keys=True, separators=(',', ':')).encode()).hexdigest()
            with jobsdb.Store(data/'applications.db') as store, self.permission_server(task, data):
                store.claim('12345677', 'old-job', 'cli')
                store.finish('12345677', 'SUBMITTED')
                self.assertEqual(0, jobsdb.run_single(task, self.single_browser(task), store, data))
                self.assertEqual('SUBMITTED', store.attempt(task['jobId'])['status'])
            receipt = json.loads(Path(task['resultPath']).read_text())
            self.assertEqual('new', receipt['attachment']['reviewResumeId'])
            self.assertEqual((1, 1), (self.uploads, self.submissions))

    def test_single_fences_reject_stale_permission_before_upload_or_final_click(self):
        import hashlib, json
        self.review_document = '<label><input type="radio" name="document-select" value="new" checked>English_abcd.docx</label>'
        self.start = self.b1_upload_start()
        for phase in ('UPLOAD', 'QUESTIONS', 'CLAIM', 'SUBMIT'):
            with self.subTest(phase=phase), tempfile.TemporaryDirectory() as tmp:
                data = Path(tmp); task = self.single_task(data)
                task['answers'] = self.b1_answers()
                task['answersSha256'] = hashlib.sha256(json.dumps(task['answers'], sort_keys=True, separators=(',', ':')).encode()).hexdigest()
                with jobsdb.Store(data/'applications.db') as store, self.permission_server(task, data, reject=phase) as calls:
                    self.assertEqual(2, jobsdb.run_single(task, self.single_browser(task), store, data))
                    self.assertEqual(phase == 'SUBMIT', store.contains('12345678'))
                    self.assertEqual(phase, calls[-1][0])
                    self.assertEqual(0, self.submissions)
                payload = json.loads(Path(task['resultPath']).read_text())
                self.assertEqual('STOPPED_BEFORE_EFFECT' if phase == 'UPLOAD' else 'UNKNOWN', payload['status'])

    def test_question_change_autosave_is_fenced_before_modifying_answers(self):
        import hashlib, json
        self.review_document = '<p>English_abcd.docx</p>'
        self.detail = '<a href="/job/12345678/apply/role-requirements">Quick apply</a>'
        with tempfile.TemporaryDirectory() as tmp:
            data = Path(tmp); task = self.single_task(data); task['answers'] = self.b1_answers()
            task['answersSha256'] = hashlib.sha256(json.dumps(task['answers'], sort_keys=True, separators=(',', ':')).encode()).hexdigest()
            browser = self.single_browser(task)
            browser.observe_execution_context = lambda page, task: {'jdSha256':task['jdSha256'], 'accountHash':'c'*64,
                'sessionHash':('f' if page.locator('[name^="questionnaire."]').count() else 'd')*64}
            with jobsdb.Store(data/'applications.db') as store, self.permission_server(task, data):
                self.assertEqual(2, jobsdb.run_single(task, browser, store, data))
                self.assertFalse(store.contains('12345678'))
                self.assertEqual('STOPPED_BEFORE_EFFECT', json.loads(Path(task['resultPath']).read_text())['status'])
                self.assertIsNone(self.page.locator('body').get_attribute('data-answer-disclosed'), 'An onchange autosave must not disclose answers before a valid claim')
                self.assertEqual(0, self.submissions)

    def test_same_job_changed_or_unreadable_jd_blocks_before_upload(self):
        import json
        self.start = self.b1_upload_start()
        original = self.detail
        for replacement in ('Revised requirements', ''):
            self.detail = original.replace('Engineer JD', replacement)
            with self.subTest(jd=replacement), tempfile.TemporaryDirectory() as tmp:
                data = Path(tmp)
                task = self.single_task(data)
                with jobsdb.Store(data/'applications.db') as store, self.permission_server(task, data) as calls:
                    self.assertEqual(2, jobsdb.run_single(task, self.single_browser(task), store, data))
                    self.assertEqual([], calls)
                    self.assertFalse(store.contains(task['jobId']))
                result = json.loads(Path(task['resultPath']).read_text())
                self.assertEqual(0, self.uploads)
                self.assertEqual('not attempted', result['deliveryOutcome']['cvActuallyReceived'])
                self.assertEqual(0, self.submissions)

    def test_live_jd_changes_after_upload_before_claim(self):
        import json
        self.start = self.b1_upload_start()
        def change_description(route):
            self.detail = self.detail.replace('Engineer JD', 'Changed role')
            self.route(route)
        self.context.route('**/fixture-upload', change_description)
        with tempfile.TemporaryDirectory() as tmp:
            data = Path(tmp)
            task = self.single_task(data)
            with jobsdb.Store(data/'applications.db') as store, self.permission_server(task, data) as calls:
                self.assertEqual(2, jobsdb.run_single(task, self.single_browser(task), store, data))
                self.assertEqual([('UPLOAD', False)], calls)
                self.assertFalse(store.contains(task['jobId']))
            result = json.loads(Path(task['resultPath']).read_text())
            self.assertEqual(('blocked', 'unknown'), (result['deliveryOutcome']['attemptDisposition'],
                                                     result['deliveryOutcome']['cvActuallyReceived']))
            self.assertEqual((1, 0), (self.uploads, self.submissions))

    def test_unknown_question_after_upload_retains_disclosure(self):
        import json
        self.start = self.b1_upload_start()
        with tempfile.TemporaryDirectory() as tmp:
            data = Path(tmp)
            task = self.single_task(data)
            with jobsdb.Store(data/'applications.db') as store, self.permission_server(task, data) as calls:
                self.assertEqual(2, jobsdb.run_single(task, self.single_browser(task), store, data))
                self.assertIn(('QUESTIONS', False), calls)
                self.assertNotIn(('CLAIM', False), calls)
                self.assertFalse(store.contains(task['jobId']))
            result = json.loads(Path(task['resultPath']).read_text())
            self.assertEqual('UNKNOWN', result['status'])
            self.assertEqual({'cvActuallyReceived':'unknown', 'messageSent':'not applicable', 'attemptDisposition':'blocked'},
                             result['deliveryOutcome'])
            self.assertEqual(0, self.submissions)

    def test_changed_attachment_at_review_preserves_upload_without_native_claim(self):
        import hashlib, json
        self.start = self.b1_upload_start()
        self.review_document = '<p>English_abcd.docx</p>'
        with tempfile.TemporaryDirectory() as tmp:
            data = Path(tmp)
            task = self.single_task(data)
            task['answers'] = self.b1_answers()
            task['answersSha256'] = hashlib.sha256(json.dumps(task['answers'], sort_keys=True, separators=(',', ':')).encode()).hexdigest()
            with jobsdb.Store(data/'applications.db') as store, self.permission_server(task, data) as calls:
                self.assertEqual(2, jobsdb.run_single(task, self.single_browser(task), store, data))
                self.assertIn(('QUESTIONS', False), calls)
                self.assertFalse(store.contains(task['jobId']))
                self.assertEqual(0, self.submissions)
            result = json.loads(Path(task['resultPath']).read_text())
            self.assertEqual(1, self.uploads)
            self.assertEqual(('UNKNOWN', 'unknown', 'blocked'), (result['status'], result['deliveryOutcome']['cvActuallyReceived'],
                                                               result['deliveryOutcome']['attemptDisposition']))

    def test_unknown_or_misbound_upload_ack_never_authorizes_disclosure(self):
        import json
        self.start = self.b1_upload_start()
        for missing in (False, True):
            with self.subTest(missing=missing), tempfile.TemporaryDirectory() as tmp:
                data = Path(tmp)
                task = self.single_task(data)
                with jobsdb.Store(data/'applications.db') as store, self.permission_server(task, data, wrong_ack=True) as calls:
                    self.assertEqual(2, jobsdb.run_single(task, self.single_browser(task, observer=not missing), store, data))
                    self.assertEqual([] if missing else [('UPLOAD', False)], calls)
                    self.assertFalse(store.contains(task['jobId']))
                result = json.loads(Path(task['resultPath']).read_text())
                self.assertEqual('STOPPED_BEFORE_EFFECT' if missing else 'UNKNOWN', result['status'])
                self.assertEqual(0, self.submissions)

    def test_automatic_unknown_question_never_submits(self):
        self.start='<input name="questionnaire.unknown"><button>Continue</button>'
        flow=self.flow()
        self.assertEqual('NEEDS_INPUT',flow.prepare_automatic(Path('/tmp/English_abcd.docx'),{}))
        self.assertIn('Unconfigured',flow.reason)
        self.assertEqual(0,self.submissions)

    def test_automatic_review_rejects_wrong_attachment(self):
        self.start='<input name="questionnaire.unknown"><button>Continue</button>'
        self.detail='<a href="/job/12345678/apply/review">Quick apply</a>'
        flow=self.flow()
        self.assertEqual('NEEDS_INPUT',flow.prepare_automatic(Path('/tmp/English_abcd.docx'),{}))
        self.assertIn('resume absent',flow.reason)
        self.assertEqual(0,self.submissions)

    def test_automatic_profile_retries_only_non_submitting_continue(self):
        self.detail='<a href="/job/12345678/apply/profile">Quick apply</a>'
        self.review_document='<p>English_abcd.docx</p>'
        self.profile="""<p>Your Jobsdb Profile is part of your application. Make sure it's up-to-date.</p>
        <script>let n=0</script><button onclick="if(++n===2) location.href='/job/12345678/apply/review'">Continue</button>"""
        self.assertEqual('REVIEW',self.flow().prepare_automatic(Path('/tmp/English_abcd.docx'),{}))
        self.assertEqual(0,self.submissions)

    def test_live_employer_named_success(self):
        self.success='<h1>Your application has been sent to Auspicious Bullion Limited</h1>'
        flow=self.flow(); self.assertEqual('REVIEW', flow.open())
        self.assertEqual('SUBMITTED', flow.submit())
        self.assertEqual(1,self.submissions)

    def test_unknown_does_not_resubmit(self):
        self.success='<h1>Something went wrong</h1>'
        flow=self.flow(); self.assertEqual('REVIEW',flow.open())
        self.assertEqual('UNKNOWN',flow.submit()); self.assertEqual(1,self.submissions)
    def test_identity_change_blocks_submit(self):
        flow=self.flow(); flow.open()
        self.page.goto('https://hk.jobsdb.com/job/999/apply/review')
        self.assertEqual('NEEDS_INPUT',flow.submit())
        self.page.goto('https://external.test/job/12345678/apply/review')
        self.assertEqual('NEEDS_INPUT',flow.submit()); self.assertEqual(0,self.submissions)
    def test_external_apply_is_not_followed(self):
        self.detail='<a href="https://external.test/apply">Quick apply</a>'
        self.assertEqual('SKIPPED',self.flow().open()); self.assertEqual(0,self.external)
        self.detail='<a href="https://external.test/apply">Apply</a>'
        self.assertEqual('SKIPPED',self.flow().open()); self.assertEqual(0,self.external)
    def test_applied_badge(self):
        self.detail='<span data-automation="applied-badge">Applied</span>'
        self.assertEqual('ALREADY_APPLIED',self.flow().open())
    def test_challenge_recognition_in_both_languages(self):
        self.page.goto(self.url)
        self.page.set_content('<title>请稍候…</title>')
        self.assertTrue(jobsdb.challenge(self.page))
        self.page.set_content('<h1>Performing security verification</h1>')
        self.assertTrue(jobsdb.challenge(self.page))
    def test_challenge_blocks_final_click(self):
        flow=self.flow(); flow.open()
        self.page.locator('body').evaluate("e=>e.insertAdjacentHTML('afterbegin','<h1>Verify you are human</h1>')")
        self.assertEqual('NEEDS_INPUT',flow.submit()); self.assertEqual(0,self.submissions)
    def test_stalled_continue_is_bounded(self):
        self.start='<button>Continue</button>'
        self.assertEqual('NEEDS_INPUT',self.flow().open())
    def test_collect_dedupes_canonical_links(self):
        self.detail='<a data-automation="jobTitle" href="/job/123?x=y">Engineer</a><a data-automation="jobTitle" href="/job/123">Duplicate</a><a data-automation="jobTitle" href="https://evil.test/job/456">External</a>'
        self.page.goto(self.url)
        self.assertEqual([{'id':'123','title':'Engineer','url':'https://hk.jobsdb.com/job/123'}],jobsdb.collect_jobs(self.page))
    def test_search_ignores_hidden_overlay_before_title(self):
        self.detail='<article><a style="display:none" href="/job/123"></a><a href="/job/123"></a><a data-automation="jobTitle" href="/job/123">Engineer title</a></article>'
        self.page.goto(self.url)
        self.assertEqual('Engineer title', jobsdb.collect_jobs(self.page)[0]['title'])
    def test_eof_does_not_confirm_login_or_submit(self):
        from unittest.mock import patch
        self.page.goto(self.url)
        with patch('sys.stdin',io.StringIO('')):
            self.assertEqual(2,jobsdb.login(self.page))
        with tempfile.TemporaryDirectory() as tmp, jobsdb.Store(Path(tmp)/'db') as store:
            browser=type('Browser',(),{'solve':staticmethod(lambda page: None)})()
            with patch('sys.stdin',io.StringIO('')):
                self.assertEqual(2,jobsdb.application(self.page,browser,store,self.url,'apply',Path(tmp)))
            self.assertFalse(store.contains('12345678'))
            self.assertEqual(0,self.submissions)
    def test_confirmed_application_claim_and_skip(self):
        from unittest.mock import patch
        self.page.goto(self.url)
        with tempfile.TemporaryDirectory() as tmp, jobsdb.Store(Path(tmp)/'db') as store:
            browser=type('Browser',(),{'solve':staticmethod(lambda page: None)})()
            with patch('sys.stdin',io.StringIO('SUBMIT 12345678\n')):
                self.assertEqual(0,jobsdb.application(self.page,browser,store,self.url,'apply',Path(tmp)))
            self.assertTrue(store.contains('12345678'))
            self.assertFalse(store.claim('12345678'))
            self.assertIn('SUBMITTED','\n'.join(store.history()))

class IntegrationTests(unittest.TestCase):
    def test_persistent_session_and_action_exceptions(self):
        with tempfile.TemporaryDirectory() as tmp:
            data=Path(tmp)
            for attempt in range(2):
                with jobsdb.Browser(data) as browser:
                    browser.session.context.route('**/*', lambda route: route.fulfill(content_type='text/html',body='<h1>Fixture account</h1>'))
                    def action(page):
                        if attempt == 0:
                            page.evaluate("document.cookie='session_fixture=retained;Max-Age=600;Secure;SameSite=Lax';localStorage.setItem('fixture','retained')")
                        else:
                            self.assertIn('session_fixture=retained', page.evaluate('document.cookie'))
                            self.assertEqual('retained',page.evaluate("localStorage.getItem('fixture')"))
                        return 'OK'
                    self.assertEqual('OK',browser.run('https://hk.jobsdb.com/',action))
                    def broken(page): raise ValueError('Action failure must propagate')
                    with self.assertRaisesRegex(ValueError,'Action failure'):
                        browser.run('https://hk.jobsdb.com/',broken)
    def test_lock_excludes_second_process(self):
        import subprocess,sys
        with tempfile.TemporaryDirectory() as tmp, jobsdb.profile_lock(Path(tmp)):
            result=subprocess.run([sys.executable,'-c',f"import jobsdb; from pathlib import Path;\nwith jobsdb.profile_lock(Path({tmp!r})): pass"],cwd=str(Path(jobsdb.__file__).parent),capture_output=True)
            self.assertNotEqual(0,result.returncode)
            self.assertIn(b'Another JobsDB command',result.stderr)
    def test_cli_rejects_before_browser_and_help_is_lazy(self):
        with self.assertRaises(SystemExit): jobsdb.main(['search','engineer','6'])
        with self.assertRaises(ValueError): jobsdb.main(['apply','https://evil.test/job/123'])
        self.assertEqual(0,jobsdb.main(['help']))

if __name__ == '__main__':
    unittest.main()
