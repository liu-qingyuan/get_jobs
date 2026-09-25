"""Configuration-driven JobsDB batches; no stdin, background service or model API."""
import hashlib
import json
from pathlib import Path
import re
import time
from datetime import datetime, timezone
import jobsdb


def screen(title, description, location):
    """Return exclusion reason, or empty string for an early-career AI/quant candidate."""
    if not re.search(r'\bAI\b|artificial intelligence|machine learning|data scien|quant|量化|人工智能|机器学习|機器學習',title,re.I):
        return 'not an AI or quantitative role'
    if re.search(r'\bsenior\b|\blead\b|\bhead\b|\bprincipal\b|\bmanager\b|\bVP\b|資深|资深|高級|高级|主管|總監|总监',title,re.I):
        return 'senior role'
    outside=r'Shenzhen|深圳|Shanghai|上海|Beijing|北京|Guangzhou|廣州|广州|Mainland|內地|内地|Singapore|新加坡'
    if re.search(outside, location+' '+title, re.I):
        return 'location outside Hong Kong'
    workplace = re.search(r'(?:based|located|work(?:ing)?|location|office)\s*(?:is\s+)?(?:in|at|:)?\s*(?:'+outside+r')|(?:工作地[點点]|辦公地[點点]|办公地[点點]|駐|驻)\s*[:：]?\s*(?:'+outside+r')', description, re.I)
    if workplace:
        return 'location outside Hong Kong in job description'
    if not re.search(r'Hong Kong|Kowloon|New Territories|Central|Wan Chai|Eastern|Southern|Sham Shui Po|Yau Tsim Mong|Wong Tai Sin|Kwun Tong|Kwai Tsing|Tsuen Wan|Tuen Mun|Yuen Long|North District|Tai Po|Sha Tin|Sai Kung|Islands|香港|九龍|九龙|新界',location,re.I):
        return 'location not confirmed in Hong Kong'
    if re.search(r'currently (?:enrolled|pursuing|studying)|pursuing\s+(?:a\s+|an\s+)?(?:master|bachelor|undergraduate|postgraduate|degree)|must be (?:a )?(?:current )?student|在[讀读](?:學生|学生|碩士|硕士|博士|本科|研究生)|大三|大四',description,re.I):
        return 'current student eligibility required'
    normalized=re.sub(r'Ph\.?D\.?', 'PhD', description, flags=re.I)
    for line in re.split(r'[\n;；。!?]|\.(?:\s|$)', normalized):
        if re.search(r'Cantonese|粤语|粵語|廣東話|广东话',line,re.I):
            if not re.search(r'(?:Cantonese|粤语|粵語|廣東話|广东话)\s*(?:is\s+)?(?:not required|optional|preferred|an advantage|a plus|非必須|非必须|優先|优先|加分)' ,line,re.I):
                return 'Cantonese requirement or unclear language condition'
        if re.search(r'\bPh\.?D\b|doctorate|doctoral|博士',line,re.I) and not re.search(r'(?:PhD|doctorate|doctoral|博士)\s*(?:is\s+)?(?:preferred|an advantage|a plus|優先|优先)|(?:master|bachelor|MSc|BSc|碩士|硕士)[^;]{0,40}(?:\bor\b|/|或)[^;]{0,20}(?:PhD|博士)',line,re.I):
            return 'doctoral qualification required'
        if re.search(r'experience|經驗|经验',line,re.I):
            match=re.search(r'(\d+)\s*(?:[-–]\s*\d+)?\s*\+?\s*(?:years?|年)',line,re.I)
            if match and int(match.group(1))>1 and not re.search(r'(?:experience|經驗|经验)\s*(?:is\s+)?(?:preferred|an advantage|a plus|優先|优先)|fresh graduates|应届|應屆',line,re.I):
                return 'experience above graduate level'
    return ''


def load_config(path):
    config=json.loads(Path(path).read_text())
    if config.get('auto_submit_enabled') is not True:
        raise ValueError('auto_submit_enabled must be true for a submitting batch')
    resume=Path(config['resume_path']).expanduser().resolve()
    content=resume.read_bytes()
    digest=hashlib.sha256(content).hexdigest()
    if digest!=config['resume_sha256']: raise ValueError('Resume SHA256 mismatch')
    for field,maximum in [('pages',5),('max_candidates',200),('max_submissions',50)]:
        value=config[field]
        if type(value) is not int or not 1<=value<=maximum: raise ValueError('Invalid '+field)
    if not isinstance(config.get('searches'),list) or not config['searches'] or len(config['searches'])>10 or any(not isinstance(s,str) or not s.strip() for s in config['searches']):
        raise ValueError('searches requires 1-10 nonempty terms')
    def valid_answer(value):
        if isinstance(value,str):return bool(value.strip())
        return isinstance(value,list) and bool(value) and all(isinstance(label,str) and label.strip() for label in value) and len(set(value))==len(value)
    if not isinstance(config.get('answers'),dict) or any(not isinstance(k,str) or not k.startswith('questionnaire.') or not valid_answer(v) for k,v in config['answers'].items()):
        raise ValueError('answers must map questionnaire names to an exact label or unique checkbox labels')
    # Never accidentally pick the old default document; name binds immutable bytes.
    upload=jobsdb.DATA/'resumes'/'upload'/(resume.stem+'_'+digest[:12]+resume.suffix)
    upload.parent.mkdir(parents=True,exist_ok=True)
    upload.write_bytes(content)
    config['upload_path']=str(upload)
    return config


