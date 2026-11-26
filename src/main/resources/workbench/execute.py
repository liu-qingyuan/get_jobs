"""Private POSIX executor lifetime: keep a group leader alive until every command descendant is drained.

The outer monitor is deliberately outside that group. Java never forcibly kills it or assumes its
exit proves cleanup: only its private release proof permits reuse of the shared browser slot.
"""
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import time


def active_group(group):
    output = subprocess.run(['/bin/ps', '-axo', 'pgid=,stat='], check=True, capture_output=True, text=True).stdout
    return any(int(fields[0]) == group and not fields[1].startswith('Z')
               for line in output.splitlines() if len(fields := line.split()) == 2)


def main():
    if os.name != 'posix':
        raise RuntimeError('Controlled workflow execution requires POSIX process groups')
    os.umask(0o077)
    leader_mode = sys.argv[1] == '--leader'
    proof = Path(sys.argv[2] if leader_mode else sys.argv[1])
    status = proof.with_suffix('.status')
    command = sys.argv[3:] if leader_mode else sys.argv[2:]
    if leader_mode:
        # A caught (not SIG_IGN) signal is reset on exec, so the actual command keeps normal signals.
        signal.signal(signal.SIGTERM, lambda *_: None)
        signal.signal(signal.SIGINT, lambda *_: None)
        try:
            code = subprocess.Popen(command).wait()
        except OSError:
            code = 127
        status.write_text(json.dumps({'exitCode': code}))
        # Keep our group ID allocated even after a very short-lived launcher has exited.
        while True:
            time.sleep(1)

    stopping = False
    def stop(*_):
        nonlocal stopping
        stopping = True
    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)
    leader = subprocess.Popen([sys.executable, __file__, '--leader', str(proof), *command], start_new_session=True)
    try:
        while not stopping and leader.poll() is None and not status.exists():
            time.sleep(0.02)
        code = json.loads(status.read_text())['exitCode'] if status.exists() else 143
        if leader.poll() is not None:
            raise RuntimeError('Group leader disappeared before controlled cleanup')
        # The still-live leader owns this exact private group; never signal a recycled PID/group.
        # On an explicit stop, some channel supervisors put their browser worker in its own session
        # and need up to two seconds to reap that group. Never SIGKILL the outer group before that
        # inner grace expires, or the surviving worker keeps the profile lock while this monitor
        # writes its release proof. A normally finished command has no such worker and needs only
        # the short group teardown, so successful executions are not delayed.
        os.killpg(leader.pid, signal.SIGTERM)
        time.sleep(3 if stopping else 0.2)
        os.killpg(leader.pid, signal.SIGKILL)
        leader.wait()
        while active_group(leader.pid):
            time.sleep(0.02)
        proof.write_text(json.dumps({'released': True, 'exitCode': code}))
        return code
    except Exception as exc:
        print('EXECUTOR_RESOURCE_UNCONFIRMED: ' + str(exc), file=sys.stderr, flush=True)
        # Fail closed. Without a release proof Java must not forcibly kill this monitor and reuse
        # the slot. This is a resource exception requiring inspection, not permission to retry.
        while True:
            time.sleep(1)


if __name__ == '__main__':
    sys.exit(main())
