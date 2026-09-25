import unittest
from batch import screen

class ScreeningTests(unittest.TestCase):
    def test_language_and_career_stage(self):
        self.assertEqual('',screen('Junior AI Engineer','Python and LLMs. Fresh graduates welcome.','Hong Kong'))
        self.assertIn('Cantonese',screen('Junior AI Engineer','Fluent Cantonese and English required.','Hong Kong'))
        self.assertEqual('',screen('Graduate Quantitative Analyst','Cantonese is not required.','Hong Kong'))
        self.assertIn('senior',screen('Senior AI Engineer','LLM development','Hong Kong'))
        self.assertIn('experience',screen('AI Engineer','At least 3 years of experience in AI','Hong Kong'))
        self.assertIn('location',screen('Junior AI Engineer','Python','Shenzhen'))

class ScreeningRegressionTests(unittest.TestCase):
    def test_unrelated_preference_never_weakens_required_qualification(self):
        for desc in ['Fluent Cantonese required; Python preferred.', 'PhD required for this role.', 'At least 3 years of AI experience required; AWS preferred.']:
            with self.subTest(desc=desc):
                self.assertNotEqual('',screen('AI Engineer',desc,'Hong Kong'))

class LocationRegressionTests(unittest.TestCase):
    def test_actual_workplace_overrides_platform_location(self):
        self.assertIn('location',screen('Data Scientist (Computer Science or Equivalent) (深圳 Role)', 'This role is based in Shenzhen Office. Hong Kong Employment & MPF', 'Hong Kong SAR'))
        self.assertIn('location',screen('AI Engineer', 'This role is based in Shenzhen Office. Hong Kong Employment & MPF', 'Hong Kong SAR'))
        self.assertEqual('',screen('Junior AI Engineer', 'Based in Hong Kong; collaborate with our Shenzhen team.', 'Hong Kong SAR'))

class BatchTests(unittest.TestCase):
    def test_batch_skips_duplicate_continues_unknown_and_submits_once(self):
        import tempfile,json
        from pathlib import Path
        from unittest.mock import patch
        import jobsdb,batch
        rows=[{'id':str(i),'title':'Junior AI Engineer','url':'https://hk.jobsdb.com/job/'+str(i)} for i in range(1,5)]
        class Locator:
            def __init__(self,text):self.text=text
            def wait_for(self,**kw):pass
            def inner_text(self):return self.text
        class Page:
            def __init__(self,id):self.id=id;self.url='https://hk.jobsdb.com/job/'+id+'/apply/role-requirements'
            def locator(self,selector):
                return Locator({'[data-automation="jobAdDetails"]':'Fresh graduates welcome, Python AI','[data-automation="job-detail-title"]':'Junior AI Engineer','[data-automation="job-detail-location"]':'Hong Kong'}.get(selector,'Review'))
            def screenshot(self,path):Path(path).write_bytes(b'fixture')
        class Browser:
            solve=None
            def run(self,url,action):
                if '/jobs?' in url:return rows
                return action(Page(url.rsplit('/',1)[-1]))
        class Flow:
            reason='unknown question'
            def __init__(self,page,*args):self.page=page
            def prepare_automatic(self,*args):return 'NEEDS_INPUT' if self.page.id=='2' else 'REVIEW'
            def submit(self):
                self_outer.assertTrue(store.contains(self.page.id))
                return 'SUBMITTED'
        self_outer=self
        config={'searches':['AI'],'pages':1,'max_candidates':4,'max_submissions':1,'answers':{},'upload_path':'/tmp/cv.docx'}
        with tempfile.TemporaryDirectory() as tmp, jobsdb.Store(Path(tmp)/'db') as store:
            store.claim('1')
            with patch.object(jobsdb,'Flow',Flow),patch.object(batch.time,'sleep'):
                report=batch.run(config,Browser(),store,Path(tmp))
            self.assertEqual(['SKIPPED','NEEDS_INPUT','SUBMITTED'],[j['state'] for j in report['jobs']])
            self.assertFalse(store.contains('2'));self.assertFalse(store.contains('4'))
            saved=json.loads(next((Path(tmp)/'batches').glob('*/report.json')).read_text())
            self.assertEqual('FINISHED',saved['status'])
            self.assertEqual('SUBMITTED',saved['jobs'][-1]['state'])

class StudentEligibilityTests(unittest.TestCase):
    def test_explicit_student_only_variants(self):
        for description in ['计算机相关专业在读硕士；优秀大三及以上本科生亦可', 'Pursuing a Master’s degree in Computer Science', 'Currently enrolled in a university programme']:
            with self.subTest(description=description):
                self.assertIn('student',screen('AI System and Application Engineer (Intern)',description,'Hong Kong Island'))

class ConfigTests(unittest.TestCase):
    def test_resume_hash_mismatch_fails_before_browser(self):
        import tempfile,json
        from pathlib import Path
        from batch import load_config
        with tempfile.TemporaryDirectory() as tmp:
            p=Path(tmp);(p/'cv.docx').write_bytes(b'changed')
            (p/'config.json').write_text(json.dumps({'auto_submit_enabled':True,'resume_path':str(p/'cv.docx'),'resume_sha256':'wrong'}))
            with self.assertRaisesRegex(ValueError,'SHA256'):load_config(p/'config.json')
