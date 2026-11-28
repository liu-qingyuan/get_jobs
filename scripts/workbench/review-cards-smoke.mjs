// #49 岗位审核默认页：静态导出 + Playwright，只拦截 /api/**，不连接真实后端或平台。
//
// 先构建：cd front && ./node_modules/.bin/next build
// 运行：
//   PLAYWRIGHT_MODULE=/path/to/playwright/index.js node scripts/workbench/review-cards-smoke.mjs
//
// 合成数据只存在本测试进程内；页面本身不包含样例卡。
import assert from 'node:assert/strict'
import { createServer } from 'node:http'
import { mkdtemp, readFile, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { extname, join, resolve } from 'node:path'
import { pathToFileURL } from 'node:url'
import { once } from 'node:events'
import { setTimeout as delay } from 'node:timers/promises'

assert(process.env.PLAYWRIGHT_MODULE, 'Set PLAYWRIGHT_MODULE to an installed playwright module')
const { default: { chromium } } = await import(pathToFileURL(resolve(process.env.PLAYWRIGHT_MODULE)).href)

const frontRoot = resolve('front')
const outDir = join(frontRoot, 'out')
try {
  await readFile(join(outDir, 'workbench.html'))
} catch {
  throw new Error(`missing static export ${join(outDir, 'workbench.html')}; run: cd front && ./node_modules/.bin/next build`)
}

const JD = 'Build and evaluate AI search applications with Python and synthetic data.\n'
  + 'Communicate clearly in written English with reproducible evaluation notes and tests.'

function canonical(channel) {
  const value = (channel || '').trim().toUpperCase()
  return value === '51JOB' ? 'JOB51' : value
}

/** 后端 GET /api/workbench/review-cards 的合成响应；顺序即后端查询顺序。 */
const cardsAll = [
  {
    candidateId: 'c-match', channel: 'OFFERTODAY', company: 'Synthetic Offer', title: 'AI 应用工程师',
    url: 'https://www.offertoday.com/hk/job/synthetic-offer', region: 'HK', language: 'en',
    lastSeenAt: '2026-11-29T02:00:00Z', stale: false,
    description: JD, descriptionComplete: true,
    status: 'ANALYSIS_READY', statusLabel: '已分析', verdict: 'MATCH', verdictLabel: '建议投',
    reason: '', nextAction: '暂无建议',
    requirements: [
      {
        kind: 'SUPPORTED', requirementQuote: '熟练使用 Python 构建可复现的评估流程。',
        reason: '已确认的合成项目直接支持。', gap: false, gapReason: '',
        evidence: [{ type: 'FACT', id: 'fact-1', quote: '已用合成数据构建 Python 搜索项目。', label: 'Python 项目' }],
      },
      {
        kind: 'NEEDS_FACTS', requirementQuote: '具备英文书面沟通经验。',
        reason: '需要确认书面英语证据。', gap: true, gapReason: '缺少已确认的英文书面证据。',
        evidence: [],
      },
    ],
    preparation: { status: 'PREPARED', label: '已准备' },
  },
  {
    candidateId: 'c-gap', channel: 'OFFERTODAY', company: 'Synthetic Gap', title: '搜索工程师',
    url: 'https://www.offertoday.com/hk/job/synthetic-gap', region: 'HK', language: 'zh',
    lastSeenAt: '2026-11-29T01:00:00Z', stale: false,
    description: '负责构建搜索与排序系统。\n要求可复现的离线评测和清晰的书面沟通。', descriptionComplete: true,
    status: 'EXPERIENCE_GAP', statusLabel: '经验缺口', verdict: 'MISMATCH', verdictLabel: '不匹配',
    reason: '', nextAction: '项目目标：搭建本地评估工具；验收：固定夹具测试通过',
    requirements: [{
      kind: 'EXPERIENCE_GAP', requirementQuote: '具备生产级搜索排序经验。',
      reason: '没有已确认的生产级搜索排序经历。', gap: true, gapReason: '缺少生产级搜索排序经历。',
      evidence: [{ type: 'FACT', id: 'fact-1', quote: '已用合成数据构建 Python 搜索项目。', label: 'Python 项目' }],
      suggestion: { goal: '搭建本地评估工具', acceptance: '固定夹具测试通过' },
    }],
    preparation: { status: 'NOT_PREPARED', label: '未准备' },
  },
  {
    candidateId: 'c-empty', channel: 'BOSS', company: 'Synthetic Empty', title: '算法工程师',
    url: 'https://www.zhipin.com/job_detail/synthetic-empty.html', region: 'MAINLAND', language: 'zh',
    lastSeenAt: '2026-11-29T00:30:00Z', stale: false,
    description: JD, descriptionComplete: true,
    status: 'ANALYSIS_READY', statusLabel: '已分析', verdict: 'UNDETERMINED', verdictLabel: '待定',
    reason: '暂无分析依据', nextAction: '暂无建议',
    requirements: [], preparation: { status: 'NOT_PREPARED', label: '未准备' },
  },
  {
    candidateId: 'c-unanalyzed', channel: 'ZHILIAN', company: 'Synthetic New', title: '后端工程师',
    url: 'https://www.zhaopin.com/job/synthetic-new', region: 'MAINLAND', language: 'zh',
    lastSeenAt: '2026-11-29T00:10:00Z', stale: false,
    description: JD, descriptionComplete: true,
    status: 'NOT_ANALYZED', statusLabel: '未分析', verdict: 'UNDETERMINED', verdictLabel: '待定',
    reason: '', nextAction: '暂无建议',
    requirements: [], preparation: { status: 'NOT_PREPARED', label: '未准备' },
  },
  {
    candidateId: 'c-ineligible', channel: 'JOBSDB', company: 'Synthetic Expired', title: '数据工程师',
    url: 'https://hk.jobsdb.com/job/synthetic-expired', region: 'HK', language: 'en',
    lastSeenAt: '2026-11-29T00:05:00Z', stale: false,
    description: JD, descriptionComplete: true,
    status: 'INELIGIBLE', statusLabel: '不适用', verdict: 'UNDETERMINED', verdictLabel: '待定',
    reason: '不适用（范围已到期）', nextAction: '暂无建议',
    requirements: [], preparation: { status: 'NOT_PREPARED', label: '未准备' },
  },
  {
    candidateId: 'c-exception', channel: 'LIEPIN', company: 'Synthetic Exception', title: '平台工程师',
    url: 'https://www.liepin.com/job/synthetic-exception', region: 'MAINLAND', language: 'zh',
    lastSeenAt: '2026-11-29T00:01:00Z', stale: false,
    description: JD, descriptionComplete: true,
    status: 'ANALYSIS_EXCEPTION', statusLabel: '分析异常', verdict: 'UNDETERMINED', verdictLabel: '待定',
    reason: '合成分析异常：执行器超时', nextAction: '暂无建议',
    requirements: [], preparation: { status: 'MATERIAL_EXCEPTION', label: '材料异常' },
  },
]

/** 审计视图所需的最小 /state；只用于证明进入审计后才读取，且旧内容仍可见。 */
const statePayload = {
  facts: [],
  resumes: [],
  tasks: [{
    id: 'task-1', kind: 'APPLICATION', channel: 'OFFICIAL', region: 'HK', language: 'en',
    direction: 'AI_APPLICATION', company: 'Synthetic Co', title: '合成岗位申请',
    url: 'https://careers.example.test/job/1', description: '合成审计任务，仅用于确认旧任务与账本仍可查看。',
    note: '', state: 'PENDING', createdAt: '2026-11-29T02:00:00Z', updatedAt: '2026-11-29T02:00:00Z',
    events: [{ id: 'event-1', type: 'PROGRESS', message: '合成人工报告', source: '合成测试', at: '2026-11-29T02:00:00Z' }],
  }],
  materials: { sources: [], reviews: [] },
  scope: { current: undefined, history: [], usage: { candidates: 0, modelTokens: 0, seconds: 0, outbound: 0 }, authorizations: [] },
  candidates: { jobs: [], runs: [] },
  preparations: { packages: [] },
  jobsdbAttempts: [],
  workflow: { cycles: [] },
  projects: { projects: [] },
  runtime: {
    verifierConfigured: false, projectConfigured: false, agentConfigured: false, offerTodayConfigured: false,
    busy: false, bossReadConfigured: false, bossOutboundSupported: false, bossCapabilityReason: '合成未配置',
    jobsDbConfigured: false, jobsDbProductionVerified: false, jobsDbOutboundSupported: false,
    jobsDbCapabilityReason: '合成未配置', jobsDbSingleJobSupported: false, jobsDbSingleJobReason: '合成未配置',
  },
  platforms: {
    bossUrl: '', offerTodayUrl: '', jobsdbAvailable: false,
    jobsdbHistory: [{ id: 'ledger-1', status: 'SUBMITTED', updated_at: '2026-11-29T02:00:00Z', provenance: 'ORIGINAL_LEDGER', provenanceNote: '合成只读账本记录' }],
  },
}

const mime = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8', '.json': 'application/json; charset=utf-8',
  '.svg': 'image/svg+xml', '.ico': 'image/x-icon', '.txt': 'text/plain; charset=utf-8',
  '.woff2': 'font/woff2', '.png': 'image/png',
}

