"""Isolated, completely intercepted playwright-cli tests for the native OfferToday driver."""
import hashlib
from http.server import BaseHTTPRequestHandler, HTTPServer
import json
import os
from pathlib import Path
import subprocess
import tempfile
import threading
import unittest
import uuid

from offertoday import BrowserBlocked, ExistingBrowserAdapter, discover, run

JOB = 'AbCdEfGhIjKlMnOpQrStUv=='
URL = 'https://www.offertoday.com/hk/job/' + JOB
DATA = b'%PDF-1.4\nfixture only\n%%EOF'
SHA = hashlib.sha256(DATA).hexdigest()
MESSAGE = 'Approved fixture introduction.'
MESSAGE_SHA = hashlib.sha256(MESSAGE.encode()).hexdigest()
HTML = '''<!doctype html><html><head><meta charset="UTF-8"></head><body>
<div id="root"></div><main><h1>Fixture engineer</h1><a href="/hk/company/example">Fixture co</a></main>
<div class="otd-chat-toolbar"><button>Quick actions, not the editor résumé picker</button></div>
<div class="editor-shell"><div class="js-message-input-editor" contenteditable="true"></div>
<div><button aria-label="open-resume"><svg data-testid="resume-icon-2Icon"></svg></button>
<button aria-label="open-resume"><svg data-testid="contact-icon-2Icon"></svg></button></div></div>
<div id="picker" role="dialog" hidden><div id="rows"></div><button id="upload-new">上傳新履歷</button>
<button id="send-cv">發送</button></div>
<div id="upload" role="dialog" hidden><input type="file" accept=".pdf,.docx,.png"><div class="footer"><div class="btns"><button>確認</button></div></div></div>
<script type="module" src="/static/js/index.BKzheqN_.js"></script><script>
window.__fixture = {owner:'own-1', peer:'peer-1', selected:'', title:'Fixture engineer', company:'Fixture co',
  history:[], local:[], attachments:[], selectedResume:null, sends:[], textPersist:true, cvPersist:true,
  uploadCount:0, blockUploads:false};
const S=window.__fixture;
document.addEventListener('keydown',e=>{if(e.key==='Escape'){
 if(!document.querySelector('#upload').hidden)document.querySelector('#upload').hidden=true;
 else document.querySelector('#picker').hidden=true;
 }});
const rows=document.querySelector('#rows');
function render(){rows.replaceChildren(); for(const item of S.attachments){
 const row=document.createElement('div');row.className='resume-row';
 const label=document.createElement('span');label.textContent=item.fileName;row.append(label);
 const radio=document.createElement('svg');radio.setAttribute('width','18');radio.setAttribute('height','18');radio.dataset.testid=S.selectedResume===item.encryptAttachId?'radio-checked-iconIcon':'radio-unchecked-iconIcon';row.append(radio);
 row.addEventListener('click',()=>{S.selectedResume=item.encryptAttachId;render()});rows.append(row);
 }document.querySelector('#upload-new').hidden=S.attachments.length>=3;
}
document.querySelector('button[aria-label="open-resume"]').onclick=()=>{
 S.selectedResume=S.attachments[0]?.encryptAttachId||null;render();
 document.querySelector('#picker').hidden=!S.attachments.length;
 if(!S.attachments.length)document.querySelector('#upload').hidden=false;
};
document.querySelector('#upload-new').onclick=()=>{document.querySelector('#upload').hidden=false};
document.querySelector('#upload input').onchange=async event=>{
 const f=event.target.files[0];S.uploadCount++;
 if(!f||S.blockUploads)return;
 const bytes=await f.arrayBuffer();const hex=[...new Uint8Array(await crypto.subtle.digest('SHA-256',bytes))].map(x=>x.toString(16).padStart(2,'0')).join('');
 S.uploaded={fileName:f.name,originalOssKey:'new-key-'+S.uploadCount,previewOssKey:'preview-key',size:f.size,bytes:[...new Uint8Array(bytes)],sha:hex};
 await fetch('/wapi/geek/mine/attach/upload',{method:'POST',body:new Uint8Array(bytes)});
};
document.querySelector('#upload .footer .btns button').onclick=()=>{
 if(!S.uploaded)return;
 S.attachments.push({encryptAttachId:'new-id-'+S.uploadCount,fileName:S.uploaded.fileName,
 fileSizeDesc:String(S.uploaded.size),size:S.uploaded.size,originalOssKey:S.uploaded.originalOssKey,
 previewOssKey:S.uploaded.previewOssKey,defaulted:false,produceStatus:false,auditInfo:{auditStatus:1},bytes:S.uploaded.bytes});
 S.uploadResult={originalOssKey:S.uploaded.originalOssKey,fileName:S.uploaded.fileName,size:S.uploaded.size};
 document.querySelector('#upload').hidden=true;document.querySelector('#picker').hidden=false;render();
};
document.querySelector('#send-cv').onclick=()=>{
 S.sends.push({kind:'resume',id:S.selectedResume});const id=S.selectedResume;
 if(S.cvPersist)S.history.unshift({messageId:102,addTime:102,seq:102,fromSn:S.owner,toSn:S.peer,
 mediaType:6,type:6,status:0,body:{compressiveCard:{type:10,content:JSON.stringify({resumeId:id,fileName:'approved.pdf',webPreviewUrl:'preview'})}}});
};
document.querySelector('.js-message-input-editor').addEventListener('keydown',e=>{
 if(e.key!=='Enter'||e.shiftKey)return;e.preventDefault();const text=e.currentTarget.textContent;
 S.sends.push({kind:'text',text});S.local.push({messageId:'local-loading',status:100});
 if(S.textPersist)S.history.unshift({messageId:101,addTime:101,seq:101,fromSn:S.owner,toSn:S.peer,
 mediaType:1,type:1,status:0,body:{text:{content:text}}});e.currentTarget.textContent='';
});
</script></body></html>'''
SEARCH_HTML = '''<!doctype html><input name="search_keyword"><div><p>Salary not company</p><div class="jobCardTitle"><a href="''' + URL + '''?ref=fixture">Fixture engineer</a></div><a href="/hk/company/example">Fixture co</a></div>'''
LANG = '''export const s={get(atom){return atom.read()},async set(atom,value){return atom.write(value)},sub(){return()=>{}}};'''
ENTRY = '''const S=window.__fixture;
const cs={read:()=>S.selected,write:async id=>{S.selected=id;return id}};
const cI={read:()=>S.selected};const aD={read:()=>({upsn:S.owner})};const dc={read:()=>S.local};
const I={search:{get_getJobDetail:async()=>({jobId:'%s',jobName:S.title,companyName:S.company,brandName:S.company,userId:S.peer})},
 chat:{getHistoryMsgs:async()=>({hasMore:false,pageSize:30,resultList:S.history.slice(0,30)})},
 account:{getAttachList:async()=>({resume:S.attachments.map(({bytes,...meta})=>meta)}),
 getDownloadAttach:async({encryptAttachId})=>{const a=S.attachments.find(x=>x.encryptAttachId===encryptAttachId);return new Blob([new Uint8Array(a.bytes)])}}};
export {cs,cI,aD,dc,I};''' % JOB


