import json
from pathlib import Path
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, HTTPServer
from urllib.error import HTTPError
from report import main, report


class ReporterTest(unittest.TestCase):
    def test_local_callback_only_returns_task_status(self):
        observed = []
        class Handler(BaseHTTPRequestHandler):
            def do_POST(self):
                observed.append((self.headers['Authorization'], json.loads(self.rfile.read(int(self.headers['Content-Length'])))))
                data = json.dumps({'id': 'fixture-task', 'state': 'UNKNOWN', 'snapshot': {'private': 'not-returned'}}).encode()
                self.send_response(200); self.end_headers(); self.wfile.write(data)
            def log_message(self, *args):
                pass
        with HTTPServer(('127.0.0.1', 0), Handler) as server, tempfile.TemporaryDirectory() as directory:
            thread = threading.Thread(target=server.serve_forever, daemon=True); thread.start()
            bundle = Path(directory) / 'task.json'
            bundle.write_text(json.dumps({'callbackUrl': 'http://127.0.0.1:%s/task' % server.server_port, 'token': 'fixture-only'}))
            try:
                self.assertEqual({'taskId': 'fixture-task', 'state': 'UNKNOWN'}, report(bundle, 'SUBMITTING', 'fixture claim'))
                self.assertEqual('Bearer fixture-only', observed[0][0])
                self.assertEqual('SUBMITTING', observed[0][1]['type'])
            finally:
                server.shutdown(); thread.join()

    def test_offertoday_receipt_events_are_accepted_by_reporter_cli(self):
        observed = []
        class Handler(BaseHTTPRequestHandler):
            def do_POST(self):
                observed.append(json.loads(self.rfile.read(int(self.headers['Content-Length'])))['type'])
                data = json.dumps({'id': 'fixture-task', 'state': 'UNKNOWN'}).encode()
                self.send_response(200); self.end_headers(); self.wfile.write(data)
            def log_message(self, *args):
                pass
        with HTTPServer(('127.0.0.1', 0), Handler) as server, tempfile.TemporaryDirectory() as directory:
            thread = threading.Thread(target=server.serve_forever, daemon=True); thread.start()
            bundle = Path(directory) / 'task.json'
            bundle.write_text(json.dumps({'callbackUrl': f'http://127.0.0.1:{server.server_port}/task', 'token': 'fixture-only'}))
            try:
                for event in ('CONTACTED', 'CV_DELIVERED'):
                    self.assertEqual(0, main([str(bundle), event, 'fixture', '--evidence', '{}']))
                self.assertEqual(['CONTACTED', 'CV_DELIVERED'], observed)
            finally:
                server.shutdown(); thread.join()

    def test_remote_callback_rejected_before_network(self):
        with tempfile.TemporaryDirectory() as directory:
            bundle = Path(directory) / 'task.json'
            bundle.write_text(json.dumps({'callbackUrl': 'https://example.invalid/steal', 'token': 'fixture-only'}))
            with self.assertRaises(ValueError):
                report(bundle, 'PROGRESS', 'must never send credential')

    def test_redirect_is_not_followed_with_task_credential(self):
        paths = []
        class Handler(BaseHTTPRequestHandler):
            def do_POST(self):
                paths.append(self.path)
                self.send_response(307); self.send_header('Location', '/credential-leak'); self.end_headers()
            def log_message(self, *args):
                pass
        with HTTPServer(('127.0.0.1', 0), Handler) as server, tempfile.TemporaryDirectory() as directory:
            thread = threading.Thread(target=server.serve_forever, daemon=True); thread.start()
            bundle = Path(directory) / 'task.json'
            bundle.write_text(json.dumps({'callbackUrl': 'http://127.0.0.1:%s/task' % server.server_port, 'token': 'fixture-only'}))
            try:
                with self.assertRaises(HTTPError): report(bundle, 'PROGRESS', 'fixture')
                self.assertEqual(['/task'], paths)
            finally:
                server.shutdown(); thread.join()


if __name__ == '__main__':
    unittest.main()