const staticServer = createServer(async (request, response) => {
  const pathname = decodeURIComponent(new URL(request.url, 'http://127.0.0.1').pathname)
  const clean = pathname.replace(/\/+$/, '') || '/index.html'
  const candidates = clean === '/' ? [join(outDir, 'index.html')] : [
    join(outDir, clean), join(outDir, `${clean}.html`), join(outDir, clean, 'index.html'),
  ]
  for (const file of candidates) {
    try {
      const body = await readFile(file)
      response.writeHead(200, { 'Content-Type': mime[extname(file)] || 'application/octet-stream' })
      response.end(body)
      return
    } catch { /* try next mapping */ }
  }
  response.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' })
  response.end('not found')
})
staticServer.listen(0, '127.0.0.1')
await once(staticServer, 'listening')
const base = `http://127.0.0.1:${staticServer.address().port}`
const screenshots = await mkdtemp(join(tmpdir(), 'review-cards-smoke-'))

let browser
const networkLog = []

async function openPage(mode) {
  const context = await browser.newContext({ viewport: { width: 1440, height: 1100 }, serviceWorkers: 'block' })
  const records = { workbench: [], other: [], external: [], writes: [], state: [] }
  await context.route('**/*', async route => {
    const request = route.request()
    const url = new URL(request.url())
    if (url.origin !== base) {
      records.external.push(url.href)
      return route.abort('blockedbyclient')
    }
    if (url.pathname === '/api/health' || url.pathname === '/actuator/health') {
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ status: 'UP' }) })
    }
    if (url.pathname.startsWith('/api/workbench/')) {
      records.workbench.push({ method: request.method(), path: url.pathname, search: url.search })
      if (request.method() !== 'GET') {
        records.writes.push(`${request.method()} ${url.pathname}`)
        return route.fulfill({ status: 405, contentType: 'application/json', body: JSON.stringify({ error: '合成测试只允许只读' }) })
      }
      if (url.pathname === '/api/workbench/review-cards') {
        if (mode === 'error') return route.fulfill({ status: 503, contentType: 'application/json', body: JSON.stringify({ error: '合成后端不可用（503）' }) })
        if (mode === 'empty') return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ cards: [] }) })
        const requested = canonical(url.searchParams.get('channel'))
        const cards = requested ? cardsAll.filter(card => card.channel === requested) : cardsAll
        return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ cards }) })
      }
      if (url.pathname === '/api/workbench/state') {
        records.state.push(url.pathname)
        return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(statePayload) })
      }
      return route.fulfill({ status: 404, contentType: 'application/json', body: JSON.stringify({ error: `合成测试未提供 ${url.pathname}` }) })
    }
    if (url.pathname.startsWith('/api/')) {
      records.other.push(`${request.method()} ${url.pathname}`)
      if (request.method() !== 'GET') records.writes.push(`${request.method()} ${url.pathname}`)
      return route.fulfill({ status: 404, contentType: 'application/json', body: JSON.stringify({ error: `合成测试未提供 ${url.pathname}` }) })
    }
    return route.continue()
  })
  const page = await context.newPage()
  const errors = []
  page.on('pageerror', error => errors.push(error.message))
  return { context, page, records, errors }
}

