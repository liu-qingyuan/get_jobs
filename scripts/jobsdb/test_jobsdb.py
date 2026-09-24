import io
import sqlite3
import tempfile
import unittest
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
        self.detail = '<h1>Engineer</h1><a href="/job/12345678/apply/start">Quick apply</a>'
        self.start = '<button onclick="location.href=\'/job/12345678/apply/review\'">Continue</button>'
        self.success = '<h1>Your application has been submitted</h1>'
        self.submissions = self.external = 0
    def tearDown(self): self.context.close()
    def route(self, route):
        from urllib.parse import urlsplit
        url = route.request.url
        path = urlsplit(url).path
        if not url.startswith('https://hk.jobsdb.com/'):
            self.external += 1; route.fulfill(body='External'); return
        if path.endswith('/success'):
            self.submissions += 1; body = self.success
        elif path.endswith('/review'):
            body = '<p>Review and submit</p><button onclick="location.href=\'/job/12345678/apply/success\'">Submit application</button>'
        elif path.endswith('/start'): body = self.start
        else: body = self.detail
        route.fulfill(content_type='text/html', body=body)
    def flow(self):
        self.page.goto(self.url)
        return jobsdb.Flow(self.page,self.url,lambda p: None)
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
