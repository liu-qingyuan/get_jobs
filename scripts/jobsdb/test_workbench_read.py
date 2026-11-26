from pathlib import Path
import hashlib
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
        return {'kind': 'CANDIDATE_READ', 'readAttemptId': 'read-attempt-1', 'source': {'channel': 'JOBSDB', 'url': self.url, 'cursor': ''}, 'limits': {'candidates': 2, 'seconds': 10, 'modelTokens': 0}}

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

                # Resume mode keeps the profile untouched but combines browser blocking with an official
                # per-page CDP bypass, so the same persisted worker can no longer evade the route guard.
                from workbench_read import secure_service_workers
                context = self.pw.chromium.launch_persistent_context(str(data / 'scrapling-profile'), channel='chrome', headless=True,
                                                                     service_workers='block', args=['--disable-features=ServiceWorker'])
                routed_probes = []
                def deny_bypassed_probe(route):
                    if route.request.url == base + '/probe':
                        routed_probes.append(route.request.url)
                        route.abort()
                    elif route.request.url == base + '/':
                        route.continue_()
                    else:
                        route.abort()
                context.route('**/*', deny_bypassed_probe)
                try:
                    page = context.new_page()
                    secure_service_workers(page)
                    page.goto(base)
                    page.add_script_tag(content="fetch('/probe').then(r=>r.text()).then(t=>document.body.dataset.probe=t).catch(()=>document.body.dataset.probe='route blocked')")
                    page.wait_for_function("document.body.dataset.probe!==undefined")
                    self.assertEqual('route blocked', page.locator('body').get_attribute('data-probe'))
                    self.assertEqual([base + '/probe'], routed_probes)
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

    def test_strict_login_redirect_becomes_a_user_action_checkpoint(self):
        self.context.unroute('**/*', self.route)
        def route(route):
            if route.request.url == self.url:
                route.fulfill(status=302, headers={'Location': 'https://hk.jobsdb.com/login'})
            elif route.request.url == 'https://hk.jobsdb.com/login':
                route.fulfill(content_type='text/html', body='<html><body>Sign in</body></html>')
            else:
                route.abort()
        self.context.route('**/*', route)
        with self.assertRaises(jobsdb.VerificationRequired):
            self.read()
        self.assertEqual(0, self.effects)

    def test_verification_page_is_reported_to_workbench_without_results_or_retry(self):
        import json
        from unittest.mock import patch

        for challenged_path, headers in [('/python-jobs', {}), ('/job/12345678', {}), ('/job/12345678', {'cf-mitigated': 'challenge'})]:
            with self.subTest(path=challenged_path, headers=headers), tempfile.TemporaryDirectory() as tmp:
                data = Path(tmp)
                (data / 'scrapling-profile').mkdir()
                file = data / 'task.json'
                result = data / 'result.json'
                file.write_text(json.dumps(self.bundle() | {'resultPath': str(result)}))
                challenged = []

                def route(route):
                    if route.request.url == jobsdb.SITE + challenged_path:
                        challenged.append(route.request.url)
                        body = '<title>Access restricted</title>' if headers else '<title>Just a moment...</title>'
                        route.fulfill(status=403, headers=headers, content_type='text/html', body=body)
                    else:
                        self.route(route)

                def fetch(url, *, solve_cloudflare, page_action, page_setup):
                    self.assertFalse(solve_cloudflare)
                    with self.browser.new_context() as context:
                        context.route('**/*', route)
                        page = context.new_page()
                        page_setup(page)
                        page.goto(url)
                        page_action(page)

                with patch('scrapling.fetchers.StealthySession') as session, patch.object(jobsdb, 'screenshot') as screenshot:
                    session.return_value.fetch.side_effect = fetch
                    self.assertEqual(0, jobsdb.main(['read-candidates', str(file), '--data-dir', str(data)]))
                screenshot.assert_not_called()
                target = self.url if challenged_path == '/python-jobs' else jobsdb.SITE + '/job/12345678'
                payload = json.loads(result.read_text())
                remaining = payload['userActionRequired'].pop('remainingSeconds')
                self.assertGreaterEqual(remaining, 0)
                self.assertLessEqual(remaining, 10)
                self.assertEqual({'userActionRequired': {'interactionId': 'read-attempt-1', 'targetUrl': target,
                    'message': 'JobsDB 要求用户在可见浏览器完成平台验证或登录'}}, payload)
                self.assertEqual([jobsdb.SITE + challenged_path], challenged)
                self.assertEqual(0, self.effects)

    def test_interactive_resume_waits_for_visible_challenge_to_clear_then_reads_the_same_job(self):
        from workbench_read import resume_candidates
        calls = {'list': 0}
        self.context.unroute('**/*', self.route)
        def route(route):
            if route.request.url == self.url:
                calls['list'] += 1
                if calls['list'] == 1:
                    route.fulfill(content_type='text/html', body="<title>Just a moment...</title><script>setTimeout(()=>location.reload(),100)</script>")
                    return
            self.route(route)
        self.context.route('**/*', route)
        class InteractiveBrowser:
            def __init__(inner, context): inner.context = context
            def run_interactive(inner, url, action, *, setup):
                page = inner.context.new_page()
                try:
                    setup(page)
                    page.goto(url)
                    return action(page)
                finally:
                    page.close()
            def solve(inner, page):
                # The visible challenge clears itself in this fixture; record that the automatic path ran.
                inner.solved = getattr(inner, 'solved', 0) + 1
                page.wait_for_timeout(250)
        class ReadBrowser:
            def __init__(inner, context): inner.context = context
            def run(inner, url, action, *, setup):
                page = inner.context.new_page()
                try:
                    setup(page)
                    page.goto(url)
                    return action(page)
                finally:
                    page.close()
        bundle = self.bundle() | {'resumeReadId': 'read-attempt-1'}
        interactive = InteractiveBrowser(self.context)
        output = resume_candidates(bundle, interactive, ReadBrowser(self.context))
        self.assertEqual(3, calls['list'])
        self.assertGreaterEqual(interactive.solved, 1)
        self.assertEqual('https://hk.jobsdb.com/job/12345678', output['jobs'][0]['url'])
        self.assertTrue(output['jobs'][0]['descriptionComplete'])
        self.assertEqual(0, self.effects)

    def test_auto_solver_is_attempted_twice_before_a_challenge_stays_a_checkpoint(self):
        from types import SimpleNamespace
        from unittest.mock import patch
        import workbench_read
        self.context.unroute('**/*', self.route)
        self.context.route('**/*', lambda route: route.fulfill(content_type='text/html',
            body="<title>Just a moment...</title><p>Performing security verification</p>"))
        calls = []
        class Browser:
            def run_interactive(inner, url, action, *, setup):
                page = self.context.new_page()
                try:
                    setup(page); page.goto(url); return action(page)
                finally:
                    page.close()
            def solve(inner, page):
                calls.append(page.url)
                raise RuntimeError('CF challenge remains')
        bundle = self.bundle() | {'userActionUrl': self.url}
        with self.assertRaises(jobsdb.VerificationRequired):
            workbench_read.wait_for_user_access(bundle, Browser())
        self.assertEqual(2, len(calls))

    def test_failed_automatic_handoff_keeps_the_same_attempt_and_full_strict_budget(self):
        import json
        from unittest.mock import MagicMock, patch
        import workbench_read
        with tempfile.TemporaryDirectory() as tmp:
            data = Path(tmp); (data / 'scrapling-profile').mkdir()
            file = data / 'task.json'; result = data / 'result.json'
            bundle = self.bundle() | {'resumeReadId': 'read-attempt-1', 'userActionUrl': self.url, 'resultPath': str(result)}
            file.write_text(json.dumps(bundle))
            interactive_context = MagicMock()
            interactive_context.__enter__.return_value = object()
            with patch.object(jobsdb, 'Browser', return_value=interactive_context), \
                    patch.object(workbench_read, 'wait_for_user_access',
                                 side_effect=jobsdb.VerificationRequired('login required', self.url)):
                self.assertEqual(0, jobsdb.main(['resume-read', str(file), '--data-dir', str(data)]))
            self.assertEqual({'userActionRequired': {'interactionId': 'read-attempt-1', 'targetUrl': self.url,
                'remainingSeconds': 10, 'message': 'JobsDB 自动验证或登录未完成；保留同一断点且不消耗严格读取时间'}},
                json.loads(result.read_text()))
            self.assertTrue(result.with_name(result.name + '.published').is_file())

    def test_interactive_wait_gives_up_after_bounded_redirects(self):
        from workbench_read import wait_for_user_access
        target = self.url
        other = jobsdb.SITE + '/other-jobs'
        class Locator:
            def all(inner): return []
        class Page:
            url = other
            gotos = 0
            def title(inner): return 'Other list'
            def locator(inner, _): return Locator()
            def get_by_text(inner, _): return Locator()
            def goto(inner, _target, wait_until=None):
                inner.gotos += 1
        page = Page()
        class Browser:
            def run_interactive(inner, url, action, *, setup):
                return action(page)
        bundle = self.bundle() | {'userActionUrl': target}
        with self.assertRaises(jobsdb.VerificationRequired):
            wait_for_user_access(bundle, Browser())
        self.assertEqual(3, page.gotos)

    def test_interactive_resume_observes_process_cancellation_before_touching_the_page(self):
        from types import SimpleNamespace
        from unittest.mock import patch
        from workbench_read import wait_for_user_access

        class ForbiddenPage:
            def __getattribute__(self, name):
                raise AssertionError('cancelled handoff must not touch the browser page')

        browser = SimpleNamespace(run_interactive=lambda _target, action, setup: action(ForbiddenPage()))
        bundle = self.bundle() | {'userActionUrl': self.url}
        with patch.object(jobsdb, 'cancellation_requested', return_value=True, create=True):
            with self.assertRaises(KeyboardInterrupt):
                wait_for_user_access(bundle, browser)

    def test_detail_challenge_is_presented_visibly_before_strict_read_restarts_from_the_original_list(self):
        from workbench_read import resume_candidates
        detail = jobsdb.SITE + '/job/12345678'
        calls = {'detail': 0, 'interactive_url': None}
        self.context.unroute('**/*', self.route)
        def route(route):
            if route.request.url == detail:
                calls['detail'] += 1
                if calls['detail'] == 1:
                    route.fulfill(content_type='text/html', body="<title>Just a moment...</title><script>setTimeout(()=>location.reload(),100)</script>")
                    return
            self.route(route)
        self.context.route('**/*', route)
        class InteractiveBrowser:
            def run_interactive(inner, url, action, *, setup):
                calls['interactive_url'] = url
                page = self.context.new_page()
                try:
                    setup(page); page.goto(url); return action(page)
                finally:
                    page.close()
            def solve(inner, page):
                calls['solved'] = calls.get('solved', 0) + 1
                page.wait_for_timeout(250)
        class ReadBrowser:
            def run(inner, url, action, *, setup):
                page = self.context.new_page()
                try:
                    setup(page); page.goto(url); return action(page)
                finally:
                    page.close()
        bundle = self.bundle() | {'resumeReadId': 'read-attempt-1', 'userActionUrl': detail}
        output = resume_candidates(bundle, InteractiveBrowser(), ReadBrowser())
        self.assertEqual(detail, calls['interactive_url'])
        self.assertEqual(3, calls['detail'])
        self.assertEqual(detail, output['jobs'][0]['url'])
        self.assertEqual(0, self.effects)

    def test_interactive_guard_blocks_unrecognized_writes_even_on_trusted_jobsdb_hosts(self):
        from workbench_read import interactive_guard
        page = self.context.new_page()
        try:
            interactive_guard(page)
            page.goto(self.url)
            page.evaluate("fetch('/api/job-application',{method:'POST',body:'x'}).catch(()=>{})")
            page.wait_for_timeout(100)
            self.assertEqual(0, self.effects)
        finally:
            page.close()

    def test_auth_host_writes_are_limited_to_recognized_authentication_paths(self):
        from workbench_read import interactive_request_allowed
        page = 'https://id.seek.com/login'
        self.assertFalse(interactive_request_allowed(page, 'POST', 'https://id.seek.com/graphql'))
        self.assertFalse(interactive_request_allowed(page, 'POST', 'https://id.seek.com/auth/graphql'))
        self.assertFalse(interactive_request_allowed(page, 'POST', 'https://id.seek.com/account/settings'))
        self.assertFalse(interactive_request_allowed(page, 'POST', 'http://id.seek.com/oauth/token'))
        self.assertTrue(interactive_request_allowed(page, 'POST', 'https://id.seek.com/oauth/token'))
        self.assertTrue(interactive_request_allowed(page, 'POST', 'https://id.seek.com/auth/verify'))
        self.assertFalse(interactive_request_allowed(page, 'POST', 'https://id.seek.com/telemetry-write'))
        self.assertFalse(interactive_request_allowed('https://hk.jobsdb.com/python-jobs', 'POST', 'https://id.seek.com/graphql'))
        self.assertFalse(interactive_request_allowed(page, 'POST', 'https://challenges.cloudflare.com/graphql'))
        self.assertFalse(interactive_request_allowed(page, 'POST', 'https://challenges.cloudflare.com/cdn-cgi/challenge-platform/account/delete'))
        flow = '/cdn-cgi/challenge-platform/h/g/flow/ov1/0.123456:1234567890:abcdef0123456789/0123456789abcdef/challenge_123'
        self.assertTrue(interactive_request_allowed(page, 'POST', 'https://challenges.cloudflare.com' + flow))
        self.assertTrue(interactive_request_allowed(page, 'POST', 'https://id.seek.com' + flow))
        first = '/cdn-cgi/challenge-platform/h/g/fo/3661899856:1795634424:tigYalylZ6nDVs1WG9sMOgNJ2RtZ9EWP_yeyoMVw4B0/a6035f86d8b6a99e/WxPwajVmGjrDF9zWYh_p6YVX9CUtTB2D9U3umgH.24s-1795638227-1.2.1.1-3NTONkHU84lvGMucmEl'
        second = '/cdn-cgi/challenge-platform/h/g/eb/3661899856:1795634424:tigYalylZ6nDVs1WG9sMOgNJ2RtZ9EWP_yeyoMVw4B0/a6035f86d8b6a99e/managed'
        self.assertTrue(interactive_request_allowed('https://hk.jobsdb.com/python-jobs', 'POST', 'https://hk.jobsdb.com' + first))
        self.assertTrue(interactive_request_allowed('https://hk.jobsdb.com/python-jobs', 'POST', 'https://hk.jobsdb.com' + second))
        self.assertFalse(interactive_request_allowed('https://hk.jobsdb.com/python-jobs', 'POST', 'https://hk.jobsdb.com/cdn-cgi/challenge-platform/h/g/fo/apply'))
        self.assertFalse(interactive_request_allowed('https://hk.jobsdb.com/python-jobs', 'POST', 'https://challenges.cloudflare.com/cdn-cgi/challenge-platform/h/g/fo/apply'))
        # Cloudflare's challenge completion reposts the exact authorized list/detail URL; application paths stay blocked.
        self.assertTrue(interactive_request_allowed('https://hk.jobsdb.com/python-jobs', 'POST', 'https://hk.jobsdb.com/python-jobs'))
        self.assertTrue(interactive_request_allowed('https://hk.jobsdb.com/job/12345678', 'POST', 'https://hk.jobsdb.com/job/12345678'))
        self.assertFalse(interactive_request_allowed('https://hk.jobsdb.com/python-jobs', 'POST', 'https://hk.jobsdb.com/job/12345678/apply'))
        self.assertFalse(interactive_request_allowed('https://hk.jobsdb.com/python-jobs', 'POST', 'https://hk.jobsdb.com/job/12345678'))
        self.assertFalse(interactive_request_allowed('https://hk.jobsdb.com/python-jobs', 'POST', 'https://evil.test/python-jobs'))
        self.assertFalse(interactive_request_allowed(page, 'POST', 'https://sub.hcaptcha.com/api/account/settings'))
        self.assertTrue(interactive_request_allowed(page, 'POST', 'https://api.hcaptcha.com/checksiteconfig'))
        self.assertFalse(interactive_request_allowed(page, 'POST', 'https://www.google.com/recaptcha/api2/graphql'))
        self.assertTrue(interactive_request_allowed(page, 'POST', 'https://www.google.com/recaptcha/api2/reload'))

    def test_interactive_browser_never_invokes_the_automatic_solver_or_strict_page_setup(self):
        from types import SimpleNamespace
        from unittest.mock import Mock, patch

        browser = jobsdb.Browser(Path(tempfile.gettempdir()), screenshots=False)
        solver = Mock()
        observed = {}
        def fetch(url, **options):
            observed.update(options)
            options['page_action'](object())
        browser.session = SimpleNamespace(fetch=fetch, _cloudflare_solver=solver)
        with patch.object(jobsdb, 'screenshot') as screenshot:
            self.assertEqual('done', browser.run_interactive(self.url, lambda _: 'done'))
        screenshot.assert_not_called()
        self.assertFalse(observed['solve_cloudflare'])
        self.assertNotIn('page_setup', observed)
        solver.assert_not_called()

    def test_resume_read_uses_the_explicit_interactive_reader_and_writes_only_final_jobs(self):
        import json
        from unittest.mock import patch
        import workbench_read

        with tempfile.TemporaryDirectory() as tmp:
            data = Path(tmp)
            (data / 'scrapling-profile').mkdir()
            file = data / 'task.json'
            result = data / 'result.json'
            bundle = self.bundle() | {'resumeReadId': 'read-attempt-1', 'resultPath': str(result)}
            file.write_text(json.dumps(bundle))
            expected = {'jobs': [{'url': 'https://hk.jobsdb.com/job/12345678', 'title': 'Python engineer',
                                  'company': 'Synthetic Corp', 'description': self.description, 'descriptionComplete': True}]}
            from unittest.mock import MagicMock
            browser = MagicMock()
            browser_type = MagicMock()
            browser_type.return_value.__enter__.return_value = browser
            with patch.object(jobsdb, 'Browser', browser_type), \
                    patch.object(workbench_read, 'wait_for_user_access') as wait, \
                    patch.object(workbench_read, 'read_candidates', return_value=expected) as read, \
                    patch.object(workbench_read.time, 'monotonic', return_value=100.0), \
                    patch.object(workbench_read.time, 'time', side_effect=[1000.0, 1000.5]):
                self.assertEqual(0, jobsdb.main(['resume-read', str(file), '--data-dir', str(data)]))
            browser_type.assert_called_once_with(data, read_only=True, allow_existing_worker=True, screenshots=False)
            browser.session.context.unroute.assert_called_once_with('**/*')
            wait.assert_called_once_with(bundle, browser)
            read.assert_called_once_with(bundle, browser, deadline=110.0)
            self.assertEqual(expected | {'readAttemptCompleted': {'interactionId': 'read-attempt-1',
                'targetUrl': self.url, 'startedAtEpochMillis': 1000000, 'completedAtEpochMillis': 1000500,
                'budgetSeconds': 10}}, json.loads(result.read_text()))
            self.assertEqual(hashlib.sha256(result.read_bytes()).hexdigest(),
                             result.with_name(result.name + '.published').read_text())

    def test_resume_detail_challenge_returns_the_same_attempt_with_reduced_seconds(self):
        import json
        from unittest.mock import MagicMock, patch
        import workbench_read
        with tempfile.TemporaryDirectory() as tmp:
            data = Path(tmp); (data / 'scrapling-profile').mkdir()
            file = data / 'task.json'; result = data / 'result.json'
            bundle = self.bundle() | {'resumeReadId': 'read-attempt-1', 'userActionUrl': self.url, 'resultPath': str(result)}
            file.write_text(json.dumps(bundle))
            browser = MagicMock()
            browser_type = MagicMock()
            browser_type.return_value.__enter__.return_value = browser
            with patch.object(jobsdb, 'Browser', browser_type), \
                    patch.object(workbench_read, 'wait_for_user_access'), \
                    patch.object(workbench_read, 'read_candidates', side_effect=jobsdb.VerificationRequired('detail challenge', jobsdb.SITE + '/job/12345678')), \
                    patch.object(workbench_read.time, 'monotonic', side_effect=[100.0, 101.2]):
                self.assertEqual(0, jobsdb.main(['resume-read', str(file), '--data-dir', str(data)]))
            self.assertEqual({'userActionRequired': {'interactionId': 'read-attempt-1',
                'targetUrl': jobsdb.SITE + '/job/12345678', 'remainingSeconds': 8,
                'message': 'JobsDB 仍要求平台验证或登录；保留同一断点'}}, json.loads(result.read_text()))

    def test_interrupted_strict_resume_leaves_a_timestamped_budget_progress_record(self):
        import json
        from unittest.mock import MagicMock, patch
        import workbench_read
        with tempfile.TemporaryDirectory() as tmp:
            data = Path(tmp); (data / 'scrapling-profile').mkdir()
            file = data / 'task.json'; result = data / 'result.json'
            bundle = self.bundle() | {'resumeReadId': 'read-attempt-1', 'userActionUrl': self.url, 'resultPath': str(result)}
            file.write_text(json.dumps(bundle))
            browser = MagicMock()
            browser_type = MagicMock()
            browser_type.return_value.__enter__.return_value = browser
            with patch.object(jobsdb, 'Browser', browser_type), \
                    patch.object(workbench_read, 'wait_for_user_access'), \
                    patch.object(workbench_read, 'read_candidates', side_effect=RuntimeError('strict browser stopped')), \
                    patch.object(workbench_read.time, 'time', return_value=1234.5):
                with self.assertRaisesRegex(RuntimeError, 'strict browser stopped'):
                    workbench_read.execute_resume(str(file), data)
            self.assertEqual({'readAttemptInProgress': {'interactionId': 'read-attempt-1',
                'targetUrl': self.url, 'startedAtEpochMillis': 1234500}}, json.loads(result.read_text()))

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