function assertReadOnly(records, label, { allowState = false } = {}) {
  assert.deepEqual(records.external, [], `${label}: 外部请求必须为零`)
  assert.deepEqual(records.other, [], `${label}: 不得请求其他 API`)
  assert.deepEqual(records.writes, [], `${label}: 写请求必须为零`)
  const allowed = new Set(['/api/workbench/review-cards', ...(allowState ? ['/api/workbench/state'] : [])])
  for (const call of records.workbench) {
    assert.equal(call.method, 'GET', `${label}: ${call.path} 必须是 GET`)
    assert.equal(allowed.has(call.path), true, `${label}: 未预期的数据请求 ${call.path}`)
  }
}

function recordNetwork(label, records) {
  networkLog.push({
    label,
    workbench: records.workbench,
    stateCount: records.state.length,
    other: records.other,
    writes: records.writes,
    external: records.external,
  })
}

async function cardIds(page) {
  return page.locator('[data-review-card]').evaluateAll(nodes => nodes.map(node => node.getAttribute('data-review-card')))
}

async function waitForCards(page, count) {
  await page.waitForFunction(expected => document.querySelectorAll('[data-review-card]').length === expected, count)
}

async function waitForText(page, text) {
  await page.getByText(text, { exact: false }).first().waitFor()
}

