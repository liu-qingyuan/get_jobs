// #14 packaged UI + real HTTP, synthetic data/fake children only. Never touches a running workbench.
import assert from 'node:assert/strict'
import { mkdtemp, mkdir, writeFile, readFile, open, unlink } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join, resolve } from 'node:path'
import { pathToFileURL } from 'node:url'
import { createServer } from 'node:net'
import { spawn, execFileSync } from 'node:child_process'
import { once } from 'node:events'
import { setTimeout as delay } from 'node:timers/promises'

assert(process.env.PLAYWRIGHT_MODULE, 'Set PLAYWRIGHT_MODULE to an installed playwright module')
const { default: { chromium } } = await import(pathToFileURL(resolve(process.env.PLAYWRIGHT_MODULE)).href)
const jar = resolve(process.env.WORKBENCH_SMOKE_JAR || 'build/libs/get_jobs-2.0.1.jar')
const root = await mkdtemp(join(tmpdir(), 'workbench-workflow-smoke-'))
const probe = createServer().listen(0, '127.0.0.1'); await once(probe, 'listening')
const port = probe.address().port; await new Promise(resolve => probe.close(resolve))
const base = `http://127.0.0.1:${port}`
await mkdir(join(root, 'src/main/resources/dist'), { recursive: true })
const fake = join(root, 'fake.py')
await writeFile(fake, `import json,sys,pathlib,urllib.request,os,time,hashlib
b=json.load(open(sys.argv[-1]));root=pathlib.Path(__file__).parent
mode=(root/'mode').read_text() if (root/'mode').exists() else 'login'
with (root/'calls').open('a') as log: log.write(b.get('kind','EXECUTE')+'\\n')
if b.get('kind')=='CANDIDATE_READ':
    offer=b['source']['channel']=='OFFERTODAY'
    names=['crash-c','crash-d'] if mode=='crash' else ['c'] if offer else ['a','b']
    jd='Responsibilities: build and evaluate Python search applications using synthetic data. Requirements: demonstrable Python projects, clear documentation and repeatable evaluation tests.'
    json.dump({'jobs':[{'url':('https://www.offertoday.com/hk/job/synthetic-job-' if offer else 'https://careers.example.test/jobs/')+n,'company':'Synthetic '+n,'title':'Python engineer','description':jd,'descriptionComplete':True} for n in names]},open(b['resultPath'],'w'));sys.exit(0)
if b.get('kind')=='CANDIDATE_MATCH':
    json.dump({'results':[{'candidateId':j['candidateId'],'jdSha256':j['jdSha256'],'findings':[{'kind':'SUPPORTED','requirementQuote':'Python','reason':'Confirmed synthetic Python project','evidence':[{'type':'FACT','id':j['facts'][0]['id'],'quote':j['facts'][0]['value']}]}]} for j in b['candidates']]},open(b['resultPath'],'w'));sys.exit(0)
if b.get('kind')=='MATERIAL_PREPARE':
    json.dump({'results':[{'candidateId':j['candidateId'],'resumeId':j['resumes'][0]['id'],'transformation':'REORDER','factIds':[j['facts'][0]['id']]} for j in b['candidates']]},open(b['resultPath'],'w'));sys.exit(0)
t=b['task'];s=t['snapshot']
assert t['approvalSource']=='SCOPE_POLICY' and s['source']=='SCOPE_POLICY'
assert hashlib.sha256(pathlib.Path(b['resumePath']).read_bytes()).hexdigest()==s['resume']['sha256']
assert b['limits']['seconds']<=30 and b['limits']['modelTokens']<100001
opener=urllib.request.build_opener(urllib.request.ProxyHandler({}))
def report(kind,action='',proof=None):
    request=urllib.request.Request(b['callbackUrl'],json.dumps({'type':kind,'action':action,'message':'Synthetic '+kind,'evidence':json.dumps(proof) if proof else ''}).encode(),headers={'Content-Type':'application/json','Authorization':'Bearer '+b['token']})
    with opener.open(request,timeout=5) as r: assert r.status==200
if mode=='login' and t['company']=='Synthetic b': report('NEEDS_INPUT');sys.exit(0)
offer=t['channel']=='OFFERTODAY'
report('SUBMITTING','CONTACT' if offer else 'APPLY')
if offer:
    common={'jobId':t['platformJobId'],'attemptId':t['attemptId'],'conversationId':'fixture-'+t['id'],'proof':'Synthetic fresh platform history, not a real site'}
    report('CONTACTED',proof=dict(common,messageId='text',initialMessageSha256=s['initialMessageSha256']))
    if mode=='crash' and t['company']=='Synthetic crash-c':
        (root/'held').write_text(json.dumps({'pid':os.getpid(),'callbackUrl':b['callbackUrl'],'token':b['token']}))
        while True: time.sleep(0.1)
    report('SUBMITTING','SEND_CV')
    report('CV_DELIVERED',proof=dict(common,messageId='cv',resumeSha256=s['resume']['sha256']))
else: report('SUBMITTED',proof='Synthetic application acknowledgement')
`)
execFileSync('python3', ['-c', `import sqlite3,sys
with sqlite3.connect(sys.argv[1]) as c:
    c.executescript('CREATE TABLE default_page_config_seed(id INTEGER PRIMARY KEY); INSERT INTO default_page_config_seed VALUES(1); CREATE TABLE config(id INTEGER PRIMARY KEY, config_key TEXT, config_value TEXT, config_type TEXT, category TEXT, description TEXT, created_at TEXT, updated_at TEXT);')
`, join(root, 'empty-legacy.db')])
const log = await open(join(root, 'server.log'), 'a')
let server, browser
function launch() {
  server = spawn(process.env.JAVA_HOME ? join(process.env.JAVA_HOME, 'bin/java') : 'java', ['-jar', jar, `--server.port=${port}`, '--server.address=127.0.0.1', '--browser.auto-start=false',
    `--spring.datasource.url=jdbc:sqlite:${join(root, 'empty-legacy.db')}`, `--workbench.data-dir=${join(root, 'state')}`, `--workbench.jobsdb-dir=${join(root, 'empty-jobsdb')}`,
    `--workbench.offertoday-command=${JSON.stringify(['python3', fake])}`, `--workbench.agent-command=${JSON.stringify(['python3', fake, '{taskFile}'])}`, `--logging.file.name=${join(root, 'application.log')}`,
  ], { cwd: root, stdio: ['ignore', log.fd, log.fd] })
}
async function until(check, label) {
  for (let i = 0; i < 250; i++) { if (await check()) return; await delay(100) }
  throw new Error(`${label}; inspect ${root}`)
}
async function ready() { await until(async () => { assert.equal(server.exitCode, null, `Server exited: ${root}`); try { return (await fetch(`${base}/api/workbench/state`)).ok } catch { return false } }, 'Server readiness') }
async function get(path = '/state') { return (await fetch(`${base}/api/workbench${path}`)).json() }
async function post(path, body) {
  const response = await fetch(`${base}/api/workbench${path}`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) })
  assert(response.ok, `${path}: ${response.status} ${await response.clone().text()}`); return response.json()
}
try {
  launch(); await ready()
  const fact = await post('/facts', { key: 'project', label: 'Synthetic project', value: 'Built a synthetic Python search project.', scope: 'HK', confirmed: true })
  const contact = await post('/facts', { key: 'contact_email', label: 'Synthetic email', value: 'applicant@example.test', scope: 'HK', confirmed: true })
  const form = new FormData(); for (const [key, value] of Object.entries({ label: 'Synthetic HK English', region: 'HK', language: 'en', content: fact.value })) form.set(key, value)
  const resume = await (await fetch(`${base}/api/workbench/resumes`, { method: 'POST', body: form })).json()
  await post(`/resumes/${resume.id}/approve`, {})
  await post('/scope', { channels: ['OFFICIAL', 'OFFERTODAY'], hosts: ['careers.example.test', 'www.offertoday.com'], regions: ['HK'], languages: ['en'], directions: ['AI_APPLICATION'], transformations: ['REORDER'], factIds: [fact.id, contact.id], resumeIds: [resume.id], contactFactIds: [contact.id], contactVerified: true, actions: ['DISCOVER', 'MATCH', 'PREPARE', 'APPLY', 'CONTACT', 'SEND_CV'], budgets: { candidates: 100, seconds: 2000, modelTokens: 100000, outbound: 20 }, expiresAt: new Date(Date.now() + 3600000).toISOString() })
  browser = await chromium.launch({ channel: 'chrome', headless: true })
  const context = await browser.newContext({ viewport: { width: 1365, height: 1000 }, serviceWorkers: 'block' })
  const failures = [], starts = []
  await context.route('**/*', async route => {
    const url = new URL(route.request().url())
    if (url.pathname === '/api/workbench/cycles' && route.request().method() === 'POST') starts.push(url.pathname)
    if (url.origin === base) return route.continue()
    if (url.hostname === 'localhost' && url.port === '9527' && url.pathname.startsWith('/api/')) {
      try {
        const response = await route.fetch({ url: `${base}${url.pathname}${url.search}`, maxRedirects: 0, headers: { ...route.request().headers(), origin: base } })
        return route.fulfill({ response, headers: { ...response.headers(), 'access-control-allow-origin': base } })
      } catch {
        // The deliberate server crash can race the page's poll. Preserve the real network failure,
        // not a fabricated application response or an unhandled route-handler rejection.
        return route.abort('connectionrefused')
      }
    }
    return route.abort('blockedbyclient') // All websites/accounts/third-party traffic blocked.
  })
  const page = await context.newPage(); page.on('pageerror', error => failures.push(error.message))
  await page.goto(`${base}/workbench`)
  await page.getByRole('button', { name: '一次启动本轮求职', exact: true }).click()
  const progress = page.getByRole('region', { name: '整轮进展与安全断点', exact: true })
  const screenshot = async name => {
    await page.waitForFunction(() => {
      let node = document.querySelector('[aria-label="整轮进展与安全断点"]')
      if (!node) return false
      for (; node; node = node.parentElement) if (Number(getComputedStyle(node).opacity) < 0.99) return false
      return true
    })
    await page.screenshot({ path: join(root, name), fullPage: true })
  }
  await progress.getByText('Synthetic b · Python engineer · 已知未外发 · 需处理例外', { exact: true }).waitFor()
  await until(async () => !(await get()).runtime.busy, 'First turn completion')
  let state = await get()
  assert.equal(state.tasks.filter(task => task.state === 'SUBMITTED').length, 2)
  assert.equal(state.tasks.filter(task => task.state === 'NEEDS_INPUT').length, 1)
  assert.equal(state.scope.usage.outbound, 3)
  assert.equal(starts.length, 1, 'Normal stages and jobs must not require another start')
  await screenshot('workflow-exception.png')
  const completed = state.tasks.filter(task => task.state === 'SUBMITTED')
  await writeFile(join(root, 'mode'), 'resolved')
  await progress.getByRole('button', { name: '处理例外后恢复同一轮', exact: true }).click()
  await until(async () => { const s = await get(); return !s.runtime.busy && s.tasks.every(task => task.state === 'SUBMITTED') }, 'Resume safe login checkpoint')
  await page.reload(); await progress.getByRole('heading', { name: /本轮处理结束/ }).waitFor()
  state = await get(); assert.equal(state.tasks.length, 3); assert.equal(state.scope.usage.outbound, 4)
  for (const task of completed) assert.deepEqual(state.tasks.find(row => row.id === task.id), task)
  const calls = (await readFile(join(root, 'calls'), 'utf8')).trim().split('\n')
  assert.equal(calls.filter(kind => kind === 'CANDIDATE_READ').length, 2)
  assert.equal(calls.filter(kind => kind === 'CANDIDATE_MATCH').length, 1)
  assert.equal(calls.filter(kind => kind === 'MATERIAL_PREPARE').length, 1)
  await screenshot('workflow-completed.png')
  // Crash after contact. The next application instance must release only its own orphan child,
  // invalidate that callback credential and preserve UNKNOWN before another job may advance.
  await writeFile(join(root, 'mode'), 'crash')
  const cycle = await post('/cycles', { scopeVersion: 1, maxPages: 1, jobsPerPage: 5, secondsPerPage: 10, secondsPerBatch: 10, modelTokensPerCandidate: 100, sources: [{ channel: 'OFFERTODAY', url: 'https://www.offertoday.com/hk/recommend', region: 'HK', language: 'en', direction: 'AI_APPLICATION' }] })
  await until(async () => { try { await readFile(join(root, 'held')); return true } catch { return false } }, 'Contact interruption barrier')
  const stale = JSON.parse(await readFile(join(root, 'held'), 'utf8'))
  server.kill('SIGKILL'); await once(server, 'exit'); launch(); await ready()
  assert.throws(() => process.kill(stale.pid, 0), 'Owned orphan must be gone before reopening the shared slot')
  const rejected = await fetch(stale.callbackUrl, { method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${stale.token}` }, body: JSON.stringify({ type: 'SUBMITTING', action: 'SEND_CV', message: 'Stale fixture executor' }) })
  assert.equal(rejected.status, 403)
  state = await get(); const unknown = state.tasks.find(task => task.company === 'Synthetic crash-c')
  assert.equal(unknown.state, 'UNKNOWN'); assert(unknown.contactReceipt); assert(!unknown.cvReceipt)
  await writeFile(join(root, 'mode'), 'resolved')
  await post(`/cycles/${cycle.id}/resume`, { scopeVersion: 1 })
  await until(async () => !(await get()).runtime.busy, 'Restart resume')
  state = await get(); assert.deepEqual(state.tasks.find(task => task.id === unknown.id), unknown)
  assert.equal(state.tasks.find(task => task.company === 'Synthetic crash-d').state, 'SUBMITTED')
  await page.reload(); await progress.getByText('Synthetic crash-c · Python engineer · 外发结果未知 · 禁止重投', { exact: true }).waitFor()
  await screenshot('workflow-unknown-restart.png')
  assert.deepEqual(failures, [])
  console.log(JSON.stringify({ root, port, browserStarts: starts.length, submitted: state.tasks.filter(t => t.state === 'SUBMITTED').length, unknown: state.tasks.filter(t => t.state === 'UNKNOWN').length, outbound: state.scope.usage.outbound, screenshots: ['workflow-exception.png', 'workflow-completed.png', 'workflow-unknown-restart.png'] }, null, 2))
} finally {
  if (browser) await browser.close()
  if (server && server.exitCode === null) { server.kill('SIGTERM'); await once(server, 'exit') }
  await log.close()
  await unlink(join(root, 'held')).catch(() => {}) // discard this fixture-only callback credential
}
