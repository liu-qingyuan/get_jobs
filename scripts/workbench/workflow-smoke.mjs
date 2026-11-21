// #14–17 packaged UI + real HTTP, synthetic data/fake children only. Never touches a running workbench.
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
const projectRoot = await mkdtemp(join(tmpdir(), 'workbench-project-output-'))
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
    names=['project-gap','project-ready'] if mode.startswith('project') else ['crash-c','crash-d'] if mode=='crash' else ['c'] if offer else ['a','b']
    jd='Responsibilities: build and evaluate Python search applications using synthetic data. Requirements: demonstrable Python projects, clear documentation and repeatable evaluation tests.'
    json.dump({'jobs':[{'url':('https://www.offertoday.com/hk/job/synthetic-job-' if offer else 'https://careers.example.test/jobs/')+n,'company':'Synthetic '+n,'title':'Python engineer','description':jd,'descriptionComplete':True} for n in names]},open(b['resultPath'],'w'));sys.exit(0)
if b.get('kind')=='CANDIDATE_MATCH':
    results=[]
    for j in b['candidates']:
        gap=j['url'].endswith('project-gap')
        finding={'kind':'EXPERIENCE_GAP' if gap else 'SUPPORTED','requirementQuote':'Python','reason':'Confirmed synthetic demo is not production experience','evidence':[{'type':'FACT','id':j['facts'][0]['id'],'quote':j['facts'][0]['value']}]}
        if gap: finding['suggestion']={'goal':'Synthetic local evaluation project','acceptance':'Repeatable synthetic fixture tests; not employment or commercial years'}
        results.append({'candidateId':j['candidateId'],'jdSha256':j['jdSha256'],'findings':[finding]})
    json.dump({'results':results},open(b['resultPath'],'w'));sys.exit(0)
if b.get('kind')=='MATERIAL_PREPARE':
    json.dump({'results':[{'candidateId':j['candidateId'],'resumeId':j['resumes'][0]['id'],'transformation':'REORDER','factIds':[j['facts'][0]['id']]} for j in b['candidates']]},open(b['resultPath'],'w'));sys.exit(0)
