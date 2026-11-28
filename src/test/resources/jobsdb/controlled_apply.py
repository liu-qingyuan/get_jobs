"""Local form and platform doubles. Execute the real CLI, callbacks, file lock and SQLite claim."""
import hashlib
import json
import os
from pathlib import Path
import sys
import time
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[4] / 'scripts/jobsdb'))
import jobsdb


def apply(root, description, scenario=''):
    task = json.loads(Path(sys.argv[2]).read_text())
    data = Path(sys.argv[4])
    if os.environ.get('WORKBENCH_TASK_FILE'):
        (root/'dispatch-file').write_text(sys.argv[2])
        if (root/'direct-first').exists():
            while not (root/'direct-finished').exists(): time.sleep(.02)
    assert task['schemaVersion'] == 1
    assert task['taskId'] == task['attemptId'] and task['stage'] == 'APPLY'
    assert task['approvedFacts'] and 'token' in task
    class Page:
        url = task['url'] + '/apply/success'
        def locator(self, selector): return self
        def inner_text(self):
            if scenario == 'LOST_RECEIPT' and (root/'effects').exists():
                raise ValueError('Fixture page closed before receipt could be read')
            return 'Your application has been submitted'

    class Browser:
        def __init__(self, data): pass
        def __enter__(self): return self
        def __exit__(self, *_): pass
        def solve(self, page): pass
        def run(self, url, action): return action(Page())
        def observe_execution_context(self, page, binding):
            return dict(jdSha256=hashlib.sha256(description.encode()).hexdigest(), accountHash='c'*64, sessionHash='d'*64)
        def read_role_requirements(self, page, job):
            questions = [dict(q, required=True) for q in task['answerBank']['entries']]
            if scenario.startswith('QUESTIONS_'):
                q = questions[0]
                if scenario == 'QUESTIONS_UNKNOWN': q['name'] = 'questionnaire.unknown'
                if scenario == 'QUESTIONS_LABEL': q['label'] += '?'
                if scenario == 'QUESTIONS_TYPE': q['type'] = 'radio'
                if scenario == 'QUESTIONS_OPTIONS': q['options'] += ['Extra']
                if scenario == 'QUESTIONS_AMBIGUOUS': q['options'] *= 2
                if scenario == 'QUESTIONS_NO_OBSERVER': raise ValueError('Unreadable fixture questionnaire')
            return dict(jobId='other' if scenario == 'QUESTIONS_JOB' else job,
                        complete=scenario != 'QUESTIONS_INCOMPLETE', questions=questions)

    class Form:
        reason = ''
        application_receipt = received_attachment = None
        def __init__(self, page, url, solve): self.page = page
        def prepare_automatic(self, resume, answers, *, before_upload, before_disclosure, on_questions):
            contents = before_upload()
            (root/'uploaded').write_bytes(contents)
            assert hashlib.sha256(contents).hexdigest() == task['resumeSha256']
            if scenario == 'CRASH_AFTER_UPLOAD': os._exit(3)
            self.selected_attachment = dict(name=resume.name, selected=True, resumeId='doc-1')
            on_questions(self.page)
            before_disclosure()
            return 'REVIEW'
        def review_resume_id(self):
            if scenario == 'REVIEW_MISSING': return None
            if scenario == 'REVIEW_WRONG': return 'prior-resume'
            return 'doc-1'
        def submit(self):
            with jobsdb.Store(data/'applications.db') as store:
                claim = store.attempt(task['jobId'])
                assert claim['attemptId'] == task['attemptId']
            result = json.loads(Path(task['resultPath']).read_text())
            with (root/'effects').open('a') as effects:
                effects.write(json.dumps(dict(taskId=task['taskId'], attemptId=claim['attemptId'], action='APPLY',
                                              claimId=result['authorizationId'], nativeClaimed=True)) + '\n')
            if (root/'hold').exists():
                (root/'held').write_text(task['taskId'])
                while not (root/'release').exists(): time.sleep(.02)
            if scenario.startswith('CV_'):
                # The fake application owns its own retained copy. Upload alone
                # can succeed even when this application's attachment is absent.
                received = root/'applications'/'app-7'/'received.docx'
                if scenario != 'CV_MISSING':
                    received.parent.mkdir(parents=True)
                    received.write_bytes(b'other attachment' if scenario == 'CV_WRONG_BYTES' else (root/'uploaded').read_bytes())
                # The fixture platform records the submitted application and received
                # bytes independently of the local result envelope and success page.
                self.application_receipt = dict(jobId=task['jobId'], attemptId=claim['attemptId'], applicationId='app-7')
                if scenario != 'CV_MISSING':
                    self.received_attachment = dict(jobId=task['jobId'], receiptId='received-4',
                        applicationId='different' if scenario == 'CV_WRONG' else 'app-7',
                        attemptId='previous-attempt' if scenario == 'CV_WRONG_ATTEMPT' else claim['attemptId'],
                        sha256=hashlib.sha256(received.read_bytes()).hexdigest())
            return 'UNKNOWN' if scenario == 'UNKNOWN' else 'SUBMITTED'

    with patch.object(jobsdb, 'Browser', Browser), patch.object(jobsdb, 'Flow', Form):
        original_claim = jobsdb.Store.claim
        def claim(store, *args):
            claimed = original_claim(store, *args)
            if claimed and scenario == 'CRASH_AFTER_CLAIM': os._exit(3)
            return claimed
        with patch.object(jobsdb.Store, 'claim', claim):
            status = jobsdb.main(sys.argv[1:])
    if os.environ.get('WORKBENCH_TASK_FILE') and (root/'hold-receipt').exists():
        (root/'accepted').write_text('submitted; worker credential still active')
        while not (root/'release').exists(): time.sleep(.02)
    if scenario in ('MISBOUND_RECEIPT', 'BAD_VERSION', 'CRASH_WITH_RESULT'):
        path = Path(task['resultPath'])
        result = json.loads(path.read_text())
        if scenario == 'CRASH_WITH_RESULT':
            return 3
        if scenario == 'BAD_VERSION':
            result['schemaVersion'] = 2
        else:
            result['resumeSha256'] = '0'*64
        path.write_text(json.dumps(result))
    return status
