// The production browser adapter runs in an isolated Chrome; every request is intercepted.
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { pathToFileURL } from 'node:url'

assert(process.env.PLAYWRIGHT_MODULE, 'Set PLAYWRIGHT_MODULE to the installed Playwright module')
const { default: { chromium } } = await import(pathToFileURL(resolve(process.env.PLAYWRIGHT_MODULE)).href)
const driver = await readFile(new URL('browser.js', import.meta.url), 'utf8')
const site = 'https://www.offertoday.com', sourceUrl = `${site}/hk/search?q=fixture`
const [open, closed, mismatch, preview, firstContact, existingContact] = ['A', 'B', 'C', 'D', 'E', 'F'].map(prefix => `${site}/hk/job/${prefix}bCdEfGhIjKlMnOpQrStUv==`)
const entry = '<script type="module" src="/static/js/index.D4zvvLnm.js"></script>'
const description = 'Responsibilities: implement and test a Python retrieval pipeline. Requirements: hands-on local evaluation, reproducible fixtures and clear documentation.'
const listOf = urls => entry + urls.map(url => `<section><div class="jobCardTitle"><a href="${url}">Fixture engineer</a></div><a href="/hk/company/demo">Fixture co</a></section>`).join('')
const detail = (url, company, isClosed, gate = '', contact = '') => `<head>${entry}<link rel="canonical" href="${url}"></head><body><article>
  <div><div><h1>Fixture engineer</h1></div><h6>${company}·Industry</h6><div>${isClosed ? '<span>已關閉</span>' : '全職'}</div></div>
  <div${contact ? ' id="jobDetailActionButton"' : ''}>${isClosed ? '查看更多優選崗位' : contact || '<div id="apply">傳送投遞消息</div>'}</div>
  <div><h6>工作內容</h6><div>${description}${gate}</div></div></article>
  <script>document.addEventListener('click', () => fetch('/unexpected-effect', {method:'POST'}))</script>
</body>`
const docs = new Map([[sourceUrl, listOf([open, closed, mismatch, preview, firstContact, existingContact])], [open, detail(open, 'Fixture co', false)],
  [closed, detail(closed, 'Fixture co', true)], [mismatch, detail(mismatch, 'Wrong company', false)],
  [preview, detail(preview, 'Fixture co', false, '<p>登入查看完整職位描述</p>')],
  [firstContact, detail(firstContact, 'Fixture co', false, '', '<div><p>傳送訊息</p></div>')],
  [existingContact, detail(existingContact, 'Fixture co', false, '', '<div><button>繼續溝通</button></div>')]])
const unexpected = []
const browser = await chromium.launch({ channel: 'chrome', headless: true })
try {
  const context = await browser.newContext({ serviceWorkers: 'block' })
  await context.route('**/*', route => {
    const { url, method } = { url: route.request().url(), method: route.request().method() }
    if (method === 'GET' && docs.has(url)) return route.fulfill({ contentType: 'text/html; charset=utf-8', body: docs.get(url) })
    if (method === 'GET' && url === `${site}/static/js/index.D4zvvLnm.js`)
      return route.fulfill({ contentType: 'text/javascript', body: '' })
    unexpected.push([method, url]); return route.abort()
  })
  const page = await context.newPage()
  await page.goto(sourceUrl)
  const readPage = input => new Function(`return (${driver.replace('__PAYLOAD__', JSON.stringify({operation: 'candidate_page', sourceUrl, ...input}))})`)()(page)
  const first = await readPage({ maxJobs: 2, cursor: '' })
  assert.equal(first.status, 'ok', JSON.stringify(first))
  assert.match(first.cursor, /^[a-f0-9]{64}:2$/)
  assert.deepEqual(first.jobs.map(j => [j.url, j.description, j.descriptionComplete, j.available]),
    [[open, description, true, true], [closed, description, true, false]])
  const next = await readPage({ maxJobs: 2, cursor: first.cursor })
  assert.match(next.cursor, /^[a-f0-9]{64}:4$/)
  assert.deepEqual(next.jobs.map(j => [j.url, j.description, j.descriptionComplete, j.available]),
    [[mismatch, '', false, null], [preview, description, false, true]])
  const attached = await readPage({ maxJobs: 2, cursor: next.cursor })
  assert.equal(attached.cursor, '')
  assert.deepEqual(attached.jobs.map(j => [j.url, j.description, j.descriptionComplete, j.available]),
    [[firstContact, description, true, true], [existingContact, description, true, true]])
  const guest = await readPage({ maxJobs: 2, cursor: next.cursor, publicOnly: true })
  assert.deepEqual(guest.jobs.map(j => j.available), [null, null], 'Guest reader does not assume signed-in layout')
  docs.set(sourceUrl, listOf([closed, open, mismatch, preview, firstContact, existingContact]))
  await page.reload()
  const changed = await readPage({ maxJobs: 2, cursor: first.cursor })
  assert.equal(changed.status, 'blocked'); assert.match(changed.reason, /list changed/)
  assert.equal(page.url(), sourceUrl)
  assert.deepEqual(context.pages(), [page], 'Only the newly opened detail tab should have been closed')
  assert.deepEqual(unexpected, [], 'No clicks, native RPCs, or foreign requests')
  console.log('Public details: open/closed, mismatched company, gated preview, changed-list checkpoint, tab cleanup, zero side effects.')
} finally { await browser.close() }
