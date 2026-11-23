// Opt-in public search only. This child never opens, shares or persists an account profile.
import { readFileSync } from 'node:fs'
import { pathToFileURL } from 'node:url'

const blocked = reason => ({ status: 'blocked', reason })
async function search() {
  const input = JSON.parse(readFileSync(0, 'utf8'))
  const url = new URL(input.sourceUrl)
  if (input.operation !== 'candidate_page' || url.origin !== 'https://www.offertoday.com' ||
      url.username || url.password || url.hash || !/^\/hk\/search(?:\/|$)/.test(url.pathname) ||
      !Number.isInteger(input.readSeconds) || input.readSeconds < 1 || input.readSeconds > 120)
    return blocked('Expected bounded, anonymous OfferToday HK search')
  const deadline = Date.now() + input.readSeconds * 1000
  const remaining = cap => {
    const ms = deadline - Date.now()
    if (ms <= 0) throw Error('Public read deadline')
    return Math.min(ms, cap)
  }
  const { default: { chromium } } = await import(pathToFileURL(process.argv[2]).href)
  const browser = await chromium.launch({ channel: 'chrome', headless: true, timeout: remaining(10000) })
  try {
    const context = await browser.newContext()
    const page = await context.newPage()
    const response = await page.goto(url.href, { waitUntil: 'domcontentloaded', timeout: remaining(10000) })
    if (!response?.ok() || page.url() !== url.href)
      return blocked('OfferToday public search was unavailable or redirected; not retried')
    input.readSeconds = Math.floor(remaining(64000) / 1000)
    if (input.readSeconds < 1) return blocked('OfferToday public search exhausted its time budget')
    input.publicOnly = true
    const driver = readFileSync(new URL('browser.js', import.meta.url), 'utf8')
    return await new Function(`return (${driver.replace('__PAYLOAD__', () => JSON.stringify(input))})`)()(page)
  } finally { await browser.close() }
}
try { console.log(JSON.stringify(await search())) }
catch { console.log(JSON.stringify(blocked('Temporary OfferToday public browser did not return verified results; not retried'))) }
