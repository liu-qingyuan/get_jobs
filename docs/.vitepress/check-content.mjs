import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'

const output = new URL('./dist/', import.meta.url)
const page = path => readFile(new URL(path, output), 'utf8')

// Check the built public pages, not Markdown implementation details.
for (const path of ['index.html', 'guide/introduction.html']) {
  const html = await page(path)
  assert.match(html, /href="[^"]*\/platforms(?:\.html)?"/, `${path}: capability overview must be reachable`)
}
const overview = await page('platforms.html')
const rows = [...overview.matchAll(/<tr>([\s\S]*?)<\/tr>/g)].map(match =>
  match[1].replace(/<[^>]*>/g, ' ').replace(/\s+/g, ' ')
)
for (const [channel, required] of [
  ['OFFICIAL', ['可信执行器', '逐站验收', 'UNKNOWN']],
  ['OFFERTODAY', ['实站通过', '保持关闭', 'null']],
  ['BOSS', ['未完整验收', '尚未完整实现']],
  ['JOBSDB', ['未完整验收', '尚未完整实现']]
]) {
  const row = rows.find(text => text.includes(channel))
  assert.ok(row, `Missing capability row: ${channel}`)
  for (const text of required) assert.ok(row.includes(text), `${channel}: missing qualification boundary ${text}`)
}

for (const [name, labels] of [
  ['c4-context', ['本机求职工作台', '公开文档站']],
  ['c4-containers', ['本机主程序', 'SQLite', 'Python', 'Node.js', '可信命令']]
]) {
  const html = await page(`architecture/${name}.html`)
  assert.match(html, new RegExp(`<img[^>]+src="[^"]*/architecture/${name}\\.svg"`), `${name}: rendered diagram must be an image, not just source code`)
  const svg = await page(`architecture/${name}.svg`)
  assert.match(svg, /<svg\b/, `${name}: SVG resource missing`)
  for (const label of labels) assert.ok(svg.includes(label), `${name}: missing visible label ${label}`)
  for (const path of ['index.html', 'guide/introduction.html']) {
    assert.ok((await page(path)).includes(`/architecture/${name}`), `${path}: diagram must be reachable`)
  }
}
console.log('Documentation contract: overview boundaries, entry points and both rendered C4 resources passed')
