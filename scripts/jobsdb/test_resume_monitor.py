"""Regression: the outer workbench monitor must let the inner resume supervisor reap its worker."""
import json
import os
import signal
import subprocess
import sys
import tempfile
import time
import unittest
from pathlib import Path

import jobsdb

EXECUTE = jobsdb.ROOT / 'src/main/resources/workbench/execute.py'


@unittest.skipUnless(os.name == 'posix', 'POSIX process groups are required')
class ResumeMonitorTests(unittest.TestCase):
    def test_outer_monitor_waits_for_the_inner_resume_grace_before_group_kill(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            worker = tmp / 'worker.py'
            supervisor = tmp / 'supervisor.py'
            proof = tmp / 'release.json'
            pidfile = tmp / 'worker.pid'
            worker.write_text(
                "import os, signal, sys, time\n"
                "open(sys.argv[1], 'w').write(str(os.getpid()))\n"
                "signal.signal(signal.SIGTERM, lambda *_: None)\n"
                "time.sleep(300)\n")
            supervisor.write_text(
                "import os, signal, subprocess, sys, time\n"
                "pidfile, worker = sys.argv[1], sys.argv[2]\n"
                "child = subprocess.Popen([sys.executable, worker, pidfile], start_new_session=True)\n"
                "def stop(*_):\n"
                "    try: os.killpg(child.pid, signal.SIGTERM)\n"
                "    except ProcessLookupError: pass\n"
                "    time.sleep(2)\n"
                "    try: os.killpg(child.pid, signal.SIGKILL)\n"
                "    except ProcessLookupError: pass\n"
                "    child.wait()\n"
                "    sys.exit(130)\n"
                "signal.signal(signal.SIGTERM, stop)\n"
                "child.wait()\n")
            monitor = subprocess.Popen(
                [sys.executable, str(EXECUTE), str(proof), sys.executable, str(supervisor),
                 str(pidfile), str(worker)],
                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            worker_pid = None
            try:
                deadline = time.monotonic() + 5
                while not pidfile.is_file() and time.monotonic() < deadline:
                    time.sleep(0.02)
                self.assertTrue(pidfile.is_file(), 'fake resume worker did not start')
                worker_pid = int(pidfile.read_text())
                monitor.send_signal(signal.SIGTERM)
                code = monitor.wait(timeout=10)
                self.assertIn(code, (130, 143))
                self.assertTrue(proof.is_file(), 'release proof was not written')
                self.assertTrue(json.loads(proof.read_text())['released'])
                with self.assertRaises(ProcessLookupError):
                    os.kill(worker_pid, 0)
            finally:
                if worker_pid is not None:
                    try:
                        os.killpg(worker_pid, signal.SIGKILL)
                    except ProcessLookupError:
                        pass
                if monitor.poll() is None:
                    monitor.kill()
                    monitor.wait()


if __name__ == '__main__':
    unittest.main()
