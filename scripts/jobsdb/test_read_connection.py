"""Exercise browser persistence, not authenticated production JobsDB access."""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from tempfile import TemporaryDirectory
from threading import Thread
import unittest

from patchright.sync_api import sync_playwright


class ReadConnectionTests(unittest.TestCase):
    def test_synthetic_session_survives_browser_restarts_but_not_server_revocation(self):
        cookie = 'fixture-session=synthetic-only'
        revoked = False

        class Fixture(BaseHTTPRequestHandler):
            def do_GET(self):
                if self.path == '/seed':
                    self.send_response(200)
                    self.send_header('Set-Cookie', f'{cookie}; Path=/; HttpOnly; Max-Age=600')
                elif self.path == '/probe':
                    valid = not revoked and cookie in self.headers.get('Cookie', '').split('; ')
                    self.send_response(200 if valid else 401)
                else:
                    self.send_response(404)
                self.send_header('Content-Type', 'text/plain')
                self.end_headers()
                self.wfile.write(b'fixture')

            def log_message(self, *_):
                pass

        with ThreadingHTTPServer(('127.0.0.1', 0), Fixture) as server:
            thread = Thread(target=server.serve_forever, daemon=True)
            thread.start()
            base = f'http://127.0.0.1:{server.server_port}'
            try:
                with TemporaryDirectory() as tmp, sync_playwright() as pw:
                    profile = str(Path(tmp) / 'fixture-profile')

                    def check(path, expected):
                        browser = pw.chromium.launch_persistent_context(profile, channel='chrome', headless=True)
                        try:
                            browser.route('**/*', lambda route: route.continue_()
                                          if route.request.url == base + path else route.abort())
                            page = browser.pages[0]
                            self.assertEqual(expected, page.goto(base + path).status)
                        finally:
                            browser.close()

                    check('/seed', 200)
                    check('/probe', 200)
                    check('/probe', 200)
                    revoked = True
                    check('/probe', 401)
            finally:
                server.shutdown()
                thread.join()


if __name__ == '__main__':
    unittest.main()