def run(config, browser, store, data):
    """Run one bounded batch; persist each outcome, never retry a claimed job."""
    directory=data/'batches'/datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S.%fZ')
    directory.mkdir(parents=True)
    report={'started_at':datetime.now(timezone.utc).isoformat(),'status':'RUNNING','jobs':[], 'search_errors':[]}
    def save():
        path=directory/'report.json'
        path.with_suffix('.tmp').write_text(json.dumps(report,ensure_ascii=False,indent=2))
        path.with_suffix('.tmp').replace(path)
    save()
    rows={}
    try:
        for term in config['searches']:
            for number in range(1,config['pages']+1):
                try:
                    found=browser.run(jobsdb.SITE+'/jobs?'+jobsdb.urlencode({'keywords':term,'page':number}),jobsdb.collect_jobs)
                    for row in found: rows.setdefault(row['id'],row)
                except Exception as exc:
                    report['search_errors'].append({'term':term,'page':number,'error':str(exc).splitlines()[0]});save()
                if len(rows)>=config['max_candidates']:break
            if len(rows)>=config['max_candidates']:break
        report['found']=len(rows);save()
        submissions=0
        for row in list(rows.values())[:config['max_candidates']]:
            if submissions>=config['max_submissions']:break
            result={**row,'state':'PENDING'}
            report['jobs'].append(result)
            if store.contains(row['id']):
                result.update(state='SKIPPED',reason='previous submitted/unknown attempt');save();continue
            def apply(page):
                nonlocal submissions
                detail=page.locator('[data-automation="jobAdDetails"]')
                detail.wait_for(timeout=15000)
                title=page.locator('[data-automation="job-detail-title"]').inner_text()
                location=page.locator('[data-automation="job-detail-location"]').inner_text()
                description=detail.inner_text()
                result['title']=title;result['location']=location
                (directory/(row['id']+'-description.txt')).write_text(description)
                reason=screen(title,description,location)
                if reason:result.update(state='SKIPPED',reason=reason);return
                flow=jobsdb.Flow(page,row['url'],browser.solve)
                state=flow.prepare_automatic(Path(config['upload_path']),config['answers'])
                result.update(state=state,reason=getattr(flow,'reason',''))
                if state!='REVIEW':
                    if not jobsdb.on_site(page.url) or re.search(r'/(?:login|sign-in|signin|auth)(?:/|\?|$)',page.url,re.I):
                        result.update(state='LOGIN_REQUIRED',reason='Saved session requires login')
                    return
                page.screenshot(path=str(directory/(row['id']+'-review.png')))
                (directory/(row['id']+'-review.txt')).write_text(page.locator('body').inner_text())
                if not store.claim(row['id']):result.update(state='SKIPPED',reason='already claimed');return
                result['state']='UNKNOWN';save()
                # The limit counts attempts, including unknown outcomes.
                submissions+=1
                outcome=flow.submit()
                if outcome!='SUBMITTED':outcome='UNKNOWN'
                store.finish(row['id'],outcome)
                result.update(state=outcome,reason='')
                save()
                (directory/(row['id']+'-result.txt')).write_text(page.locator('body').inner_text())
                page.screenshot(path=str(directory/(row['id']+'-result.png')))
            try: browser.run(row['url'],apply)
            except Exception as exc:
                if result['state'] not in ('SUBMITTED','UNKNOWN'):
                    result.update(state='ERROR',reason=str(exc).splitlines()[0])
                else:result['evidence_error']=str(exc).splitlines()[0]
            save()
            print(row['id']+' '+result['state']+' '+result.get('reason',''),flush=True)
            if result['state']=='LOGIN_REQUIRED':
                report['status']='LOGIN_REQUIRED'
                break
            time.sleep(2)
        if report['status']=='RUNNING': report['status']='FINISHED'
    except BaseException:
        report['status']='INTERRUPTED';raise
    finally:
        report['finished_at']=datetime.now(timezone.utc).isoformat();save()
    print('REPORT='+str(directory/'report.json'),flush=True)
    return report
