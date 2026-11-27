// Real packaged HTTP/UI, synthetic data only; independent checker really exercises a temporary Python project.
import assert from 'node:assert/strict'
import { mkdtemp, mkdir, writeFile, open } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join, resolve } from 'node:path'
import { pathToFileURL } from 'node:url'
import { createServer } from 'node:net'
import { spawn, execFileSync } from 'node:child_process'
import { once } from 'node:events'
import { setTimeout as delay } from 'node:timers/promises'
assert(process.env.PLAYWRIGHT_MODULE, 'Set PLAYWRIGHT_MODULE to an installed Playwright module')
const { default: { chromium } } = await import(pathToFileURL(resolve(process.env.PLAYWRIGHT_MODULE)).href)
const jar = resolve(process.env.WORKBENCH_SMOKE_JAR || 'build/libs/get_jobs-2.0.1.jar')
const root = await mkdtemp(join(tmpdir(), 'workbench-verification-smoke-'))
const probe = createServer().listen(0, '127.0.0.1'); await once(probe, 'listening')
const port = probe.address().port; await new Promise(resolve => probe.close(resolve))
const base = `http://127.0.0.1:${port}`
const app = join(root, 'app')
await mkdir(app); await mkdir(join(root, 'projects'))
const ai = join(root, 'synthetic-ai.py'), executor = join(root, 'project-executor.py'), verifier = join(root, 'independent-checker.py')
await writeFile(ai, `import json,sys
b=json.load(open(sys.argv[-1]))
if b['kind']=='CANDIDATE_READ':
    jd='Build Python data pipelines with pipeline experience, repeatable fixture tests and failure recovery. Requires five years of commercial work; local practice must never be presented as employment.'
    out={'jobs':[{'url':'https://careers.example.test/jobs/gap','title':'Pipeline engineer','company':'Synthetic Co','description':jd,'descriptionComplete':True}]}
else:
    out={'results':[]}
    for j in b['candidates']:
        f=next((x for x in j['facts'] if x.get('evidenceKind')=='LOCAL_PROJECT'),j['facts'][0])
        local=f.get('evidenceKind')=='LOCAL_PROJECT'
        finding={'kind':'SUPPORTED' if local else 'EXPERIENCE_GAP','requirementQuote':'pipeline experience','reason':'Only actual local evidence is cited','evidence':[{'type':'FACT','id':f['id'],'quote':f['value']}]}
        if not local: finding['suggestion']={'goal':'Build a local synthetic pipeline','acceptance':'Repeatable fixture tests and invalid-input recovery'}
        out['results'].append({'candidateId':j['candidateId'],'jdSha256':j['jdSha256'],'findings':[finding]})
json.dump(out,open(b['resultPath'],'w'))
`)
await writeFile(executor, `import json,sys,pathlib
b=json.load(open(sys.argv[-1])); d=pathlib.Path(b['directory'])
(d/'pipeline.py').write_text('import json,sys\\ntry: print(sum(json.load(sys.stdin)))\\nexcept Exception: sys.exit(2)\\n')
json.dump({'summary':'Executor claims completion, not an independent pass','acceptanceReport':'Self-report only','artifacts':[{'path':'pipeline.py','description':'Synthetic local pipeline'}]},open(b['resultPath'],'w'))
`)
await writeFile(verifier, `import json,sys,pathlib,subprocess
b=json.load(open(sys.argv[-1])); d=pathlib.Path(b['directory'])
assert b['kind']=='PROJECT_VERIFY' and b['limits']['outbound']==0 and b['limits']['modelTokens']==0
assert 'token' not in b and 'facts' not in b
checks=[]
for data,expected in [('[1,2,3]','6'),('[]','0'),('bad input',None)]:
    p=subprocess.run([sys.executable,str(d/'pipeline.py')],input=data,text=True,capture_output=True,timeout=1)
    ok=(p.returncode!=0) if expected is None else p.returncode==0 and p.stdout.strip()==expected
    checks.append({'criterion':b['acceptance'],'passed':ok,'evidence':f'Independent fixture {data}: stdout={p.stdout.strip()}, exit={p.returncode}'})
json.dump({'projectId':b['projectId'],'attemptId':b['attemptId'],'evidenceSha256':b['evidenceSha256'],'acceptance':b['acceptance'],'passed':all(c['passed'] for c in checks),'checks':checks},open(b['resultPath'],'w'))
`)
execFileSync('python3', ['-c', `import sqlite3,sys
with sqlite3.connect(sys.argv[1]) as c:
 c.executescript('CREATE TABLE default_page_config_seed(id INTEGER PRIMARY KEY); INSERT INTO default_page_config_seed VALUES(1); CREATE TABLE config(id INTEGER PRIMARY KEY, config_key TEXT, config_value TEXT, config_type TEXT, category TEXT, description TEXT, created_at TEXT, updated_at TEXT);')
`, join(root, 'empty-legacy.db')])
const log = await open(join(root, 'server.log'), 'w')
const java = process.env.JAVA_HOME ? join(process.env.JAVA_HOME, 'bin/java') : 'java'
const server = spawn(java, ['-jar', jar, `--server.port=${port}`, '--server.address=127.0.0.1', '--browser.auto-start=false',
  `--spring.datasource.url=jdbc:sqlite:${join(root, 'empty-legacy.db')}`, `--workbench.data-dir=${join(root, 'state')}`, `--workbench.jobsdb-dir=${join(root, 'empty-jobsdb')}`,
  `--workbench.projects-root=${join(root, 'projects')}`, `--workbench.agent-command=${JSON.stringify(['python3', ai, '{taskFile}'])}`,
  `--workbench.project-command=${JSON.stringify(['python3', executor, '{taskFile}'])}`, `--workbench.project-verifier-command=${JSON.stringify(['python3', verifier, '{taskFile}'])}`,
  `--logging.file.name=${join(root, 'application.log')}`], { cwd: app, stdio: ['ignore', log.fd, log.fd] })
