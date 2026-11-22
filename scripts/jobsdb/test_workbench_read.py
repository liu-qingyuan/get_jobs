from pathlib import Path
import tempfile
import unittest

import jobsdb


class WorkbenchReadTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        from patchright.sync_api import sync_playwright
        cls.pw = sync_playwright().start()
        cls.browser = cls.pw.chromium.launch(channel='chrome', headless=True)

    @classmethod
    def tearDownClass(cls):
        cls.browser.close()
        cls.pw.stop()

    def setUp(self):
        self.context = self.browser.new_context()
        self.context.route('**/*', self.route)
        self.effects = 0
        self.url = 'https://hk.jobsdb.com/python-jobs'
        self.ids = ['12345678']
        self.description = 'Responsibilities: build Python search applications with repeatable tests. Requirements: Python projects, evaluation and documentation, with synthetic data only.'

    def tearDown(self):
        self.context.close()

    def route(self, route):
        if route.request.method != 'GET' or '/apply' in route.request.url or 'evil.test' in route.request.url:
            self.effects += 1
        path = route.request.url
        if '/job/' not in path:
            body = ''.join('<a data-automation="jobTitle" href="/job/' + id + '?ref=search">Python engineer</a>' for id in self.ids)
        else:
            body = '<h1 data-automation="job-detail-title">Python engineer</h1><p data-automation="advertiser-name">Synthetic Corp</p><div data-automation="jobAdDetails">' + self.description + '</div><button>Quick apply</button>'
        route.fulfill(content_type='text/html', body=body + "<script src='/job/12345678/apply'></script><script>fetch('/job/12345678/apply',{method:'POST'}).catch(()=>{});fetch('https://evil.test/data').catch(()=>{})</script>")

    def read_browser(self, url, action, *, setup):
        page = self.context.new_page()
        try:
            setup(page)
            page.goto(url)
            return action(page)
        finally:
            page.close()

    def bundle(self):
        return {'kind': 'CANDIDATE_READ', 'source': {'channel': 'JOBSDB', 'url': self.url, 'cursor': ''}, 'limits': {'candidates': 2, 'seconds': 10, 'modelTokens': 0}}

    def read(self, bundle=None):
        from workbench_read import read_candidates
        from types import SimpleNamespace
        return read_candidates(bundle or self.bundle(), SimpleNamespace(run=self.read_browser))

    def test_bounded_search_and_full_details_never_apply_or_leak_task_materials(self):
        output = self.read()
        self.assertEqual(1, len(output['jobs']))
        job = output['jobs'][0]
        self.assertEqual('https://hk.jobsdb.com/job/12345678', job['url'])
        self.assertEqual('Synthetic Corp', job['company'])
        self.assertEqual(self.description, job['description'])
        self.assertTrue(job['descriptionComplete'])
        self.assertEqual(0, self.effects)
        self.assertEqual(0, len(self.context.pages))

    def test_popup_first_navigation_cannot_escape_the_context_read_guard(self):
        from workbench_read import read_only
        page = self.context.new_page()
        read_only(page)
        page.goto(self.url)
        with page.expect_popup() as popup:
            page.evaluate("window.open('https://evil.test/popup')")
        popup.value.wait_for_load_state()
        self.assertEqual(0, self.effects)

    def test_short_or_missing_description_stays_incomplete(self):
        self.description = 'Card summary only'
        job = self.read()['jobs'][0]
        self.assertFalse(job['descriptionComplete'])
        self.assertEqual(self.description, job['description'])
        self.assertEqual(0, self.effects)

    def test_cursor_is_bounded_and_bound_to_loaded_jobs(self):
        self.ids = ['12345678', '23456789', '34567890']
        first = self.read()
        self.assertEqual(2, len(first['jobs']))
        self.assertEqual(self.url, first['nextUrl'])
        bundle = self.bundle()
        bundle['source']['cursor'] = first['cursor']
        second = self.read(bundle)
        self.assertEqual('https://hk.jobsdb.com/job/34567890', second['jobs'][0]['url'])
        self.assertNotIn('cursor', second)
        self.ids = ['12345678']
        with self.assertRaisesRegex(RuntimeError, 'list changed'):
            self.read(bundle)
        self.assertEqual(0, self.effects)

    def test_wrong_channel_port_materials_and_resource_envelopes_are_rejected(self):
        for mutate in [lambda b: b['source'].update(url='https://hk.jobsdb.com/job/12345678/apply'),
                       lambda b: b['source'].update(url='https://hk.jobsdb.com:444/jobs'),
                       lambda b: b.update(token='forbidden'),
                       lambda b: b['limits'].update(candidates=41),
                       lambda b: b['limits'].update(modelTokens=1)]:
            bundle = self.bundle()
            mutate(bundle)
            with self.assertRaises(ValueError):
                self.read(bundle)
        self.assertEqual(0, len(self.context.pages))

    def test_read_browser_blocks_a_service_worker_persisted_in_its_own_temporary_profile(self):
        from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
        from threading import Thread
        import subprocess
        import sys
        import json

        class Fixture(BaseHTTPRequestHandler):
            def do_GET(self):
                self.send_response(200)
                self.send_header('Content-Type', 'text/javascript' if self.path == '/sw.js' else 'text/html')
                self.end_headers()
                self.wfile.write(("self.addEventListener('install',e=>self.skipWaiting());self.addEventListener('activate',e=>e.waitUntil(clients.claim()));self.addEventListener('fetch',e=>{if(new URL(e.request.url).pathname==='/probe')e.respondWith(new Response('worker intercepted'))})" if self.path == '/sw.js' else '<h1>Local service worker fixture</h1>').encode())
            def log_message(self, *_):
                pass

        server = ThreadingHTTPServer(('127.0.0.1', 0), Fixture)
        thread = Thread(target=server.serve_forever, daemon=True)
        thread.start()
        base = 'http://127.0.0.1:' + str(server.server_port)
        try:
            with tempfile.TemporaryDirectory() as tmp:
                data = Path(tmp)
                context = self.pw.chromium.launch_persistent_context(str(data / 'scrapling-profile'), channel='chrome', headless=True)
                try:
                    page = context.new_page()
                    page.goto(base)
                    page.add_script_tag(content="navigator.serviceWorker.register('/sw.js').then(()=>navigator.serviceWorker.ready).then(()=>document.body.dataset.ready='yes')")
                    page.wait_for_function("document.body.dataset.ready==='yes'")
                    page.reload()
                    page.add_script_tag(content="fetch('/probe').then(r=>r.text()).then(t=>document.body.dataset.probe=t)")
                    page.wait_for_function("document.body.dataset.probe==='worker intercepted'")
                finally:
                    context.close()

                # Registration blocking does NOT stop the saved worker. Reopen only this
                # synthetic profile and prove its response bypasses a deny-all request route.
                context = self.pw.chromium.launch_persistent_context(str(data / 'scrapling-profile'), channel='chrome', headless=True, service_workers='block')
                routed_probes = []
                def deny_probe(route):
                    if route.request.url == base + '/probe':
                        routed_probes.append(route.request.url)
                        route.abort()
                    elif route.request.url == base + '/':
                        route.continue_()
                    else:
                        route.abort()
                context.route('**/*', deny_probe)
                try:
                    page = context.new_page()
                    page.goto(base)
                    page.add_script_tag(content="fetch('/probe').then(r=>r.text()).then(t=>document.body.dataset.probe=t).catch(()=>document.body.dataset.probe='route blocked')")
                    page.wait_for_function("document.body.dataset.probe!==undefined")
                    self.assertEqual('worker intercepted', page.locator('body').get_attribute('data-probe'))
                    self.assertEqual([], routed_probes, 'Saved worker response bypasses even a context-level deny route')
                finally:
                    context.close()

                # A separate owned process avoids nesting Scrapling's synchronous Playwright loop.
                child = subprocess.run([sys.executable, '-c', '''
import json,sys
from pathlib import Path
from unittest.mock import patch
sys.path.insert(0, sys.argv[3])
import jobsdb
with patch('scrapling.fetchers.StealthySession', side_effect=AssertionError('must not launch')):
    try:
        with jobsdb.Browser(Path(sys.argv[1]), read_only=True):
            raise AssertionError('must refuse existing worker before browser launch')
    except RuntimeError as error:
        print(json.dumps({'blocked':str(error)}))
''', str(data), base, str(Path(jobsdb.__file__).parent)], capture_output=True, text=True, timeout=30, check=True)
                self.assertIn('existing Service Worker state', json.loads(child.stdout)['blocked'])
                self.assertTrue((data / 'scrapling-profile/Default/Service Worker').is_dir())
                # A fresh temporary read profile must also refuse NEW worker registration.
                fresh = data / 'fresh'
                (fresh / 'scrapling-profile').mkdir(parents=True)
                child = subprocess.run([sys.executable, '-c', '''
import json,sys
from pathlib import Path
from unittest.mock import patch
sys.path.insert(0, sys.argv[3])
import jobsdb
from scrapling.fetchers import StealthySession
def headless_session(**options):
    return StealthySession(**dict(options, headless=True))
with patch('scrapling.fetchers.StealthySession', side_effect=headless_session):
    with jobsdb.Browser(Path(sys.argv[1]), read_only=True) as browser:
        page = browser.session.context.new_page()
        page.goto(sys.argv[2])
        page.add_script_tag(content="Promise.resolve().then(()=>navigator.serviceWorker.register('/sw.js')).then(reg=>document.body.dataset.registration=reg?'registered':'blocked').catch(()=>document.body.dataset.registration='blocked')")
        page.wait_for_function("document.body.dataset.registration!==undefined", timeout=5000)
        print(json.dumps({'registration':page.locator('body').get_attribute('data-registration')}))
''', str(fresh), base, str(Path(jobsdb.__file__).parent)], capture_output=True, text=True, timeout=30, check=True)
                self.assertEqual({'registration': 'blocked'}, json.loads(child.stdout))
        finally:
            server.shutdown()
            server.server_close()
            thread.join()

    def test_missing_session_never_creates_a_profile_or_opens_browser(self):
        from unittest.mock import patch
        import json
        with tempfile.TemporaryDirectory() as tmp:
            data = Path(tmp) / 'unconnected'
            file = Path(tmp) / 'task.json'
            bundle = self.bundle()
            bundle['resultPath'] = str(Path(tmp) / 'result.json')
            file.write_text(json.dumps(bundle))
            with patch.object(jobsdb, 'Browser', side_effect=AssertionError('must not open')):
                with self.assertRaisesRegex(RuntimeError, 'session missing'):
                    jobsdb.main(['read-candidates', str(file), '--data-dir', str(data)])
            self.assertFalse(data.exists())
            self.assertFalse(Path(bundle['resultPath']).exists())


if __name__ == '__main__':
    unittest.main()
