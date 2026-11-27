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

from fixtures.delivery_receiver import DeliveryReceiver
from offertoday import BrowserBlocked, ExistingBrowserAdapter, discover, run

JOB = 'AbCdEfGhIjKlMnOpQrStUv=='
URL = 'https://www.offertoday.com/hk/job/' + JOB
DATA = b'%PDF-1.4\nfixture only\n%%EOF'
SHA = hashlib.sha256(DATA).hexdigest()
MESSAGE = 'Approved fixture introduction.'
MESSAGE_SHA = hashlib.sha256(MESSAGE.encode()).hexdigest()
TEXT_SYNC = json.loads(Path(__file__).with_name('fixtures').joinpath('published-text-sync.json').read_text())
CLIENT_MESSAGE_ID = TEXT_SYNC['matchingSync']['syncMessage']['clientMessageId']
PROOF_PATH = '/static/js/fixture-dispatch-proof.js'
# The fake native client independently acknowledges the exact event. History writes alone cannot set this map.
PROOF_MODULE = '''const S=window.__fixture;
export const selectedResume = () => S.planResume(S.selectedResume);
export async function pinResume({resumeId, originalOssKey}) {
  const ref = S.planResume(resumeId);
  if (!ref || ref.originalOssKey !== originalOssKey) return null;
  const blob = await S.account.getDownloadAttach({encryptAttachId:resumeId, version:ref.version});
  return {...ref, blob};
}
export const historyResumeRef = record => JSON.parse(record.body.compressiveCard.content);
export const pendingUpload = () => S.uploaded;
export function guardNative({ target, kind, permit, stillAuthorized }) {
  const slot = kind === 'upload' ? 'onchange' : kind === 'text' ? 'onkeydown' : 'onclick';
  const handler = target[slot];
  if (typeof handler !== 'function') return null;
  const guarded = function(event) {
    if (kind === 'text' && event.key !== 'Enter') return handler.call(this, event);
    const request = S.prepareNativeRequest(kind, target);
    if (!permit(event, request)) { event.preventDefault(); event.stopImmediatePropagation(); return; }
    S.nativeAuthorization.set(event, actual => stillAuthorized(event, actual));
    S.nativeRequests.set(event, request);
    return handler.call(this, event);
  };
  target[slot] = guarded;
  return () => { if (target[slot] === guarded) target[slot] = handler; };
}
export const begin = ({event}) => ({
  acknowledgement: () => Promise.resolve(S.dispatchReceipts.get(event)),
  release: () => S.dispatchReceipts.delete(event)
});'''
PUBLIC_JOBS = json.loads(Path(__file__).with_name('fixtures').joinpath('public-job-availability.json').read_text())
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
  dispatchReceipts:new WeakMap(), nativeAuthorization:new WeakMap(), nativeRequests:new WeakMap(),
  resumeHeads:new Map(), resumeVersions:new Map(), uploadCount:0, uploadRequestCount:0, blockUploads:false};
const S=window.__fixture;
const textSync=__TEXT_SYNC__;
// Each write changes the fixture's mutable attachment head. The fake server retains immutable versions.
S.planResume = id => {
 const item=S.attachments.find(a=>a.encryptAttachId===id);
 if(!item)return null;
 if(S.receiverRef) return item.encryptAttachId===S.receiverRef.resumeId ? S.receiverRef : null;
 const previous=S.resumeHeads.get(id);
 if(previous && previous.ref.originalOssKey===item.originalOssKey && previous.bytes.length===item.bytes.length &&
    previous.bytes.every((byte,index)=>byte===item.bytes[index]))return previous.ref;
 const ref=Object.freeze({resumeId:id,originalOssKey:item.originalOssKey,version:'fixture-version-'+(S.resumeVersions.size+1)});
 const snapshot=Object.freeze({ref,bytes:Object.freeze(item.bytes.slice())});
 S.resumeVersions.set(ref.version,snapshot);S.resumeHeads.set(id,snapshot);
 return ref;
};
S.prepareResumeRequest = () => S.planResume(S.selectedResume);
S.prepareNativeRequest = (kind, target) => Object.freeze({
  jobId:location.pathname.split('/').at(-1),owner:S.owner,peer:S.selected,
  ...(kind === 'text' ? {text:S.nativeDraft ?? target.textContent,clientMessageId:__CLIENT_ID__} :
      kind === 'resume' ? {resume:S.prepareResumeRequest()} :
      kind === 'upload' ? {file:target.files[0]} : {upload:Object.freeze({...S.uploaded})})
});
S.postDispatch = (event, kind, request) => S.dispatchReceipts.set(event,
 fetch('/__fixture_delivery__/'+kind, {method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(request)})
   .then(async response=>response.ok ? (kind==='text' ? new Uint8Array(await response.arrayBuffer()) : response.json())
     : null).catch(()=>null));
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
 const request=S.nativeRequests.get(event) ?? S.prepareNativeRequest('upload',event.target);
 const {file:f}=request;S.uploadCount++;
 if(!f||S.blockUploads)return;
 const bytes=await f.arrayBuffer();const hex=[...new Uint8Array(await crypto.subtle.digest('SHA-256',bytes))].map(x=>x.toString(16).padStart(2,'0')).join('');
 const authorize=S.nativeAuthorization.get(event);
 if(authorize && !authorize(request))return;
 S.uploadRequestCount++;
 S.uploaded={fileName:f.name,originalOssKey:'new-key-'+S.uploadCount,previewOssKey:'preview-key',size:f.size,bytes:Object.freeze([...new Uint8Array(bytes)]),sha:hex};
 await fetch('/wapi/geek/mine/attach/upload',{method:'POST',body:new Uint8Array(bytes)});
};
document.querySelector('#upload .footer .btns button').onclick=event=>{
 const request=S.nativeRequests.get(event) ?? S.prepareNativeRequest('save',event.target);
 const authorize=S.nativeAuthorization.get(event), upload=request.upload;
 if(!upload?.originalOssKey || (authorize && !authorize(request)))return;
 S.attachments.push({encryptAttachId:'new-id-'+S.uploadCount,fileName:upload.fileName,
 fileSizeDesc:String(upload.size),size:upload.size,originalOssKey:upload.originalOssKey,
 previewOssKey:upload.previewOssKey,defaulted:false,produceStatus:false,auditInfo:{auditStatus:1},bytes:upload.bytes});
 S.uploadResult={originalOssKey:upload.originalOssKey,fileName:upload.fileName,size:upload.size};
 document.querySelector('#upload').hidden=true;document.querySelector('#picker').hidden=false;render();
};
document.querySelector('#send-cv').onclick=event=>{
 const request=S.nativeRequests.get(event) ?? S.prepareNativeRequest('resume',event.target);
 const authorize=S.nativeAuthorization.get(event), ref=request.resume;
 if(!ref || (authorize && !authorize(request)))return;
 S.sends.push({kind:'resume',id:ref.resumeId});
 if(S.receiverRef){S.postDispatch(event,'resume',request);return;}
 const snapshot=S.resumeVersions.get(ref.version);
 S.receivedCvBytes=snapshot.bytes.slice();
 if(S.cvPersist){S.history.unshift({messageId:102,addTime:102,seq:102,fromSn:S.owner,toSn:S.peer,
 mediaType:6,type:6,status:0,body:{compressiveCard:{type:10,content:JSON.stringify(snapshot.ref)}}});
 S.dispatchReceipts.set(event,{messageId:102,resume:snapshot.ref});}
};
S.dispatchText = (e, request) => {
 const authorize=S.nativeAuthorization.get(e);
 if(authorize && !authorize(request))return;
 const {text}=request;
 S.sends.push({kind:'text',text});S.local.push({messageId:'local-loading',status:100});
 if(S.receiverRef) S.postDispatch(e,'text',request);
 else if(S.textPersist){S.history.unshift({messageId:101,addTime:101,seq:101,fromSn:S.owner,toSn:S.peer,
 clientMessageId:request.clientMessageId,mediaType:1,type:1,status:0,body:{text:{content:text}}});
 S.dispatchReceipts.set(e,Uint8Array.from(textSync));}
};
document.querySelector('.js-message-input-editor').onkeydown=e=>{
 if(e.key!=='Enter'||e.shiftKey)return;e.preventDefault();
 const request=S.nativeRequests.get(e) ?? S.prepareNativeRequest('text',e.currentTarget);
 S.dispatchText(e, request);
 e.currentTarget.textContent='';
};
</script></body></html>'''.replace('__TEXT_SYNC__', json.dumps(TEXT_SYNC['frames']['matchingSync'])).replace('__CLIENT_ID__', str(CLIENT_MESSAGE_ID))
SEARCH_HTML = HTML.replace('<div id="root"></div>', '''<input name="search_keyword"><div><p>Salary not company</p><div class="jobCardTitle"><a href="''' + URL + '''?ref=fixture">Fixture engineer</a></div><a href="/hk/company/example">Fixture co</a></div>''')
LANG = '''export const s={get(atom){return atom.read()},async set(atom,value){return atom.write(value)},sub(){return()=>{}}};'''
ENTRY = '''const S=window.__fixture;
const cs={read:()=>S.selected,write:async id=>{S.selected=id;return id}};
const cI={read:()=>S.selected};const aD={read:()=>({upsn:S.owner})};const dc={read:()=>S.local};
const I={search:{get_getJobDetail:async()=>S.jobReplay??({jobId:'%s',jobStatus:S.jobStatus===undefined?2:S.jobStatus,jobName:S.title,companyName:S.company,brandName:S.company,userId:S.peer,jobDesc:S.description===undefined?'Responsibilities: build and test AI applications with Python, maintain evaluation pipelines and document delivery. Requirements: demonstrable programming projects and clear technical communication.':S.description})},
 chat:{getHistoryMsgs:async({friendId})=>{
   if(!S.receiverRef)return {hasMore:false,pageSize:30,resultList:S.history.slice(0,30)};
   const response=await fetch('/__fixture_delivery__/history?peer='+encodeURIComponent(friendId));
   return response.ok ? response.json() : null;
 }},
 account:{getAttachList:async()=>({resume:S.attachments.map(({bytes,...meta})=>meta)}),
 getDownloadAttach:async({encryptAttachId,version})=>{
   if(S.receiverRef && version){
     const response=await fetch('/__fixture_delivery__/asset?'+new URLSearchParams({resumeId:encryptAttachId,version}));
     return response.ok ? response.blob() : null;
   }
   const snapshot=version ? S.resumeVersions.get(version) : null;
   const bytes=version ? (snapshot?.ref.resumeId===encryptAttachId ? snapshot.bytes : null) :
     S.attachments.find(x=>x.encryptAttachId===encryptAttachId)?.bytes;
   return bytes ? new Blob([new Uint8Array(bytes)]) : null;
 }}};