const get = async path => { const r = await fetch(`${base}/api/workbench${path}`); assert(r.ok, `${path}: ${r.status}`); return r.json() }
const post = async (path, body) => { const r = await fetch(`${base}/api/workbench${path}`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) }); assert(r.ok, `${path}: ${r.status} ${await r.clone().text()}`); return r.json() }
let browser
try {
  let ready = false
  for (let i = 0; i < 200; i++) {
    assert(server.exitCode === null, `Isolated server exited: ${root}/server.log`)
    try { ready = (await fetch(`${base}/api/workbench/state`)).ok } catch { /* this owned server only */ }
    if (ready) break
    await delay(100)
  }
  assert(ready, `Server did not start: ${root}`)
  const fact = await post('/facts', { key: 'python', label: 'Synthetic local practice', value: 'Built local demos; no commercial pipeline experience.', scope: 'HK', confirmed: true })
  await post('/scope', { channels: ['OFFICIAL'], hosts: ['careers.example.test'], regions: ['HK'], languages: ['en'], directions: ['AI_APPLICATION'], transformations: ['REORDER'], factIds: [fact.id], budgets: { candidates: 10, seconds: 300, modelTokens: 30000, outbound: 0 }, expiresAt: new Date(Date.now() + 3600000).toISOString() })
  await post('/candidates/discover', { scopeVersion: 1, maxPages: 1, jobsPerPage: 1, secondsPerPage: 2, sources: [{ channel: 'OFFICIAL', url: 'https://careers.example.test/jobs', region: 'HK', language: 'en', direction: 'AI_APPLICATION' }] })
  await post('/candidates/match', { scopeVersion: 1, secondsPerBatch: 2 })
  const gap = (await get('/candidates/gaps')).suggestions[0]
  const project = await post('/projects', { candidateId: gap.candidateId, analysisId: gap.analysisId, requirementQuote: gap.requirementQuote })
  const scope = (await get('/scope')).current
  await post('/scope', { ...scope, expectedVersion: 1, actions: [...scope.actions, 'PROJECT_EXECUTE', 'PROJECT_VERIFY', 'PROJECT_FEEDBACK'], projectIds: [project.id], projectResources: ['LOCAL_FILES', 'LOCAL_PROCESS'], projectFeedbackIds: [project.id] })
  await post(`/projects/${project.id}/start`, { scopeVersion: 2, seconds: 3 })
  let completed
  for (let i = 0; i < 100; i++) {
    completed = (await get('/projects')).projects[0]
    if (completed.status === 'UNVERIFIED') break
    await delay(50)
  }
  assert.equal(completed.status, 'UNVERIFIED')
  assert.equal((await get('/state')).facts.length, 1)
  browser = await chromium.launch({ channel: 'chrome', headless: true })
  const context = await browser.newContext({ viewport: { width: 1365, height: 1000 }, serviceWorkers: 'block' })
  await context.route('**/*', async route => {
    const url = new URL(route.request().url())
    if (url.origin === base) return route.continue()
    return route.abort('blockedbyclient')
  })
  const page = await context.newPage(), errors = []
  page.on('pageerror', error => errors.push(error.message)); page.on('dialog', dialog => dialog.accept())
  await page.goto(`${base}/workbench`)
  const panel = page.getByRole('region', { name: '独立项目队列', exact: true })
  await panel.getByText('产物待独立验证', { exact: true }).waitFor()
  await panel.getByLabel('每次项目运行秒数上限').fill('3')
  await panel.getByRole('button', { name: '独立验收 / 按范围回流成果', exact: true }).click()
  await panel.getByText('独立验收通过 · 回流仍按当前范围', { exact: true }).waitFor()
  await panel.getByText('独立验证 · PASSED · INDEPENDENT_VERIFIER', { exact: true }).waitFor()
  await panel.getByText(/Independent fixture \[1,2,3\]: stdout=6/).waitFor()
  await panel.getByText(/有限成果事实 .*not employment/).waitFor()
  await panel.scrollIntoViewIfNeeded(); await page.screenshot({ path: join(root, 'verification.png'), fullPage: true })
  const state = await get('/state'), analysis = state.candidates.jobs[0].analysis
  assert.equal(state.facts.length, 2); assert.equal(state.tasks.length, 0); assert.equal(state.scope.usage.outbound, 0)
  assert.equal(analysis.status, 'EXPERIENCE_GAP'); assert.equal(analysis.findings[0].kind, 'SUPPORTED')
  assert(analysis.findings.some(f => f.kind === 'EXPERIENCE_GAP' && f.requirementQuote.includes('five years')))
  await page.reload(); await panel.getByText('独立验证 · PASSED · INDEPENDENT_VERIFIER', { exact: true }).waitFor()
  assert.deepEqual(errors, [])
  console.log(JSON.stringify({ root, screenshot: join(root, 'verification.png'), projectId: project.id, verification: state.projects.projects[0].verifications[0].status, facts: state.facts.length, tasks: state.tasks.length, outbound: state.scope.usage.outbound, match: analysis.status }))
} finally {
  if (browser) await browser.close()
  server.kill('SIGTERM'); if (server.exitCode === null) await once(server, 'exit')
  await log.close()
}