if b.get('kind')=='PROJECT_EXECUTE':
    assert 'token' not in b and 'callbackUrl' not in b and 'facts' not in b and 'resumePath' not in b
    assert b['limits']['outbound']==0 and b['limits']['modelTokens']==0
    d=pathlib.Path(b['directory']);assert pathlib.Path.cwd()==d
    (d/'checkpoint.txt').write_text('Synthetic local checkpoint, not a confirmed fact')
    json.dump({'message':'Synthetic fixture project progress'},open(b['progressPath'],'w'))
    if mode=='project-running':
        (root/'project-held').write_text(str(os.getpid()))
        while True: time.sleep(.1)
    json.dump({'summary':'Executor self report only','acceptanceReport':'Synthetic tests claimed passed; independent verification pending','artifacts':[] if mode=='project-false' else [{'path':'checkpoint.txt','description':'Synthetic project checkpoint'}]},open(b['resultPath'],'w'));sys.exit(0)
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
const jobsdbFake = join(root, 'jobsdb-fake.py')
await writeFile(jobsdbFake, `import json,sys
assert sys.argv[1]=='read-candidates' and sys.argv[3]=='--data-dir'
b=json.load(open(sys.argv[2]));assert b['source']['channel']=='JOBSDB' and 'token' not in b and 'resumePath' not in b
json.dump({'jobs':[{'url':'https://hk.jobsdb.com/job/12345678?ref=search','company':'Synthetic JobsDB','title':'Python engineer','description':'Responsibilities: build Python search applications with repeatable tests. Requirements: Python projects, evaluation and documentation, with synthetic data only.','descriptionComplete':True}]},open(b['resultPath'],'w'))
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
    `--workbench.projects-root=${projectRoot}`, `--workbench.project-command=${JSON.stringify(['python3', fake, '{taskFile}'])}`,
    `--workbench.jobsdb-command=${JSON.stringify(['python3', jobsdbFake])}`, `--workbench.offertoday-command=${JSON.stringify(['python3', fake])}`, `--workbench.agent-command=${JSON.stringify(['python3', fake, '{taskFile}'])}`, `--logging.file.name=${join(root, 'application.log')}`,
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
  // #15: no Boss login/session is ever provided. The real HTTP path must show a read exception,
  // capability limits and no official-AI fallback or additional send, even with a scope that allows CONTACT.
  const beforeBossOutbound = state.scope.usage.outbound
  await post('/scope', { ...state.scope.current, expectedVersion: 1, channels: ['OFFICIAL', 'OFFERTODAY', 'BOSS'], hosts: ['careers.example.test', 'www.offertoday.com', 'www.zhipin.com'], regions: ['HK', 'MAINLAND'] })
  await page.reload()
  const discovery = page.getByRole('region', { name: '只读候选发现', exact: true })
  await discovery.getByRole('button', { name: '载入当前范围来源', exact: true }).click()
  await discovery.getByLabel('官网只读入口（每行一个）').fill('')
  await discovery.getByLabel('OfferToday 只读列表（每行一个）').fill('')
  assert.equal(await discovery.getByLabel('Boss 只读列表（每行一个）').inputValue(), 'https://www.zhipin.com/web/geek/jobs')
  await discovery.getByLabel('本次地区').selectOption('MAINLAND')
  await until(async () => !(await get()).runtime.busy, 'Scope-driven read-only reassessment')
  const beforeBossCalls = await readFile(join(root, 'calls'), 'utf8')
  await discovery.locator('summary').filter({ hasText: '高级：仅发现，不衔接后续' }).click()
  await discovery.getByRole('button', { name: '一次启动范围内只读发现', exact: true }).click()
  await page.getByText(/Boss 只读需要本副本已显式登录且空闲的浏览器/).waitFor()
  await discovery.getByText(/Boss 外发能力待核实/).waitFor()
  state = await get()
  assert.equal(state.runtime.bossOutboundSupported, false)
  assert.equal(state.scope.usage.outbound, beforeBossOutbound)
  assert.equal(await readFile(join(root, 'calls'), 'utf8'), beforeBossCalls)
  await screenshot('boss-capability-blocked.png')
  // #16: configured fake JobsDB read, unified matching/materials, no original CLI apply/batch or fallback.
  await post('/scope', { ...state.scope.current, expectedVersion: 2, channels: ['JOBSDB'], hosts: ['hk.jobsdb.com'], regions: ['HK'] })
  await page.reload()
  await discovery.getByRole('button', { name: '载入当前范围来源', exact: true }).click()
  assert.equal(await discovery.getByLabel('官网只读入口（每行一个）').inputValue(), '')
  assert.equal(await discovery.getByLabel('JobsDB 只读列表（每行一个）').inputValue(), 'https://hk.jobsdb.com/jobs')
  await discovery.getByRole('button', { name: '一次启动本轮求职', exact: true }).click()
  await progress.getByText('Synthetic JobsDB · Python engineer · 逐岗例外待处理', { exact: true }).waitFor()
  await until(async () => !(await get()).runtime.busy, 'JobsDB unified preparation')
  state = await get()
  assert.equal(state.runtime.jobsDbOutboundSupported, false)
  assert.equal(state.runtime.jobsDbProductionVerified, false)
  assert.equal(state.scope.usage.outbound, beforeBossOutbound)
  assert.equal(state.tasks.length, 5)
  const jobsdbCandidate = state.candidates.jobs.find(job => job.channel === 'JOBSDB')
  assert.equal(jobsdbCandidate.platformJobId, '12345678')
  assert.equal(jobsdbCandidate.analysis.status, 'ANALYSIS_READY')
  const jobsdbPackage = state.preparations.packages.find(pkg => pkg.target.channel === 'JOBSDB')
  assert.equal(jobsdbPackage.status, 'POLICY_APPROVED')
  await page.locator('summary').filter({ hasText: '高级：独立备料与冻结包详情' }).click()
  const material = page.getByRole('article', { name: `材料包 Synthetic JobsDB ${jobsdbPackage.id}`, exact: true })
  await material.getByText('策略核准 · 未发送', { exact: true }).waitFor()
  await material.getByRole('button', { name: '只核验 APPLY（不执行）', exact: true }).click()
  await material.getByText('APPLY：拒绝执行资格', { exact: true }).waitFor()
  await screenshot('jobsdb-prepared-capability-blocked.png')
  // #17: propose from the default candidate screen, authorize only the specific directory/resources,
  // reject executor self-report without artifacts, then kill/restart only this synthetic application.
  page.on('dialog', dialog => dialog.accept())
  await writeFile(join(root, 'mode'), 'project-false')
  const projectScope = await post('/scope', { ...state.scope.current, expectedVersion: 3, channels: ['OFFICIAL'], hosts: ['careers.example.test'], contactVerified: false, contactFactIds: [], actions: ['DISCOVER', 'MATCH', 'PREPARE'] })
  await post('/candidates/discover', { scopeVersion: projectScope.version, maxPages: 1, jobsPerPage: 2, secondsPerPage: 5, sources: [{ channel: 'OFFICIAL', url: 'https://careers.example.test/jobs', region: 'HK', language: 'en', direction: 'AI_APPLICATION' }] })
  const projectIds = (await get('/candidates')).jobs.filter(j => j.url.includes('/project-')).map(j => j.id)
  await post('/candidates/match', { scopeVersion: projectScope.version, candidateIds: projectIds, secondsPerBatch: 5 })
  await page.reload()
  await page.getByRole('article').filter({ has: page.getByRole('heading', { name: 'Synthetic project-gap · Python engineer', exact: true }) }).getByRole('button', { name: '建立独立项目建议（不执行）', exact: true }).click()
  const projects = page.getByRole('region', { name: '独立项目队列', exact: true })
  const project = projects.getByRole('article', { name: '补强项目 Synthetic local evaluation project', exact: true })
  const startProject = () => project.getByRole('button', { name: '按当前范围启动 / 恢复项目', exact: true }).click()
  await startProject(); await project.getByText('范围 / 资源阻塞', { exact: true }).waitFor()
  assert.equal((await get('/projects')).projects[0].attempts.length, 0)
  await page.getByRole('tab', { name: '求职范围', exact: true }).click()
  const scopePanel = page.getByRole('region', { name: '求职范围设置', exact: true })
  await scopePanel.getByRole('checkbox', { name: '独立项目执行（非成果入库）', exact: true }).check()
  await scopePanel.getByRole('group', { name: '独立项目允许范围', exact: true }).getByRole('checkbox', { name: /^项目：Synthetic local evaluation project/ }).check()
  await scopePanel.getByRole('checkbox', { name: '本地项目文件', exact: true }).check()
  await scopePanel.getByRole('checkbox', { name: '本地项目进程', exact: true }).check()
  await scopePanel.getByRole('button', { name: '变更范围（新版本）', exact: true }).click()
  await scopePanel.getByRole('heading', { name: /^当前范围 v5/ }).waitFor()
  await page.getByRole('tab', { name: '候选与发现', exact: true }).click()
  await startProject(); await project.getByText('执行失败 · 未确认成果', { exact: true }).waitFor()
  await until(async () => !(await get()).runtime.busy, 'False completion resource release')
  state = await get(); const factsBefore = structuredClone(state.facts), tasksBefore = structuredClone(state.tasks)
  assert.equal(state.projects.projects[0].attempts.length, 1)
  assert(!state.projects.projects[0].attempts[0].evidence)
  await writeFile(join(root, 'mode'), 'project-running')
  await startProject()
  await project.getByText('项目执行中', { exact: true }).waitFor()
  await until(async () => { try { return !!(await readFile(join(root, 'project-held'), 'utf8')) } catch { return false } }, 'Project interruption barrier')
  const projectPid = Number(await readFile(join(root, 'project-held'), 'utf8'))
  const secondsBeforeRestart = (await get('/scope')).usage.seconds
  server.kill('SIGKILL'); await once(server, 'exit'); launch(); await ready()
  assert.throws(() => process.kill(projectPid, 0), 'Owned project and descendants must be released on restart')
  const recovered = (await get('/projects')).projects[0]
  assert.equal(recovered.status, 'STOPPED'); assert.equal(recovered.attempts.length, 2)
  assert.equal((await get('/scope')).usage.seconds, secondsBeforeRestart)
  assert.match(await readFile(join(projectRoot, recovered.id, 'checkpoint.txt'), 'utf8'), /Synthetic local checkpoint/)
  await page.reload(); await project.getByText('已停止 · 待显式恢复', { exact: true }).waitFor()
  await writeFile(join(root, 'mode'), 'project-complete')
  await startProject(); await project.getByText('产物待独立验证', { exact: true }).waitFor()
  await project.getByText(/SHA256 [a-f0-9]{64}/).waitFor()
  await until(async () => !(await get()).runtime.busy, 'Project final resource release')
  state = await get(); assert.deepEqual(state.facts, factsBefore); assert.deepEqual(state.tasks, tasksBefore)
  assert.equal(state.projects.projects[0].attempts.length, 3)
  assert.equal(state.projects.projects[0].attempts[2].evidence.verified, false)
  assert.equal(state.scope.usage.outbound, beforeBossOutbound)
  await screenshot('project-unverified.png')
  await page.reload(); await project.getByText('产物待独立验证', { exact: true }).waitFor()
  assert.equal(await project.getByRole('button', { name: '按当前范围启动 / 恢复项目', exact: true }).count(), 0)
  await page.getByRole('tab', { name: '求职范围', exact: true }).click()
  await page.getByRole('region', { name: '范围历史与动作依据', exact: true }).getByText(new RegExp(`独立项目执行（非成果入库） · ${recovered.id}`), { exact: false }).first().waitFor()
  assert.deepEqual(failures, [])
  console.log(JSON.stringify({ root, projectRoot, port, browserStarts: starts.length, submitted: state.tasks.filter(t => t.state === 'SUBMITTED').length, unknown: state.tasks.filter(t => t.state === 'UNKNOWN').length, outbound: state.scope.usage.outbound, projectAttempts: state.projects.projects[0].attempts.length, projectStatus: state.projects.projects[0].status, screenshots: ['workflow-exception.png', 'workflow-completed.png', 'workflow-unknown-restart.png', 'boss-capability-blocked.png', 'jobsdb-prepared-capability-blocked.png', 'project-unverified.png'] }, null, 2))
} finally {
  if (browser) await browser.close()
  if (server && server.exitCode === null) { server.kill('SIGTERM'); await once(server, 'exit') }
  await log.close()
  await unlink(join(root, 'held')).catch(() => {}) // discard this fixture-only callback credential
}