@unittest.skipUnless(os.environ.get('OFFERTODAY_FIXTURE_BROWSER') == '1', 'enable isolated browser fixture explicitly')
class InterceptedBrowserFixture(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name).resolve()
        self.session = 'offertoday-fixture-' + uuid.uuid4().hex[:12]
        self.env = {**os.environ, 'NO_UPDATE_NOTIFIER': '1', 'PLAYWRIGHT_CLI_SESSION': self.session}
        self.cli('open')
        # Intercept EVERY request before navigating, including ESM, API and document requests.
        route = r'''async page => { await page.route('**/*',route=>{const p=route.request().url().split('?')[0].replace(/^https?:\/\/[^/]+/,'');
 if(p==='/static/js/index.BKzheqN_.js')return route.fulfill({status:200,contentType:'text/javascript',body:%s});
 if(p==='/static/js/lang-73AtwcZ1.js')return route.fulfill({status:200,contentType:'text/javascript',body:%s});
 if(p==='/wapi/geek/mine/attach/upload')return route.fulfill({status:200,contentType:'application/json',body:%s});
 if(p.startsWith('/wapi/'))return route.fulfill({status:500,body:'intercepted'});
 return route.fulfill({status:200,contentType:'text/html',body:p.includes('/hk/search')?%s:%s});
 }); await page.goto(%s); return true; }''' % tuple(map(json.dumps, (ENTRY, LANG, json.dumps({'code':0,'message':'','data':{'fileName':'approved.pdf','originalOssKey':'new-key-1','previewOssKey':'preview-key','size':len(DATA)}}), SEARCH_HTML, HTML, 'https://www.offertoday.com/hk/search?q=fixture')))
        self.cli('run-code', route)
        class FixtureBrowser(ExistingBrowserAdapter):
            def require_attached(self):
                pass  # This isolated intercepted session only; never a user browser.
        self.browser = FixtureBrowser(self.root, self.session)

    def tearDown(self):
        try:
            self.cli('close')  # Close only the random isolated fixture session.
        finally:
            self.temp.cleanup()

    def cli(self, *args):
        result = subprocess.run(['playwright-cli', '--json', f'-s={self.session}', *args], cwd=self.root,
                                env=self.env, text=True, capture_output=True, timeout=45)
        self.assertEqual(result.returncode, 0, result.stderr + result.stdout[-1000:])
        self.assertNotIn('### Error', result.stdout)
        return result.stdout

    def state(self, js):
        return json.loads(json.loads(self.cli('run-code', 'async page => page.evaluate(() => {' + js + '})'))['result'])

    def preflight(self):
        return self.browser.action('preflight', jobId=JOB, title='Fixture engineer', company='Fixture co',
                                   resumeFilename='approved.pdf', resumeSha256=SHA, resumeSize=len(DATA))

    def test_discovery_uses_company_anchor_not_salary(self):
        self.assertEqual(discover(self.browser)['jobs'], [{'url':URL,'title':'Fixture engineer', 'company':'Fixture co','description':''}])

    def test_invented_auth_and_receipt_attributes_cannot_authorize_preflight(self):
        self.cli('run-code', '''async page => { await page.route('**/*',r=>r.fulfill({status:200,contentType:'text/html',body:'<main data-authenticated-user="true"><h1>Fixture engineer</h1><div data-company>Fixture co</div><div data-message-id="fake" data-delivery-state="acknowledged"></div></main>'})); await page.goto(''' + json.dumps(URL) + '''); return true; }''')
        with self.assertRaises(BrowserBlocked):
            self.preflight()

    def test_current_page_draft_blocks_navigation_without_discarding_it(self):
        self.state('''const draft=document.createElement('div');draft.className='js-message-input-editor';
          draft.contentEditable='true';draft.textContent='user unsent draft';document.body.append(draft);return true;''')
        with self.assertRaises(BrowserBlocked):
            self.preflight()
        self.assertIn('/hk/search', self.state('return location.pathname'))
        self.assertEqual('user unsent draft', self.state('return document.querySelector(".js-message-input-editor").textContent'))

    def test_same_job_draft_blocks_peer_switch_before_native_preflight(self):
        self.cli('run-code', 'async page => { await page.goto(' + json.dumps(URL) + '); return true; }')
        self.state('''window.__fixture.selected='other-peer';
          document.querySelector('.js-message-input-editor').textContent='Unsent user draft';return true;''')
        with self.assertRaises(BrowserBlocked):
            self.preflight()
        self.assertEqual('other-peer', self.state('return window.__fixture.selected'))
        self.assertEqual('Unsent user draft', self.state('return document.querySelector(".js-message-input-editor").textContent'))

    def test_existing_same_filename_but_wrong_bytes_blocks_before_text(self):
        self.cli('run-code', 'async page => { await page.goto(' + json.dumps(URL) + '); return true; }')
        self.state('''window.__fixture.attachments=[{encryptAttachId:'wrong',fileName:'approved.pdf',
          originalOssKey:'wrong-key',bytes:[9],auditInfo:{auditStatus:1},produceStatus:false}];return true;''')
        with self.assertRaises(BrowserBlocked):
            self.preflight()
        self.assertEqual('[]', self.state('return JSON.stringify(window.__fixture.sends)'))
        self.assertEqual(0, self.state('return window.__fixture.uploadCount'))

    def test_unsafe_numeric_server_message_id_blocks_before_claim(self):
        self.cli('run-code', 'async page => { await page.goto(' + json.dumps(URL) + '); return true; }')
        self.state('''window.__fixture.history=[{messageId:9007199254740992,addTime:10,
          fromSn:'own-1',toSn:'peer-1',status:0,mediaType:1,body:{text:{content:'old'}}}];return true;''')
        with self.assertRaises(BrowserBlocked):
            self.preflight()
        self.assertEqual('[]', self.state('return JSON.stringify(window.__fixture.sends)'))

    def test_native_preflight_from_search_without_upload(self):
        result = self.preflight()
        self.assertEqual(result['conversationId'], 'peer-1')
        self.assertEqual(result['ownerUpsn'], 'own-1')
        self.assertEqual(result['baselineIds'], [])
        self.assertIn('[]', self.state('return JSON.stringify(window.__fixture.sends)'))
        self.assertEqual(0, self.state('return window.__fixture.uploadCount'))

    def test_preflight_waits_for_native_account_editor_and_resume_dialog(self):
        self.cli('run-code', 'async page => { await page.goto(' + json.dumps(URL) + '); return true; }')
        self.state('''const S=window.__fixture,editor=document.querySelector('.js-message-input-editor');
          S.owner=null;setTimeout(()=>{S.owner='own-1'},700);editor.hidden=true;
          import('/static/js/index.BKzheqN_.js').then(m=>{const write=m.cs.write;m.cs.write=async id=>{
            await write(id);setTimeout(()=>{editor.hidden=false},180)};});
          const opener=document.querySelector('button[aria-label="open-resume"]');const open=opener.onclick;
          opener.onclick=()=>setTimeout(open,180);return true;''')
        p = self.preflight()
        self.assertEqual('own-1', p['ownerUpsn'])
        self.assertIsNone(p['resumeId'])
        self.assertEqual(0, self.state('return window.__fixture.uploadCount'))
        self.assertEqual('[]', self.state('return JSON.stringify(window.__fixture.sends)'))
        self.assertTrue(self.state('return document.querySelector("#upload").hidden'))

    def existing(self):
        self.cli('run-code', 'async page => { await page.goto(' + json.dumps(URL) + '); return true; }')
        self.state('''const S=window.__fixture; S.attachments=[
         {encryptAttachId:'wrong-default',fileName:'old-approved.pdf',originalOssKey:'old-key',bytes:[9],
          auditInfo:{auditStatus:1},produceStatus:false,defaulted:true},
         {encryptAttachId:'approved-id',fileName:'approved.pdf',originalOssKey:'approved-key',
          bytes:''' + json.dumps(list(DATA)) + ''',auditInfo:{auditStatus:1},produceStatus:false,defaulted:false}];return true;''')
        return self.preflight()

    def send_text(self, preflight):
        return self.browser.action('send_text', jobId=JOB, title='Fixture engineer', company='Fixture co',
                                   preflight=preflight, message=MESSAGE, messageSha256=MESSAGE_SHA)

    def test_uploaded_resume_boolean_produce_status_matches_observed_client(self):
        self.cli('run-code', 'async page => { await page.goto(' + json.dumps(URL) + '); return true; }')
        self.state('''window.__fixture.attachments=[{encryptAttachId:'approved-id',fileName:'approved.pdf',
          originalOssKey:'approved-key',produceStatus:false,auditInfo:{auditStatus:1},bytes:'''
          + json.dumps(list(DATA)) + '''}];return true;''')
        self.assertEqual('approved-id', self.preflight()['resumeId'])
        self.assertEqual('[]', self.state('return JSON.stringify(window.__fixture.sends)'))

    def test_text_hash_mismatch_does_not_press_enter(self):
        p = self.existing()
        with self.assertRaises(BrowserBlocked):
            self.browser.action('send_text', jobId=JOB, title='Fixture engineer', company='Fixture co',
                                preflight=p, message='different message', messageSha256=MESSAGE_SHA)
        self.assertEqual('[]', self.state('return JSON.stringify(window.__fixture.sends)'))

    def test_claimed_send_does_not_restore_a_user_changed_page(self):
        p = self.existing()
        self.cli('run-code', 'async page => { await page.goto("https://www.offertoday.com/hk/search?q=changed"); return true; }')
        with self.assertRaises(BrowserBlocked):
            self.send_text(p)
        self.assertEqual('/hk/search', self.state('return location.pathname'))

    def test_account_change_during_final_guard_never_sends_text(self):
        p = self.existing()
        self.cli('run-code', '''async page => page.evaluate(async()=>{
          const m=await import('/static/js/index.BKzheqN_.js');
          const get=m.I.account.getAttachList;let calls=0;
          m.I.account.getAttachList=async()=>{const result=await get();
            if(++calls===3)window.__fixture.owner='changed-owner';return result};return true;})''')
        with self.assertRaises(BrowserBlocked):
            self.send_text(p)
        self.assertEqual('[]', self.state('return JSON.stringify(window.__fixture.sends)'))

    def test_local_loading_message_is_not_server_receipt(self):
        p = self.existing()
        self.state('window.__fixture.textPersist=false;return true;')
        with self.assertRaises(BrowserBlocked):
            self.send_text(p)
        self.assertIn('"kind":"text"', self.state('return JSON.stringify(window.__fixture.sends)'))
        self.assertNotIn('"kind":"resume"', self.state('return JSON.stringify(window.__fixture.sends)'))

    def test_fresh_server_text_and_resume_receipts_have_distinct_ids_and_approved_binding(self):
        p = self.existing()
        contact = self.send_text(p)['contact']
        self.assertEqual(contact['messageId'], '101')
        result = self.browser.action('send_resume', jobId=JOB, title='Fixture engineer', company='Fixture co',
                                     preflight=p, contactMessageId=contact['messageId'],
                                     resumePath=str(self.root / 'approved.pdf'), resumeFilename='approved.pdf',
                                     resumeSha256=SHA, resumeSize=len(DATA))
        self.assertEqual(result['cv']['messageId'], '102')
        self.assertEqual(result['cv']['resumeSha256'], SHA)
        self.assertIn('"id":"approved-id"', self.state('return JSON.stringify(window.__fixture.sends)'))

    def test_resume_card_preceding_contact_is_not_this_attempt_receipt(self):
        p = self.existing()
        self.state('''const S=window.__fixture;S.cvPersist=false;
          function prior(e){if(e.key!=='Enter')return;document.removeEventListener('keydown',prior,true);
            S.history.unshift({messageId:99,addTime:101,seq:100,fromSn:S.owner,toSn:S.peer,
              mediaType:6,type:6,status:0,body:{compressiveCard:{type:10,
                content:JSON.stringify({resumeId:'approved-id'})}}});}
          document.addEventListener('keydown',prior,true);return true;''')
        contact = self.send_text(p)['contact']
        self.assertTrue(self.state('return window.__fixture.history.some(r=>r.messageId===99)'))
        with self.assertRaises(BrowserBlocked):
            self.browser.action('send_resume', jobId=JOB, title='Fixture engineer', company='Fixture co',
                                preflight=p, contactMessageId=contact['messageId'], resumePath='/never-upload.pdf',
                                resumeFilename='approved.pdf', resumeSha256=SHA, resumeSize=len(DATA))

    def test_numeric_string_timestamp_cannot_make_old_resume_look_new(self):
        p = self.existing()
        self.state('''const S=window.__fixture;S.cvPersist=false;
          const prepend=S.history.unshift.bind(S.history);
          S.history.unshift=record=>{record.addTime=String(record.addTime);return prepend(record)};
          function prior(e){if(e.key!=='Enter')return;document.removeEventListener('keydown',prior,true);
            S.history.unshift({messageId:99,addTime:99,seq:100,fromSn:S.owner,toSn:S.peer,
              mediaType:6,type:6,status:0,body:{compressiveCard:{type:10,
                content:JSON.stringify({resumeId:'approved-id'})}}});}
          document.addEventListener('keydown',prior,true);return true;''')
        contact = self.send_text(p)['contact']
        self.assertEqual('99', self.state('return window.__fixture.history.find(r=>r.messageId===99).addTime'))
        self.assertEqual('101', self.state('return window.__fixture.history.find(r=>r.messageId===101).addTime'))
        with self.assertRaises(BrowserBlocked):
            self.browser.action('send_resume', jobId=JOB, title='Fixture engineer', company='Fixture co',
                                preflight=p, contactMessageId=contact['messageId'], resumePath='/never-upload.pdf',
                                resumeFilename='approved.pdf', resumeSha256=SHA, resumeSize=len(DATA))

    def test_later_resume_sequence_with_equal_contact_timestamp_is_valid(self):
        p = self.existing()
        contact = self.send_text(p)['contact']
        self.state('''const button=document.querySelector('#send-cv'),send=button.onclick;
          button.onclick=()=>{send();window.__fixture.history.find(r=>r.messageId===102).addTime='101'};return true;''')
        result = self.browser.action('send_resume', jobId=JOB, title='Fixture engineer', company='Fixture co',
                                     preflight=p, contactMessageId=contact['messageId'], resumePath='/never-upload.pdf',
                                     resumeFilename='approved.pdf', resumeSha256=SHA, resumeSize=len(DATA))
        self.assertEqual('102', result['cv']['messageId'])

    def test_new_approved_upload_never_uses_preselected_default(self):
        self.cli('run-code', 'async page => { await page.goto(' + json.dumps(URL) + '); return true; }')
        self.state('''window.__fixture.attachments=[{encryptAttachId:'wrong-default',fileName:'old.pdf',
          originalOssKey:'old-key',bytes:[9],auditInfo:{auditStatus:1},produceStatus:false,defaulted:true}];return true;''')
        p = self.preflight()
        self.assertIsNone(p['resumeId'])
        self.assertEqual(self.state('return window.__fixture.uploadCount'), 0)
        contact = self.send_text(p)['contact']
        approved = self.root / 'approved.pdf'; approved.write_bytes(DATA)
        cv = self.browser.action('send_resume', jobId=JOB, title='Fixture engineer', company='Fixture co',
                                 preflight=p, contactMessageId=contact['messageId'], resumePath=str(approved),
                                 resumeFilename=approved.name, resumeSha256=SHA, resumeSize=len(DATA))['cv']
        self.assertEqual(cv['messageId'], '102')
        self.assertEqual(self.state('return window.__fixture.uploadCount'), 1)
        self.assertIn('"id":"new-id-1"', self.state('return JSON.stringify(window.__fixture.sends)'))
        self.assertNotIn('"id":"wrong-default"', self.state('return JSON.stringify(window.__fixture.sends)'))

    def test_first_resume_upload_uses_upload_flow_then_verified_picker(self):
        p = self.preflight()
        self.assertIsNone(p['resumeId'])
        contact = self.send_text(p)['contact']
        source = self.root / 'approved.pdf'
        source.write_bytes(DATA)
        result = self.browser.action('send_resume', jobId=JOB, title='Fixture engineer', company='Fixture co',
                                     preflight=p, contactMessageId=contact['messageId'], resumePath=str(source),
                                     resumeFilename=source.name, resumeSha256=SHA, resumeSize=len(DATA))
        self.assertEqual('102', result['cv']['messageId'])
        self.assertEqual(1, self.state('return window.__fixture.uploadCount'))
        self.assertIn('"id":"new-id-1"', self.state('return JSON.stringify(window.__fixture.sends)'))

    def test_run_keeps_contacted_unknown_when_resume_server_receipt_missing(self):
        self.existing()
        self.state('window.__fixture.cvPersist=false;return true;')
        events = []
        class Handler(BaseHTTPRequestHandler):
            def do_POST(self):
                event = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
                events.append(event)
                states = {'PROGRESS':'RUNNING' if len(events)==1 else 'UNKNOWN', 'SUBMITTING':'UNKNOWN',
                          'CONTACTED':'UNKNOWN', 'CV_DELIVERED':'SUBMITTED', 'NEEDS_INPUT':'NEEDS_INPUT'}
                body = json.dumps({'id':'fixture-task','state':states[event['type']]}).encode()
                self.send_response(200); self.end_headers(); self.wfile.write(body)
            def log_message(self, *args):
                pass
        with HTTPServer(('127.0.0.1', 0), Handler) as server:
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            try:
                source = self.root / 'approved.pdf'; source.write_bytes(DATA)
                taskfile = self.root / 'task.json'
                taskfile.write_text(json.dumps({'taskId':'fixture-task','token':'fixture-only',
                    'callbackUrl':f'http://127.0.0.1:{server.server_port}/task',
                    'reporterPath':str(Path(__file__).resolve().parents[1] / 'workbench' / 'report.py'),
                    'resumePath':str(source),
                    'task':{'kind':'APPLICATION','channel':'OFFERTODAY','region':'HK','state':'RUNNING',
                        'url':URL,'platformJobId':JOB,'title':'Fixture engineer','company':'Fixture co',
                        'attemptId':'fixture-attempt','snapshot':{'contactVerified':True,'facts':[],
                            'initialMessage':MESSAGE,'initialMessageSha256':MESSAGE_SHA,
                            'resume':{'filename':'approved.pdf','sha256':SHA,'approved':True}}}}))
                self.assertEqual(run(taskfile, self.browser), {'status':'UNKNOWN'})
                self.assertEqual([e['type'] for e in events], ['PROGRESS','SUBMITTING','CONTACTED','PROGRESS'])
                self.assertIn('"kind":"text"', self.state('return JSON.stringify(window.__fixture.sends)'))
                self.assertIn('"kind":"resume"', self.state('return JSON.stringify(window.__fixture.sends)'))
            finally:
                server.shutdown();thread.join()

    def test_preflight_opens_native_peer_and_blocks_wrong_upload_capacity(self):
        self.cli('run-code', 'async page => { await page.goto(' + json.dumps(URL) + '); return true; }')
        self.state('''const S=window.__fixture; S.attachments=[1,2,3].map(n=>({encryptAttachId:'old-'+n,fileName:'other-'+n+'.pdf',originalOssKey:'old-'+n,bytes:[42],auditInfo:{auditStatus:1},produceStatus:false}));return true;''')
        with self.assertRaises(BrowserBlocked):
            self.preflight()
        self.assertIn('[]', self.state('return JSON.stringify(window.__fixture.sends)'))


if __name__ == '__main__':
    unittest.main()