S.account=I.account;
export {cs,cI,aD,dc,I};''' % JOB


@unittest.skipUnless(os.environ.get('OFFERTODAY_FIXTURE_BROWSER') == '1', 'enable isolated browser fixture explicitly')
class InterceptedBrowserFixture(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name).resolve()
        self.session = 'offertoday-fixture-' + uuid.uuid4().hex[:12]
        self.env = {**os.environ, 'NO_UPDATE_NOTIFIER': '1', 'PLAYWRIGHT_CLI_SESSION': self.session}
        self.cli('open')
        use_current = self._testMethodName == 'test_new_native_bindings_complete_an_intercepted_two_stage_qualification'
        legacy_entry = '/static/js/index.BKzheqN_.js'
        entry = '/static/js/index.D4zvvLnm.js' if use_current else legacy_entry
        lang = '/static/js/lang-DMtzyqWP.js' if use_current else '/static/js/lang-73AtwcZ1.js'
        # Intercept EVERY request before navigating, including ESM, API and document requests.
        route = r'''async page => { await page.route('**/*',route=>{const p=route.request().url().split('?')[0].replace(/^https?:\/\/[^/]+/,'');
 if(p===%s)return route.fulfill({status:200,contentType:'text/javascript',body:%s});
 if(p===%s)return route.fulfill({status:200,contentType:'text/javascript',body:%s});
 if(p===%s)return route.fulfill({status:200,contentType:'text/javascript',body:%s});
 if(p==='/wapi/geek/mine/attach/upload')return route.fulfill({status:200,contentType:'application/json',body:%s});
 if(p.startsWith('/wapi/') || p.endsWith('.js'))return route.fulfill({status:500,body:'intercepted'});
 return route.fulfill({status:200,contentType:'text/html',body:p.includes('/hk/search')?%s:%s});
 }); await page.goto(%s); return true; }''' % tuple(map(json.dumps, (entry, ENTRY, lang, LANG, PROOF_PATH, PROOF_MODULE,
            json.dumps({'code':0,'message':'','data':{'fileName':'approved.pdf','originalOssKey':'new-key-1','previewOssKey':'preview-key','size':len(DATA)}}),
            SEARCH_HTML.replace(legacy_entry, entry), HTML.replace(legacy_entry, entry), PUBLIC_JOBS['open']['url'] if use_current else 'https://www.offertoday.com/hk/search?q=fixture')))
        self.cli('run-code', route)
        class FixtureBrowser(ExistingBrowserAdapter):
            def require_attached(self):
                pass  # This isolated intercepted session only; never a user browser.
        self.unqualified = FixtureBrowser(self.root, self.session)
        class QualifiedFixtureBrowser(FixtureBrowser):
            def _cli(self, *args, **kwargs):
                if args[0] == 'run-code':
                    marker = 'const dispatchProofByBuild = { legacy: null, current: null };'
                    assert args[1].count(marker) == 1
                    field = 'current' if use_current else 'legacy'
                    replacement = 'const dispatchProofByBuild = ' + json.dumps({field: PROOF_PATH}) + ';'
                    args = (args[0], args[1].replace(marker, replacement))
                return super()._cli(*args, **kwargs)
        self.browser = QualifiedFixtureBrowser(self.root, self.session)

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

    def start_delivery_receiver(self, job_id=JOB):
        receiver = DeliveryReceiver(job_id, MESSAGE, DATA)
        self.addCleanup(receiver.close)
        self.cli('run-code', '''async page => {
          await page.route('https://www.offertoday.com/__fixture_delivery__/**', async route => {
            const origin = 'https://www.offertoday.com', url = route.request().url();
            const path = url.slice(origin.length);
            if (!url.startsWith(origin + '/') ||
                !['text','resume','asset','history'].some(name=>path.split('?')[0] === '/__fixture_delivery__/'+name))
              return route.abort();
            const response = await route.fetch({url: ''' + json.dumps(receiver.url) + ''' + path,
              headers: {'Content-Type':'application/json'}, maxRedirects: 0, timeout: 2000});
            return route.fulfill({response});
          }); return true;
        }''')
        return receiver

    def preflight(self):
        return self.browser.action('preflight', jobId=JOB, title='Fixture engineer', company='Fixture co',
                                   resumeFilename='approved.pdf', resumeSha256=SHA, resumeSize=len(DATA))

    def test_discovery_uses_company_anchor_not_salary(self):
        self.assertEqual(discover(self.browser)['jobs'], [{'url':URL,'title':'Fixture engineer', 'company':'Fixture co','description':''}])

    def test_candidate_page_reads_native_full_details_without_opening_chat_or_picker(self):
        result = self.browser.action('candidate_page', sourceUrl='https://www.offertoday.com/hk/search?q=fixture', maxJobs=2, cursor='')
        self.assertEqual(1, len(result['jobs']))
        self.assertTrue(result['jobs'][0]['descriptionComplete'])
        self.assertGreater(len(result['jobs'][0]['description']), 120)
        self.assertEqual('', self.state('return window.__fixture.selected'))
        self.assertEqual('[]', self.state('return JSON.stringify(window.__fixture.sends)'))
        self.assertEqual(0, self.state('return window.__fixture.uploadCount'))
        self.assertTrue(self.state('return document.querySelector("#picker").hidden'))

    def test_legacy_candidate_availability_requires_numeric_open_state(self):
        open_status = PUBLIC_JOBS['open']['job']['jobStatus']
        closed_status = PUBLIC_JOBS['closed']['job']['jobStatus']
        for status, expected in ((open_status, True), (closed_status, False), ('2', None), (None, None)):
            with self.subTest(status=status):
                # The captured closed response's jobName has a trailing space while its title is visible without it.
                self.state('Object.assign(window.__fixture,{jobStatus:' + json.dumps(status) +
                           ',title:"Fixture engineer "});return true;')
                result = self.browser.action('candidate_page', sourceUrl='https://www.offertoday.com/hk/search?q=fixture', maxJobs=1, cursor='')
                self.assertEqual(expected, result['jobs'][0]['available'])
                self.assertTrue(result['jobs'][0]['descriptionComplete'], 'Retain the complete JD even when not matchable')
        self.assertEqual('', self.state('return window.__fixture.selected'))
        self.assertEqual([], self.state('return window.__fixture.sends'))

    def test_legacy_public_closed_response_stops_before_chat_or_claim(self):
        closed = PUBLIC_JOBS['closed']
        self.cli('run-code', 'async page => { await page.goto(' + json.dumps(closed['url']) + '); return true; }')
        self.state('window.__fixture.jobReplay=' + json.dumps(closed['job']) + ';return true;')
        result, events = self.run_recording_events(public_job=closed)
        self.assertEqual({'status':'NEEDS_INPUT'}, result, events)
        self.assertEqual(['NEEDS_INPUT'], [e['type'] for e in events])
        self.assertEqual('', self.state('return window.__fixture.selected'))
        self.assertEqual([], self.state('return window.__fixture.sends'))
        self.assertEqual(0, self.state('return window.__fixture.uploadCount'))

    def test_candidate_page_marks_missing_full_jd_and_blocks_unknown_version_without_actions(self):
        self.state("window.__fixture.description='Card-only text';return true;")
        result = self.browser.action('candidate_page', sourceUrl='https://www.offertoday.com/hk/search?q=fixture', maxJobs=1, cursor='')
        self.assertFalse(result['jobs'][0]['descriptionComplete'])
        self.assertEqual('Card-only text', result['jobs'][0]['description'])
        self.state("document.querySelector('script[type=module]').remove();return true;")
        with self.assertRaisesRegex(BrowserBlocked, 'version'):
            self.browser.action('candidate_page', sourceUrl='https://www.offertoday.com/hk/search?q=fixture', maxJobs=1, cursor='')
        self.assertEqual('[]', self.state('return JSON.stringify(window.__fixture.sends)'))
        self.assertEqual(0, self.state('return window.__fixture.uploadCount'))

    def test_candidate_page_keeps_unsaved_draft_and_does_not_navigate(self):
        self.state("document.querySelector('.js-message-input-editor').textContent='Unsent user draft';return true;")
        with self.assertRaisesRegex(BrowserBlocked, 'draft'):
            self.browser.action('candidate_page', sourceUrl='https://www.offertoday.com/hk/recommend', maxJobs=1, cursor='')
        self.assertEqual('/hk/search', self.state('return location.pathname'))
        self.assertEqual('Unsent user draft', self.state('return document.querySelector(".js-message-input-editor").textContent'))

    def test_invented_auth_and_receipt_attributes_cannot_authorize_preflight(self):
        self.cli('run-code', '''async page => { await page.route('**/*',r=>r.fulfill({status:200,contentType:'text/html',body:'<main data-authenticated-user="true"><h1>Fixture engineer</h1><div data-company>Fixture co</div><div data-message-id="fake" data-delivery-state="acknowledged"></div></main>'})); await page.goto(''' + json.dumps(URL) + '''); return true; }''')
        with self.assertRaises(BrowserBlocked):
            self.preflight()

    def test_current_page_draft_blocks_navigation_without_discarding_it(self):
        self.state('''document.querySelector('.js-message-input-editor').textContent='user unsent draft';return true;''')
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

    def test_send_version_bytes_differ_from_original_download_blocks_before_claim(self):
        self.existing()
        self.cli('run-code', '''async page => page.evaluate(async () => {
          const { I } = await import('/static/js/index.BKzheqN_.js');
          const download = I.account.getDownloadAttach;
          I.account.getDownloadAttach = async args => {
            const blob = await download(args);
            // Same attachment name, ID and visible content; the version-addressed file has a platform wrapper.
            return args.version ? new Blob([blob, '\\n% platform wrapper']) : blob;
          };
          return true;
        })''')
        result, events = self.run_recording_events(policy=True)
        self.assertEqual({'status':'NEEDS_INPUT'}, result, events)
        self.assertEqual(['NEEDS_INPUT'], [event['type'] for event in events])
        self.assertEqual([], self.state('return window.__fixture.sends'))
        self.assertEqual(0, self.state('return window.__fixture.uploadRequestCount'))

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

    def test_exact_editor_but_changed_native_text_never_reaches_receiver(self):
        self.existing()
        receiver = self.start_delivery_receiver()
        self.state('''const S=window.__fixture;
          S.receiverRef=''' + json.dumps(receiver.ref) + ''';
          document.addEventListener('keydown', event => {
            if (event.key !== 'Enter') return;
            S.editorAtDispatch = event.target.textContent;
            S.nativeDraft = S.editorAtDispatch + ' altered';
          }, true);return true;''')
        result, events = self.run_recording_events(policy=True, receiver=receiver)
        self.assertEqual(MESSAGE, self.state('return window.__fixture.editorAtDispatch ?? null'), (result, events, receiver.trace))
        self.assertEqual(MESSAGE + ' altered', self.state('return window.__fixture.nativeDraft ?? null'))
        self.assertEqual({'status':'UNKNOWN'}, result, events)
        self.assertEqual([], receiver.requests, 'No actual browser POST, even one rejected by the receiver')
        self.assertEqual([], self.state('return window.__fixture.sends'))
        self.assertEqual(['PROGRESS','SUBMITTING','PROGRESS'], [e['type'] for e in events])

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

    def defer_fixture_text_until_after_composer_clear(self, before_send=''):
        self.state('''const S=window.__fixture;
          const editor=document.querySelector('.js-message-input-editor');
          editor.onkeydown=function(event){
            if(event.key!=='Enter'||event.shiftKey)return;
            event.preventDefault();
            let request=S.nativeRequests.get(event);
            editor.textContent='';
            const pending=new Promise(resolve=>setTimeout(()=>{
              ''' + before_send + '''
              S.dispatchText(event, request);
              const receipt=S.dispatchReceipts.get(event);
              resolve(receipt === pending ? null : receipt);
            },80));
            S.dispatchReceipts.set(event,pending);
          };return true;''')

    def test_native_text_rechecks_after_the_editor_clears(self):
        p = self.existing()
        self.defer_fixture_text_until_after_composer_clear()
        self.assertEqual('101', self.send_text(p)['contact']['messageId'])
        self.assertEqual(['text'], self.state('return window.__fixture.sends.map(s=>s.kind)'))

    def test_native_text_scope_and_packet_cannot_change_after_the_editor_clears(self):
        for before_send in ("S.owner='changed-owner';", "S.selected='changed-peer';",
                            "request={...request,clientMessageId:78};", "request={...request,text:'changed text'};"):
            with self.subTest(change=before_send):
                p = self.existing()
                self.defer_fixture_text_until_after_composer_clear(before_send)
                with self.assertRaises(BrowserBlocked):
                    self.send_text(p)
                self.assertEqual([], self.state('return window.__fixture.sends'))

    def test_text_sync_for_a_different_client_message_cannot_start_cv(self):
        self.existing()
        self.state('''const S = window.__fixture;
          const editor = document.querySelector('.js-message-input-editor');
          const send = editor.onkeydown;
          editor.onkeydown = function(event) {
            send.call(this, event);
            const receipt = S.dispatchReceipts.get(event);
            if (receipt) receipt[7] = 78;  // SyncMessage.clientMessageId in the recorded frame.
          };
          return true;''')
        result, events = self.run_recording_events(policy=True)
        self.assertEqual({'status':'UNKNOWN'}, result, events)
        self.assertEqual(['PROGRESS','SUBMITTING','PROGRESS'], [e['type'] for e in events])
        self.assertEqual(['text'], self.state('return window.__fixture.sends.map(s=>s.kind)'))

    def test_general_message_with_sync_fields_is_not_a_text_receipt(self):
        p = self.existing()
        self.state('''const S=window.__fixture;
          const editor=document.querySelector('.js-message-input-editor'), send=editor.onkeydown;
          editor.onkeydown=function(event){
            send.call(this,event);
            const receipt=S.dispatchReceipts.get(event);
            if(receipt) receipt[1] = 1;  // General_Message, not Sync_Message.
          };return true;''')
        with self.assertRaises(BrowserBlocked):
            self.send_text(p)
        self.assertEqual(['text'], self.state('return window.__fixture.sends.map(s=>s.kind)'))

    def test_published_muted_and_rounded_syncs_do_not_confirm_text(self):
        for key in ('mutedSync', 'roundedSync'):
            # Even if another real history row uses the apparent decimal ID, a lossy
            # number from the decoder cannot be converted into a trustworthy ID.
            history_id = str(TEXT_SYNC[key]['syncMessage']['messageId'])
            with self.subTest(packet=key):
                p = self.existing()
                self.state('''const S=window.__fixture;
                  const editor=document.querySelector('.js-message-input-editor'), send=editor.onkeydown;
                  editor.onkeydown=function(event){
                    send.call(this,event);
                    if (S.dispatchReceipts.has(event)) {
                      const payload = ''' + (f"Uint8Array.from({json.dumps(TEXT_SYNC['frames'][key])})"
                                              if key == 'mutedSync' else json.dumps(TEXT_SYNC[key])) + ''';
                      S.dispatchReceipts.set(event, payload);
                      S.history[0].messageId=''' + json.dumps(history_id) + ''';
                    }
                  };return true;''')
                with self.assertRaises(BrowserBlocked):
                    self.send_text(p)
                self.assertEqual(['text'], self.state('return window.__fixture.sends.map(s=>s.kind)'))

    def test_malformed_sync_frames_cannot_confirm_even_with_a_matching_history(self):
        frame = TEXT_SYNC['frames']['matchingSync']
        malformed = {
            'truncated': frame[:-1],
            'duplicate_server_id': [*frame[:5], 8, *frame[6:], 24, 101],
            'int64_overflow': [*frame[:5], 15, *frame[6:-1], *([128] * 9), 2],
            'invalid_extension_utf8': [*frame[:5], 9, *frame[6:], 34, 1, 255],
        }
        for case, bad in malformed.items():
            with self.subTest(case=case):
                p = self.existing()
                self.state('''const S=window.__fixture;
                  const editor=document.querySelector('.js-message-input-editor'), send=editor.onkeydown;
                  editor.onkeydown=function(event){
                    send.call(this,event);
                    if (S.dispatchReceipts.has(event))
                      S.dispatchReceipts.set(event, Uint8Array.from(''' + json.dumps(bad) + '''));
                  };return true;''')
                with self.assertRaisesRegex(BrowserBlocked, 'never retry'):
                    self.send_text(p)
                self.assertEqual(['text'], self.state('return window.__fixture.sends.map(s=>s.kind)'))

    def test_same_text_history_id_with_conflicting_server_sequence_is_not_a_receipt(self):
        p = self.existing()
        self.state('''const S=window.__fixture;
          const editor=document.querySelector('.js-message-input-editor'), send=editor.onkeydown;
          editor.onkeydown=function(event){
            send.call(this,event);
            if (S.history[0]) S.history[0].seq++;
          };return true;''')
        with self.assertRaises(BrowserBlocked):
            self.send_text(p)
        self.assertEqual(['text'], self.state('return window.__fixture.sends.map(s=>s.kind)'))

    def test_raw_text_sync_preserves_a_platform_id_above_js_safe_integer(self):
        p = self.existing()
        self.state('''const S=window.__fixture;
          const editor=document.querySelector('.js-message-input-editor'), send=editor.onkeydown;
          editor.onkeydown=function(event){
            send.call(this,event);
            if(S.dispatchReceipts.has(event)) {
              S.dispatchReceipts.set(event, Uint8Array.from(''' + json.dumps(TEXT_SYNC['frames']['roundedSync']) + '''));
              S.history[0].messageId=''' + json.dumps(TEXT_SYNC['roundedSyncPlatformId']) + ''';
            }
          };return true;''')
        self.assertEqual(TEXT_SYNC['roundedSyncPlatformId'], self.send_text(p)['contact']['messageId'])
        self.assertEqual(['text'], self.state('return window.__fixture.sends.map(s=>s.kind)'))

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
        self.assertEqual(DATA, bytes(self.state('return window.__fixture.receivedCvBytes')))
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
          button.onclick=event=>{send(event);window.__fixture.history.find(r=>r.messageId===102).addTime='101'};return true;''')
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

    def test_account_switch_after_upload_dialog_opens_does_not_upload(self):
        self.cli('run-code', 'async page => { await page.goto(' + json.dumps(URL) + '); return true; }')
        self.state('''const S=window.__fixture, opener=document.querySelector('button[aria-label="open-resume"]');
          const open=opener.onclick;
          opener.onclick=event=>{
            open(event);
            if (S.sends.some(action=>action.kind==='text')) S.owner='different-owner';
          };
          return true;''')
        result, events = self.run_recording_events(policy=True)
        self.assertEqual('different-owner', self.state('return window.__fixture.owner'))
        self.assertEqual({'status':'UNKNOWN'}, result, events)
        self.assertEqual(['PROGRESS','SUBMITTING','CONTACTED','SUBMITTING','PROGRESS'], [e['type'] for e in events])
        self.assertEqual(0, self.state('return window.__fixture.uploadCount'), 'File selection must not reach the native upload handler')
        self.assertTrue(self.state('return !window.__fixture.uploaded'))
        self.assertEqual(['text'], self.state('return window.__fixture.sends.map(s=>s.kind)'), 'No CV handler or retry')

    def test_staged_file_with_changed_bytes_never_reaches_upload_handler(self):
        fixture_browser = self.browser

        class ChangedFile:
            def action(self, operation, **data):
                if operation == 'send_resume':
                    path = Path(data['resumePath'])
                    path.write_bytes(DATA.replace(b'fixture only', b'changed only'))  # Same name and size.
                return fixture_browser.action(operation, **data)

        result, events = self.run_recording_events(ChangedFile(), policy=True)
        self.assertEqual({'status':'UNKNOWN'}, result, events)
        self.assertEqual(['PROGRESS','SUBMITTING','CONTACTED','SUBMITTING','PROGRESS'], [e['type'] for e in events])
        self.assertEqual(0, self.state('return window.__fixture.uploadCount'))
        self.assertTrue(self.state('return !window.__fixture.uploaded && document.querySelector("#upload input").files.length===0'))
        self.assertEqual(['text'], self.state('return window.__fixture.sends.map(s=>s.kind)'))

    def test_peer_switch_in_document_capture_never_reaches_native_cv_handler(self):
        self.existing()
        self.state('''document.addEventListener('click', event => {
          if (event.target.closest('#send-cv')) window.__fixture.selected = 'different-peer';
        }, true);return true;''')
        result, events = self.run_recording_events(policy=True)
        self.assertEqual('different-peer', self.state('return window.__fixture.selected'))
        self.assertEqual({'status':'UNKNOWN'}, result, events)
        self.assertEqual(['text'], self.state('return window.__fixture.sends.map(s=>s.kind)'))
        self.assertEqual(0, self.state('return window.__fixture.uploadCount'))
        self.assertEqual(['PROGRESS','SUBMITTING','CONTACTED','SUBMITTING','PROGRESS'], [e['type'] for e in events])

    def test_pending_upload_changes_before_save_cannot_be_saved_or_sent(self):
        self.cli('run-code', 'async page => { await page.goto(' + json.dumps(URL) + '); return true; }')
        self.state('''const S=window.__fixture, input=document.querySelector('#upload input');
          const confirm=document.querySelector('#upload .btns button');
          input.addEventListener('change', () => {
            confirm.disabled=true;
            setTimeout(()=>{
              if(S.uploaded) S.uploaded.originalOssKey='unrelated-upload';
              confirm.disabled=false;S.pendingChanged=true;
            },700);
          });return true;''')
        result, events = self.run_recording_events(policy=True)
        self.assertTrue(self.state('return window.__fixture.pendingChanged'))
        self.assertEqual({'status':'UNKNOWN'}, result, events)
        self.assertEqual(1, self.state('return window.__fixture.uploadCount'), 'Only the already verified upload')
        self.assertEqual(0, self.state('return window.__fixture.attachments.length'), 'No confirmation for another upload')
        self.assertEqual(['text'], self.state('return window.__fixture.sends.map(s=>s.kind)'))
        self.assertNotIn('CV_DELIVERED', [e['type'] for e in events])

    def test_account_switch_in_document_capture_never_reaches_native_upload(self):
        self.cli('run-code', 'async page => { await page.goto(' + json.dumps(URL) + '); return true; }')
        self.state('''document.addEventListener('change', event => {
          if (event.target.matches('#upload input[type="file"]')) window.__fixture.owner = 'different-owner';
        }, true);return true;''')
        result, events = self.run_recording_events(policy=True)
        self.assertEqual('different-owner', self.state('return window.__fixture.owner'))
        self.assertEqual({'status':'UNKNOWN'}, result, events)
        self.assertEqual(['PROGRESS','SUBMITTING','CONTACTED','SUBMITTING','PROGRESS'], [e['type'] for e in events])
        self.assertEqual([0, 0], self.state('return [window.__fixture.uploadCount,window.__fixture.uploadRequestCount]'))
        self.assertEqual(['text'], self.state('return window.__fixture.sends.map(s=>s.kind)'))

    def test_account_switch_during_native_file_read_stops_upload_request(self):
        self.cli('run-code', 'async page => { await page.goto(' + json.dumps(URL) + '); return true; }')
        self.state('''const read=File.prototype.arrayBuffer;
          File.prototype.arrayBuffer=async function(...args) {
            const bytes=await read.apply(this,args);
            if(window.__fixture.uploadCount) window.__fixture.owner='different-owner';
            return bytes;
          };return true;''')
        result, events = self.run_recording_events(policy=True)
        self.assertEqual({'status':'UNKNOWN'}, result, events)
        self.assertEqual('different-owner', self.state('return window.__fixture.owner'))
        self.assertEqual(1, self.state('return window.__fixture.uploadCount'), 'The native handler entered and yielded')
        self.assertEqual(0, self.state('return window.__fixture.uploadRequestCount'), 'No request after the native handler yields')
        self.assertTrue(self.state('return !window.__fixture.uploaded'))
        self.assertEqual(['text'], self.state('return window.__fixture.sends.map(s=>s.kind)'))

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

    def test_text_changed_during_final_history_check_never_reaches_sender(self):
        self.existing()
        self.cli('run-code', '''async page => page.evaluate(async approved => {
          const native = await import('/static/js/index.BKzheqN_.js'), S = window.__fixture;
          const read = native.I.chat.getHistoryMsgs;
          native.I.chat.getHistoryMsgs = async (...args) => {
            const rows = await read(...args);
            const editor = document.querySelector('.js-message-input-editor');
            if (editor.textContent === approved && ++S.draftReads === 2) {
              editor.append(document.createTextNode(' '));
              S.draftChanged = editor.textContent;
            }
            return rows;
          };
          S.draftReads = 0;
          return true;
        }, ''' + json.dumps(MESSAGE) + ')')
        result, events = self.run_recording_events(policy=True)
        self.assertEqual(MESSAGE + ' ', self.state('return window.__fixture.draftChanged'))
        self.assertEqual({'status':'UNKNOWN'}, result, events)
        self.assertEqual([], self.state('return window.__fixture.sends'), 'Neither the text nor the CV handler may run')
        self.assertEqual(['PROGRESS','SUBMITTING','PROGRESS'], [e['type'] for e in events])
        self.assertEqual(0, self.state('return window.__fixture.uploadCount'))

    def test_same_text_record_added_during_final_byte_check_does_not_trigger_cv(self):
        self.existing()
        self.cli('run-code', '''async page => page.evaluate(async message => {
          const native=await import('/static/js/index.BKzheqN_.js'), S=window.__fixture;
          S.textPersist=false;
          const download=native.I.account.getDownloadAttach;
          native.I.account.getDownloadAttach=async (...args) => {
            const blob=await download(...args);
            const editor=document.querySelector('.js-message-input-editor');
            if (editor.textContent===message && !S.history.some(m=>m.messageId===98)) {
              S.history.unshift({messageId:98,addTime:101,seq:101,fromSn:S.owner,toSn:S.peer,
                mediaType:1,status:0,body:{text:{content:message}}});
            }
            return blob;
          };
          return true;
        }, ''' + json.dumps(MESSAGE) + ')')
        result, events = self.run_recording_events(policy=True)
        self.assertTrue(self.state('return window.__fixture.history.some(r=>r.messageId===98)'))
        self.assertEqual(result, {'status':'UNKNOWN'}, events)
        self.assertEqual([e['type'] for e in events], ['PROGRESS','SUBMITTING','PROGRESS'])
        self.assertEqual([], self.state('return window.__fixture.sends'), 'No Enter, CV click or retry')
        self.assertEqual(0, self.state('return window.__fixture.uploadCount'))

    def test_native_selection_changes_without_repaint_does_not_send_cv(self):
        self.existing()
        self.cli('run-code', '''async page => page.evaluate(async () => {
          const native = await import('/static/js/index.BKzheqN_.js'), S = window.__fixture;
          const history = native.I.chat.getHistoryMsgs;
          let cvReads = 0;
          native.I.chat.getHistoryMsgs = async (...args) => {
            const result = await history(...args);
            if (S.selectedResume === 'approved-id' && S.sends.some(s => s.kind === 'text') && ++cvReads === 2) {
              S.selectedResume = 'wrong-default'; // Native value changes before UI repaint.
              S.staleCheckedFilename = document.querySelector('[data-testid="radio-checked-iconIcon"]').parentElement.textContent;
            }
            return result;
          };
          return true;
        })''')
        result, events = self.run_recording_events(policy=True)
        self.assertEqual('approved.pdf', self.state('return window.__fixture.staleCheckedFilename'))
        self.assertEqual('wrong-default', self.state('return window.__fixture.selectedResume'))
        self.assertEqual({'status':'UNKNOWN'}, result, events)
        self.assertEqual([e['type'] for e in events], ['PROGRESS','SUBMITTING','CONTACTED','SUBMITTING','PROGRESS'])
        self.assertEqual(['text'], self.state('return window.__fixture.sends.map(s=>s.kind)'), 'No CV handler or retry')
        self.assertEqual(0, self.state('return window.__fixture.uploadCount'))

    def test_same_id_filename_and_size_bytes_replaced_during_history_never_send_cv(self):
        self.existing()
        self.cli('run-code', '''async page => page.evaluate(async () => {
          const native = await import('/static/js/index.BKzheqN_.js'), S = window.__fixture;
          const history = native.I.chat.getHistoryMsgs;
          let cvReads = 0;
          native.I.chat.getHistoryMsgs = async (...args) => {
            const result = await history(...args);
            if (S.selectedResume === 'approved-id' && S.sends.some(s => s.kind === 'text') && ++cvReads === 2) {
              const item = S.attachments.find(a => a.encryptAttachId === 'approved-id');
              item.bytes[8] ^= 1; // Keep the attachment ID, name, key and byte count.
              S.replacedResume = [item.encryptAttachId, item.fileName, item.originalOssKey, item.bytes.length];
            }
            return result;
          };
          return true;
        })''')
        result, events = self.run_recording_events(policy=True)
        self.assertEqual(['approved-id', 'approved.pdf', 'approved-key', len(DATA)], self.state('return window.__fixture.replacedResume'))
        self.assertEqual({'status':'UNKNOWN'}, result, (events, self.state('return window.__fixture.receivedCvBytes ?? null')))
        self.assertEqual(['PROGRESS','SUBMITTING','CONTACTED','SUBMITTING','PROGRESS'], [e['type'] for e in events])
        self.assertEqual(['text'], self.state('return window.__fixture.sends.map(s=>s.kind)'), 'No native CV send or retry')
        self.assertEqual(0, self.state('return window.__fixture.uploadRequestCount'))

    def test_native_cv_request_without_version_is_denied_even_if_selection_is_versioned(self):
        self.existing()
        self.state('''const S=window.__fixture;
          S.prepareResumeRequest = () => {
            const {resumeId, originalOssKey} = S.planResume(S.selectedResume);
            return S.unversionedRequest = {resumeId, originalOssKey};
          };return true;''')
        result, events = self.run_recording_events(policy=True)
        self.assertEqual({'resumeId':'approved-id', 'originalOssKey':'approved-key'}, self.state('return window.__fixture.unversionedRequest'))
        self.assertEqual({'status':'UNKNOWN'}, result, events)
        self.assertEqual(['PROGRESS','SUBMITTING','CONTACTED','SUBMITTING','PROGRESS'], [e['type'] for e in events])
        self.assertEqual(['text'], self.state('return window.__fixture.sends.map(s=>s.kind)'))

    def test_same_message_id_with_wrong_immutable_version_cannot_confirm_cv(self):
        for source in ('acknowledgement', 'history'):
            with self.subTest(source=source):
                self.existing()
                self.state('''const S=window.__fixture, button=document.querySelector('#send-cv'), send=button.onclick;
                  button.onclick = event => {
                    send(event);
                    const history = S.history.find(m => m.messageId === 102), ack = S.dispatchReceipts.get(event);
                    const wrong = {...ack.resume, version:'different-version'};
                    if (''' + json.dumps(source) + ''' === 'acknowledgement') S.dispatchReceipts.set(event, {...ack, resume:wrong});
                    else history.body.compressiveCard.content = JSON.stringify(wrong);
                  };return true;''')
                result, events = self.run_recording_events(policy=True)
                self.assertEqual({'status':'UNKNOWN'}, result, events)
                self.assertEqual(['PROGRESS','SUBMITTING','CONTACTED','SUBMITTING','PROGRESS'], [e['type'] for e in events])
                self.assertEqual(['text','resume'], self.state('return window.__fixture.sends.map(s=>s.kind)'), 'No retry')
                self.assertEqual(DATA, bytes(self.state('return window.__fixture.receivedCvBytes')))

    def test_same_attachment_record_added_during_cv_byte_check_is_not_this_send(self):
        self.existing()
        self.cli('run-code', '''async page => page.evaluate(async () => {
          const native=await import('/static/js/index.BKzheqN_.js'), S=window.__fixture;
          S.cvPersist=false;
          const download=native.I.account.getDownloadAttach;
          native.I.account.getDownloadAttach=async (...args) => {
            const blob=await download(...args);
            if (S.sends.some(s=>s.kind==='text') && S.selectedResume==='approved-id' && !S.history.some(m=>m.messageId===99)) {
              S.history.unshift({messageId:99,addTime:102,seq:102,fromSn:S.owner,toSn:S.peer,
                mediaType:6,status:0,body:{compressiveCard:{type:10,content:JSON.stringify({resumeId:'approved-id'})}}});
            }
            return blob;
          };
          return true;
        })''')
        result, events = self.run_recording_events(policy=True)
        self.assertTrue(self.state('return window.__fixture.history.some(r=>r.messageId===99)'))
        self.assertEqual(result, {'status':'UNKNOWN'}, events)
        self.assertEqual([e['type'] for e in events], ['PROGRESS','SUBMITTING','CONTACTED','SUBMITTING','PROGRESS'])
        self.assertEqual(['text','resume'], self.state('return window.__fixture.sends.map(s=>s.kind)'), 'No retries')

    def delay_resume_button(self, *, switch_peer):
        self.existing()
        self.cli('run-code', '''async page => page.evaluate(async switchPeer => {
          const native=await import('/static/js/index.BKzheqN_.js'), S=window.__fixture;
          const download=native.I.account.getDownloadAttach, history=native.I.chat.getHistoryMsgs;
          const send=document.querySelector('#send-cv');
          S.cvPersist=false;
          native.I.account.getDownloadAttach=async (...args) => {
            const blob=await download(...args);
            if (S.sends.some(s=>s.kind==='text') && S.selectedResume==='approved-id') send.disabled=true;
            return blob;
          };
          native.I.chat.getHistoryMsgs=async (...args) => {
            const result=await history(...args);
            if (send.disabled && !S.switchScheduled) {
              S.switchScheduled=true;
              setTimeout(()=>{
                if (switchPeer) S.selected='different-peer';
                else S.history.unshift({messageId:97,addTime:102,seq:102,fromSn:S.owner,toSn:S.peer,
                  mediaType:6,status:0,body:{compressiveCard:{type:10,content:JSON.stringify({resumeId:'approved-id'})}}});
                send.disabled=false;S.delayFinished=true;
              }, 700);
            }
            return result;
          };
          return true;
        }, ''' + json.dumps(switch_peer) + ')')

    def wait_for_delayed_resume_button(self):
        self.cli('run-code', "async page => { await page.waitForFunction(() => window.__fixture.delayFinished, null, {timeout:2000});return true; }")

    def test_same_cv_record_appearing_during_button_wait_is_not_a_receipt(self):
        self.delay_resume_button(switch_peer=False)
        result, events = self.run_recording_events(policy=True)
        self.wait_for_delayed_resume_button()
        self.assertTrue(self.state('return window.__fixture.history.some(r=>r.messageId===97)'))
        self.assertEqual({'status':'UNKNOWN'}, result, events)
        self.assertEqual([e['type'] for e in events], ['PROGRESS','SUBMITTING','CONTACTED','SUBMITTING','PROGRESS'])
        self.assertEqual(['text'], self.state('return window.__fixture.sends.map(s=>s.kind)'))

    def test_matching_record_from_another_dispatch_cannot_confirm_cv(self):
        self.existing()
        self.state('''const S=window.__fixture; S.cvPersist=false;
          document.querySelector('#send-cv').addEventListener('pointerdown', () => {
            S.history.unshift({messageId:95,addTime:102,seq:102,fromSn:S.owner,toSn:S.peer,
              mediaType:6,status:0,body:{compressiveCard:{type:10,content:JSON.stringify({resumeId:'approved-id'})}}});
          }, {once:true});return true;''')
        result, events = self.run_recording_events(policy=True)
        self.assertTrue(self.state('return window.__fixture.history.some(r=>r.messageId===95)'))
        self.assertEqual({'status':'UNKNOWN'}, result, events)
        self.assertEqual([e['type'] for e in events], ['PROGRESS','SUBMITTING','CONTACTED','SUBMITTING','PROGRESS'])
        self.assertEqual(['text','resume'], self.state('return window.__fixture.sends.map(s=>s.kind)'))

        self.state('''const S=window.__fixture;
          Object.assign(S,{history:[],local:[],sends:[],cvPersist:true});
          document.querySelector('#picker').hidden=true;
          document.querySelector('#send-cv').addEventListener('pointerdown', () => {
            S.history.unshift({messageId:95,addTime:102,seq:102,fromSn:S.owner,toSn:S.peer,
              mediaType:6,status:0,body:{compressiveCard:{type:10,content:JSON.stringify({resumeId:'approved-id'})}}});
          }, {once:true});return true;''')
        result, events = self.run_recording_events(policy=True)
        self.assertEqual({'status':'SUBMITTED'}, result, events)
        self.assertTrue(self.state('return window.__fixture.history.some(r=>r.messageId===95)'))
        self.assertEqual('102', json.loads(events[-1]['evidence'])['messageId'], 'Use the acknowledged dispatch, not a same-file lookalike')
        self.assertEqual(['text','resume'], self.state('return window.__fixture.sends.map(s=>s.kind)'))

    def test_cv_record_during_non_sending_trial_is_included_in_final_baseline(self):
        self.existing()
        self.state('''const S=window.__fixture, button=document.querySelector('#send-cv');
          S.cvPersist=false;
          document.addEventListener('click', event => {
            if (!event.target.closest('.resume-row') || S.trialDelayed) return;
            S.trialDelayed=true; button.disabled=true;
            setTimeout(()=>{
              S.history.unshift({messageId:96,addTime:102,seq:102,fromSn:S.owner,toSn:S.peer,
                mediaType:6,status:0,body:{compressiveCard:{type:10,content:JSON.stringify({resumeId:'approved-id'})}}});
              button.disabled=false;
            }, 700);
          });return true;''')
        result, events = self.run_recording_events(policy=True)
        self.assertTrue(self.state('return window.__fixture.history.some(r=>r.messageId===96)'))
        self.assertEqual({'status':'UNKNOWN'}, result, events)
        self.assertEqual([e['type'] for e in events], ['PROGRESS','SUBMITTING','CONTACTED','SUBMITTING','PROGRESS'])
        self.assertEqual(['text','resume'], self.state('return window.__fixture.sends.map(s=>s.kind)'), 'One CV click, no retries')

    def test_peer_switch_while_resume_button_waits_does_not_click(self):
        self.delay_resume_button(switch_peer=True)
        result, events = self.run_recording_events(policy=True)
        self.wait_for_delayed_resume_button()
        self.assertEqual('different-peer', self.state('return window.__fixture.selected'))
        self.assertEqual(result, {'status':'UNKNOWN'}, events)
        self.assertEqual([e['type'] for e in events], ['PROGRESS','SUBMITTING','CONTACTED','SUBMITTING','PROGRESS'])
        self.assertEqual(['text'], self.state('return window.__fixture.sends.map(s=>s.kind)'), 'No CV click, even though the button became enabled')
        self.assertEqual(0, self.state('return window.__fixture.uploadCount'))
        self.cli('run-code', '''async page => {
          await page.evaluate(() => {
            const button=document.createElement('button');button.id='local-fixture-control';button.textContent='Harmless fixture action';
            button.onclick=()=>window.__fixture.guardReleased=true;document.body.appendChild(button);
          });
          await page.locator('#local-fixture-control').click({timeout:2000});return true;
        }''')
        self.assertTrue(self.state('return window.__fixture.guardReleased'), 'The temporary event guard must be removed')

    def run_recording_events(self, adapter=None, *, policy=False, public_job=None, receiver=None):
        target = {'url': URL, 'platformJobId': JOB, 'title':'Fixture engineer', 'company':'Fixture co'}
        if public_job:
            j = public_job['job']
            target = {'url':public_job['url'], 'platformJobId':j['jobId'], 'title':j['jobName'].strip(), 'company':j['brandName']}
        events = []
        class Handler(BaseHTTPRequestHandler):
            def do_POST(self):
                event = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
                events.append(event)
                if receiver:
                    receiver.trace.append(('callback', event['type'], event.get('action')))
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
                        **target, 'attemptId':'fixture-attempt','approvalSource':'SCOPE_POLICY' if policy else 'MANUAL',
                        'snapshot':{'contactVerified':True,'facts':[],
                            'initialMessage':MESSAGE,'initialMessageSha256':MESSAGE_SHA,
                            'resume':{'filename':'approved.pdf','sha256':SHA,'approved':True}}}}))
                return run(taskfile, adapter or self.browser), events
            finally:
                server.shutdown();thread.join()

    def test_run_keeps_contacted_unknown_when_resume_server_receipt_missing(self):
        self.existing()
        self.state('window.__fixture.cvPersist=false;return true;')
        result, events = self.run_recording_events()
        self.assertEqual(result, {'status':'UNKNOWN'})
        self.assertEqual([e['type'] for e in events], ['PROGRESS','SUBMITTING','CONTACTED','PROGRESS'])
        self.assertIn('"kind":"text"', self.state('return JSON.stringify(window.__fixture.sends)'))
        self.assertIn('"kind":"resume"', self.state('return JSON.stringify(window.__fixture.sends)'))

    def assert_loopback_delivery(self, receiver, result, events):
        self.assertEqual({'status':'SUBMITTED'}, result, events)
        self.assertEqual(['PROGRESS','SUBMITTING','CONTACTED','SUBMITTING','CV_DELIVERED'], [e['type'] for e in events])
        contact, cv = (json.loads(e['evidence']) for e in events if e['type'] in ('CONTACTED','CV_DELIVERED'))
        self.assertEqual(('4201', '4202'), (contact['messageId'], cv['messageId']))
        self.assertEqual((MESSAGE_SHA, SHA), (contact['initialMessageSha256'], cv['resumeSha256']))
        self.assertEqual((receiver.scope['jobId'], 'peer-1'), (contact['jobId'], contact['conversationId']))
        self.assertEqual((receiver.scope['jobId'], 'peer-1'), (cv['jobId'], cv['conversationId']))
        self.assertEqual([('text', {**receiver.scope, 'text': MESSAGE, 'clientMessageId': CLIENT_MESSAGE_ID}),
                          ('resume', {**receiver.scope, 'resume': receiver.ref})], receiver.requests)
        self.assertEqual([DATA], receiver.delivered_bytes)
        version_read = ('asset', receiver.ref['version'])
        ordered = [version_read, ('callback','SUBMITTING','CONTACT'), ('request','text'), ('ack','text',4201),
                   ('history',(4201,)), ('callback','CONTACTED',None), ('callback','SUBMITTING','SEND_CV'),
                   version_read, ('history',(4201,)), ('request','resume'), ('ack','resume',4202),
                   ('history',(4202,4201)), ('callback','CV_DELIVERED',None)]
        position = 0
        for item in ordered:
            self.assertIn(item, receiver.trace[position:], receiver.trace)
            position = receiver.trace.index(item, position) + 1
        self.assertEqual([], self.state('return window.__fixture.history'), 'Do not use page-owned simulated history')
        self.assertEqual(0, self.state('return window.__fixture.uploadCount'))

    def test_legacy_native_adapter_uses_separate_http_deliveries_and_histories(self):
        self.existing()
        receiver = self.start_delivery_receiver()
        self.state('window.__fixture.receiverRef=' + json.dumps(receiver.ref) + ';return true;')
        self.defer_fixture_text_until_after_composer_clear()
        result, events = self.run_recording_events(policy=True, receiver=receiver)
        self.assert_loopback_delivery(receiver, result, events)

    def test_actual_native_cv_request_to_wrong_peer_never_reaches_receiver(self):
        self.existing()
        receiver = self.start_delivery_receiver()
        self.state('''const S=window.__fixture, prepare=S.prepareNativeRequest;
          S.receiverRef=''' + json.dumps(receiver.ref) + ''';
          S.prepareNativeRequest=(kind,target)=>{
            const request=prepare(kind,target);
            if(kind!=='resume')return request;
            return S.wrongCvRequest={...request,peer:'wrong-peer'};
          };return true;''')
        result, events = self.run_recording_events(policy=True, receiver=receiver)
        self.assertEqual({'status':'UNKNOWN'}, result, events)
        self.assertEqual(['PROGRESS','SUBMITTING','CONTACTED','SUBMITTING','PROGRESS'], [e['type'] for e in events])
        self.assertEqual('peer-1', self.state('return window.__fixture.selected'))
        self.assertEqual('wrong-peer', self.state('return window.__fixture.wrongCvRequest.peer'))
        self.assertEqual([('text', {**receiver.scope, 'text': MESSAGE, 'clientMessageId': CLIENT_MESSAGE_ID})], receiver.requests)
        self.assertEqual([], receiver.delivered_bytes)

    def test_disabled_sender_does_not_navigate_shared_search_tab(self):
        result, events = self.run_recording_events(self.unqualified)
        self.assertEqual({'status':'NEEDS_INPUT'}, result)
        self.assertEqual(['NEEDS_INPUT'], [e['type'] for e in events])
        self.assertIn('input-to-native attachment ownership or per-action receipt', events[0]['message'])
        self.assertEqual('/hk/search', self.state('return location.pathname'))
        self.assertEqual([], self.state('return window.__fixture.sends'))
        self.assertEqual(0, self.state('return window.__fixture.uploadRequestCount'))

    def test_unqualified_builds_stop_before_claim_or_side_effect(self):
        self.cli('run-code', 'async page => { await page.goto(' + json.dumps(URL) + '); return true; }')
        for entry in ('index.BKzheqN_.js', 'index.D4zvvLnm.js'):
            with self.subTest(entry=entry):
                self.state('document.querySelector("script[type=module]").src=' + json.dumps('/static/js/'+entry) + ';return true;')
                result, events = self.run_recording_events(self.unqualified)
                self.assertEqual(result, {'status':'NEEDS_INPUT'})
                self.assertEqual(['NEEDS_INPUT'], [e['type'] for e in events])
                self.assertIn('input-to-native attachment ownership or per-action receipt', events[0]['message'])
                self.assertEqual('', self.state('return window.__fixture.selected'))
                self.assertEqual(0, self.state('return window.__fixture.uploadCount'))
                self.assertEqual([], self.state('return window.__fixture.sends'))

    def test_new_native_bindings_complete_an_intercepted_two_stage_qualification(self):
        # Only the job details derive from a public capture. Separate loopback sends, ACKs and
        # history validate this adapter seam with synthetic data, not with a live account.
        closed, opened = PUBLIC_JOBS['closed'], PUBLIC_JOBS['open']
        receiver = self.start_delivery_receiver(opened['job']['jobId'])

        def stage(public_job):
            self.cli('run-code', 'async page => { await page.goto(' + json.dumps(public_job['url']) + '); return true; }')
            self.state('window.__fixture.jobReplay=' + json.dumps(public_job['job']) + ''';
              window.__fixture.receiverRef=''' + json.dumps(receiver.ref) + ''';
              window.__fixture.attachments=[
                {encryptAttachId:'wrong-default',fileName:'old.pdf',originalOssKey:'old-key',bytes:[42],defaulted:true},
                {encryptAttachId:'approved-id',fileName:'approved.pdf',originalOssKey:'approved-key',produceStatus:false,
                 auditInfo:{auditStatus:1},bytes:''' + json.dumps(list(DATA)) + '''}];return true;''')
        stage(closed)
        result, events = self.run_recording_events(policy=True, public_job=closed, receiver=receiver)
        self.assertEqual({'status':'NEEDS_INPUT'}, result)
        self.assertEqual(['NEEDS_INPUT'], [e['type'] for e in events])
        self.assertEqual('', self.state('return window.__fixture.selected'))
        stage(opened)
        self.state("window.__fixture.jobReplay.jobStatus='2';return true;")
        result, events = self.run_recording_events(policy=True, public_job=opened, receiver=receiver)
        self.assertEqual({'status':'NEEDS_INPUT'}, result)
        self.assertEqual(['NEEDS_INPUT'], [e['type'] for e in events])
        self.assertEqual('', self.state('return window.__fixture.selected'))
        self.assertEqual([], receiver.requests)

        self.state('window.__fixture.jobReplay.jobStatus=2;return true;')
        self.defer_fixture_text_until_after_composer_clear()
        result, events = self.run_recording_events(policy=True, public_job=opened, receiver=receiver)
        self.assert_loopback_delivery(receiver, result, events)

        # For a new fixture attempt, acknowledge and persist the CV but hide it from every history GET.
        receiver.reset(hidden_media_type=6)
        stage(opened)
        result, events = self.run_recording_events(policy=True, public_job=opened, receiver=receiver)
        self.assertEqual({'status':'UNKNOWN'}, result, events)
        self.assertEqual(['PROGRESS','SUBMITTING','CONTACTED','SUBMITTING','PROGRESS'], [e['type'] for e in events])
        self.assertEqual(['text','resume'], [kind for kind, _ in receiver.requests], 'Missing history must not retry either send')
        self.assertEqual([DATA], receiver.delivered_bytes)
        after_ack = receiver.trace[receiver.trace.index(('ack','resume',4202))+1:]
        self.assertIn(('history',(4201,)), after_ack)
        self.assertNotIn(('history',(4202,4201)), after_ack)

    def test_expected_build_marker_alone_cannot_approve_stale_native_bindings(self):
        self.cli('run-code', '''async page => {
          await page.route('**/static/js/index.BKzheqN_.js', route=>route.fulfill({
            status: 200, contentType: 'text/javascript',
            body: 'export const I=Object.freeze({}),cs={},cI={},aD={},dc={};'
          }));
          await page.goto(''' + json.dumps(URL) + ''');return true;
        }''')
        result, events = self.run_recording_events()
        self.assertEqual(result, {'status':'NEEDS_INPUT'})
        self.assertEqual(['NEEDS_INPUT'], [e['type'] for e in events])
        self.assertEqual('Native job, account, editor or approved resume is not safely recognized', events[0]['message'])
        self.assertEqual('', self.state('return window.__fixture.selected'))
        self.assertEqual(0, self.state('return window.__fixture.uploadCount'))
        self.assertEqual('[]', self.state('return JSON.stringify(window.__fixture.sends)'))

    def test_preflight_opens_native_peer_and_blocks_wrong_upload_capacity(self):
        self.cli('run-code', 'async page => { await page.goto(' + json.dumps(URL) + '); return true; }')
        self.state('''const S=window.__fixture; S.attachments=[1,2,3].map(n=>({encryptAttachId:'old-'+n,fileName:'other-'+n+'.pdf',originalOssKey:'old-'+n,bytes:[42],auditInfo:{auditStatus:1},produceStatus:false}));return true;''')
        with self.assertRaises(BrowserBlocked):
            self.preflight()
        self.assertIn('[]', self.state('return JSON.stringify(window.__fixture.sends)'))


if __name__ == '__main__':
    unittest.main()