async function settle(page) {
  await delay(900) // 等 PageHeader / 卡片动画稳定后再截图
}

async function until(check, label, timeoutMs = 5000) {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    if (await check()) return
    await delay(50)
  }
  throw new Error(`timeout: ${label}`)
}

try {
  browser = await chromium.launch({ channel: 'chrome', headless: true })

  // 1) 合成成功响应：默认岗位审核、旧版布局、完整 JD、证据、准备摘要、下一步。
  {
    const { context, page, records, errors } = await openPage('success')
    await page.goto(`${base}/workbench`)
    await waitForCards(page, cardsAll.length)
    await settle(page)
    assert.equal(await page.getByRole('tab', { name: '岗位审核', exact: true }).getAttribute('aria-selected'), 'true')
    const tabs = await page.getByRole('tab').allTextContents()
    assert.deepEqual(tabs, ['岗位审核', '高级 / 审计'], '高级 / 审计必须是最后一个 tab')
    assert.equal(await page.locator('[data-review-filter]').count(), 5)
    assert.equal(await page.locator('[data-review-filter="ALL"]').getAttribute('aria-pressed'), 'true')
    assert.equal(await page.locator('select#review-channel').inputValue(), '')
    assert.deepEqual(await cardIds(page), cardsAll.map(card => card.candidateId), '页面必须保留后端查询顺序')
    const bodyText = await page.locator('body').innerText()
    for (const enumValue of ['MATCH', 'MISMATCH', 'UNDETERMINED', 'ANALYSIS_READY', 'NEEDS_FACTS', 'EXPERIENCE_GAP', 'NOT_ANALYZED', 'ANALYSIS_EXCEPTION', 'SUPPORTED', 'HARD_REQUIREMENT', 'NOT_APPLICABLE', 'INELIGIBLE'])
      assert.equal(new RegExp(`\\b${enumValue}\\b`).test(bodyText), false, `页面不得显示英文枚举 ${enumValue}`)
    for (const auditOnly of ['任务账本', '新建任务', '只读候选', '待关注', 'JobsDB 原账本', '人工报告 / 核对记录'])
      assert.equal(bodyText.includes(auditOnly), false, `默认审核页不得显示审计内容：${auditOnly}`)
    assert.equal(await page.locator('form').count(), 0, '默认审核页不得有任务表单')
    const detail = await page.locator('[data-review-detail]').innerText()
    for (const expected of [
      'Synthetic Offer · AI 应用工程师', 'OfferToday HK', '匹配结论：建议投', '完整 JD（完整）',
      'Build and evaluate AI search applications', 'reproducible evaluation notes and tests.',
      '熟练使用 Python 构建可复现的评估流程。', '直接支持', '已用合成数据构建 Python 搜索项目。', 'Python 项目',
      '缺少已确认的英文书面证据。', '准备摘要', '已准备', '下一步', '暂无建议',
    ]) assert.equal(detail.includes(expected), true, `详情缺少：${expected}\n${detail}`)
    await page.screenshot({ path: join(screenshots, 'review-cards-success.png'), fullPage: true })

    // 选第二岗：详情全部切换，且没有第二次详情请求。
    const beforeSelect = records.workbench.length
    await page.locator('[data-review-card="c-gap"]').click()
    await page.waitForFunction(() => document.querySelector('[data-review-detail]')?.innerText.includes('Synthetic Gap · 搜索工程师'))
    const secondDetail = await page.locator('[data-review-detail]').innerText()
    assert.equal(secondDetail.includes('Synthetic Offer'), false, '详情不得残留上一岗内容')
    for (const expected of ['经验缺口', '生产级搜索排序经验', '搭建本地评估工具', '固定夹具测试通过', '未准备'])
      assert.equal(secondDetail.includes(expected), true, `第二岗详情缺少：${expected}`)
    assert.equal(records.workbench.length, beforeSelect, '切岗不得产生第二次详情请求')
    await page.screenshot({ path: join(screenshots, 'review-cards-detail-second.png'), fullPage: true })

    // 状态筛选：建议投只含 MATCH；需补强两态；UNDETERMINED 不归建议投。
    await page.locator('[data-review-filter="MATCH"]').click()
    await waitForCards(page, 1)
    assert.deepEqual(await cardIds(page), ['c-match'])
    const afterFilterDetail = await page.locator('[data-review-detail]').innerText()
    assert.equal(afterFilterDetail.includes('Synthetic Offer'), true, '筛掉上一选中项后详情必须切到首个可见卡')
    assert.equal(afterFilterDetail.includes('Synthetic Gap'), false, '筛掉当前项后详情不得残留其他岗位')
    await page.locator('[data-review-filter="STRENGTHEN"]').click()
    await waitForCards(page, 1)
    assert.deepEqual(await cardIds(page), ['c-gap'])
    assert.equal((await page.locator('[data-review-detail]').innerText()).includes('Synthetic Gap'), true, '筛掉上一选中项后详情必须切到首个可见卡')
    await page.locator('[data-review-filter="UNANALYZED"]').click()
    await waitForCards(page, 1)
    assert.deepEqual(await cardIds(page), ['c-unanalyzed'])

    // 范围到期 / 无分析依据 / 分析异常。
    await page.locator('[data-review-filter="INELIGIBLE"]').click()
    await waitForCards(page, 1)
    assert.deepEqual(await cardIds(page), ['c-ineligible'])
    const expiredCard = await page.locator('[data-review-card="c-ineligible"]').innerText()
    assert.equal(expiredCard.includes('不适用（范围已到期）'), true, '范围到期必须显示在列表')
    assert.equal(expiredCard.includes('建议投'), false, '旧建议不得出现在范围到期卡片')
    const expiredDetail = await page.locator('[data-review-detail]').innerText()
    assert.equal(expiredDetail.includes('不适用（范围已到期）'), true)
    assert.equal(expiredDetail.includes('建议投'), false, '旧建议不得出现在范围到期详情')
    await page.locator('[data-review-filter="ALL"]').click()
    await waitForCards(page, cardsAll.length)
    await page.locator('[data-review-card="c-empty"]').click()
    await page.waitForFunction(() => document.querySelector('[data-review-detail]')?.innerText.includes('暂无分析依据'))
    assert.equal((await page.locator('[data-review-card="c-empty"]').innerText()).includes('暂无分析依据'), true, '无 findings 必须显示在列表')
    await page.locator('[data-review-card="c-exception"]').click()
    await page.waitForFunction(() => document.querySelector('[data-review-detail]')?.innerText.includes('合成分析异常：执行器超时'))
    const exceptionDetail = page.locator('[data-review-detail]')
    await exceptionDetail.getByRole('button', { name: '高级 / 审计', exact: true }).waitFor()
    assertReadOnly(records, '首次进入 / 筛选 / 选岗', { allowState: false })
    assert.equal(records.state.length, 0, '审核视图不得请求 /state')

    // 等待超过原三秒轮询周期：仍没有 /state，也没有其他工作台请求。
    const beforeWait = records.workbench.length
    await delay(3500)
    assert.equal(records.state.length, 0, '等待超过 3 秒不得出现 /state')
    for (const call of records.workbench.slice(beforeWait)) assert.equal(call.path, '/api/workbench/review-cards')

    // 手动刷新也只用 review-cards。
    const beforeRefresh = records.workbench.length
    await page.getByRole('button', { name: '刷新', exact: true }).click()
    await until(() => records.workbench.length > beforeRefresh, 'review-cards refresh')
    assert.equal(records.workbench.at(-1).path, '/api/workbench/review-cards')
    assert.equal(records.state.length, 0, '审核页刷新不得读取 /state')

    // 渠道切换：只有 review-cards；网址、筛选、卡片集合一致。
    await page.locator('select#review-channel').selectOption('OFFERTODAY')
    await waitForCards(page, 2)
    assert.deepEqual(await cardIds(page), ['c-match', 'c-gap'])
    assert.equal(new URL(page.url()).searchParams.get('channel'), 'OFFERTODAY')
    assert.equal(await page.locator('select#review-channel').inputValue(), 'OFFERTODAY')
    await page.getByRole('button', { name: '返回岗位审核（全部渠道）', exact: true }).click()
    await waitForCards(page, cardsAll.length)
    assert.equal(new URL(page.url()).searchParams.has('channel'), false)
    assertReadOnly(records, '刷新 / 渠道切换', { allowState: false })

    // 进入审计：/state 出现，旧任务、人工报告与账本仍可查看。
    const stateBeforeAudit = records.state.length
    await page.getByRole('tab', { name: '高级 / 审计', exact: true }).click()
    await page.getByRole('tab', { name: '手工任务与历史', exact: true }).waitFor()
    await page.getByRole('tab', { name: '手工任务与历史', exact: true }).click()
    await waitForText(page, '任务账本')
    await waitForText(page, '合成岗位申请')
    await waitForText(page, '人工报告 / 核对记录')
    await waitForText(page, 'ledger-1')
    assert.equal(records.state.length > stateBeforeAudit, true, '进入审计后必须读取 /state')
    const stateBeforePoll = records.state.length
    await delay(3500)
    assert.equal(records.state.length > stateBeforePoll, true, '审计视图保留原三秒 /state 轮询')
    await settle(page)
    await page.screenshot({ path: join(screenshots, 'review-cards-audit.png'), fullPage: true })

    // 返回审核并等待一个旧轮询周期：新增 /state 为零。
    const stateAtReview = records.state.length
    await page.getByRole('tab', { name: '岗位审核', exact: true }).click()
    await waitForCards(page, cardsAll.length)
    await delay(3500)
    assert.equal(records.state.length, stateAtReview, '返回审核后不得继续轮询 /state')

    // 异常卡的直达链接同样只在点击后读取 /state。
    await page.locator('[data-review-card="c-exception"]').click()
    const beforeDirect = records.state.length
    await page.locator('[data-review-detail]').getByRole('button', { name: '高级 / 审计', exact: true }).click()
    await page.getByRole('tab', { name: '手工任务与历史', exact: true }).waitFor()
    assert.equal(records.state.length > beforeDirect, true, '点异常链接后必须读取 /state')
    await page.getByRole('tab', { name: '岗位审核', exact: true }).click()
    await waitForCards(page, cardsAll.length)
    const stateAfterDirect = records.state.length
    await delay(3500)
    assert.equal(records.state.length, stateAfterDirect, '再次返回审核后不得继续轮询 /state')

    assertReadOnly(records, '完整审核 + 审计切换', { allowState: true })
    assert.deepEqual(errors, [])
    recordNetwork('success-default-review-audit-channel', records)
    await context.close()
  }

  // 2) 无候选空态：仍显示页头与筛选，不出现样例卡。
  {
    const { context, page, records, errors } = await openPage('empty')
    await page.goto(`${base}/workbench`)
    await waitForText(page, '暂无岗位审核卡片')
    assert.equal(await page.getByRole('heading', { name: '求职工作台', exact: true }).count(), 1)
    assert.equal(await page.locator('[data-review-filter]').count(), 5)
    assert.equal(await page.locator('[data-review-card]').count(), 0)
    assert.equal(await page.locator('[data-review-detail]').count(), 0)
    assert.equal((await page.locator('body').innerText()).includes('Synthetic'), false, '空态不得出现样例数据')
    await settle(page)
    await page.screenshot({ path: join(screenshots, 'review-cards-empty.png'), fullPage: true })
    assertReadOnly(records, '无候选空态')
    assert.deepEqual(errors, [])
    recordNetwork('empty-candidates', records)
    await context.close()
  }

  // 3) 当前筛选无结果：渠道深链有 1 张不适用卡，切到建议投后显示筛选空态且无残留详情。
  {
    const { context, page, records, errors } = await openPage('success')
    await page.goto(`${base}/workbench?channel=JOBSDB`)
    await waitForCards(page, 1)
    assert.deepEqual(await cardIds(page), ['c-ineligible'])
    assert.equal(new URL(page.url()).searchParams.get('channel'), 'JOBSDB')
    assert.equal(await page.locator('select#review-channel').inputValue(), 'JOBSDB')
    await page.locator('[data-review-filter="MATCH"]').click()
    await waitForText(page, '当前筛选没有岗位')
    assert.equal(await page.locator('[data-review-card]').count(), 0)
    assert.equal(await page.locator('[data-review-detail]').count(), 0, '筛选空态不得残留其他岗位详情')
    await settle(page)
    await page.screenshot({ path: join(screenshots, 'review-cards-filter-empty.png'), fullPage: true })
    assertReadOnly(records, '筛选空态')
    assert.deepEqual(errors, [])
    recordNetwork('filter-empty', records)
    await context.close()
  }

  // 4) 接口 503：仍显示页头、筛选和中文错误提示，不出现样例卡或虚构分数。
  {
    const { context, page, records, errors } = await openPage('error')
    await page.goto(`${base}/workbench`)
    const alert = page.locator('[data-testid="review-cards-error"]')
    await alert.waitFor()
    assert.match(await alert.innerText(), /读取岗位审核卡片失败：合成后端不可用（503）/)
    assert.equal(await page.getByRole('heading', { name: '求职工作台', exact: true }).count(), 1)
    assert.equal(await page.locator('[data-review-filter]').count(), 5)
    assert.equal(await page.locator('[data-review-card]').count(), 0)
    assert.equal((await page.locator('body').innerText()).includes('Synthetic'), false, '错误态不得出现样例数据')
    await settle(page)
    await page.screenshot({ path: join(screenshots, 'review-cards-error-503.png'), fullPage: true })
    assertReadOnly(records, '503 错误态')
    assert.deepEqual(errors, [])
    recordNetwork('error-503', records)
    await context.close()
  }

  const networkPath = join(screenshots, 'review-cards-network.json')
  await writeFile(networkPath, JSON.stringify(networkLog, null, 2))
  console.log(JSON.stringify({
    network: networkPath,
    screenshots: [
      join(screenshots, 'review-cards-success.png'),
      join(screenshots, 'review-cards-detail-second.png'),
      join(screenshots, 'review-cards-audit.png'),
      join(screenshots, 'review-cards-empty.png'),
      join(screenshots, 'review-cards-filter-empty.png'),
      join(screenshots, 'review-cards-error-503.png'),
    ],
    checks: 'default review tab / 5 filters / detail switch without request / channel deep link / only GET review-cards in review / /state only after audit or exception link / zero writes / zero external',
  }, null, 2))
} finally {
  if (browser) await browser.close()
  staticServer.close()
}
