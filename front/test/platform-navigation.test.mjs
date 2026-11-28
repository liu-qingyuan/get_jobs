/**
 * 平台导航 UI read model 的纯数据测试。
 *
 * 运行（Node 25 直接剥离类型，无需新增依赖）：
 *   cd front && node --no-warnings test/platform-navigation.test.mjs
 *
 * 这里只断言展示行、链接、能力和限制；请求拒绝与零动作计数由 Java 测试证明。
 * 导航状态不是授权或执行输入，改这里只会改变显示。
 */
import assert from 'node:assert/strict'
import {
  NAVIGATION_STATUS_LABEL,
  OFFERTODAY_DISPATCH_PROOF_BY_BUILD,
  platformNavigation,
} from '../lib/platform-navigation.ts'

const passed = []
function check(name, fn) {
  fn()
  passed.push(name)
}
function row(rows, id) {
  const found = rows.filter(item => item.id === id)
  assert.equal(found.length, 1, `${id} 必须且只能出现一次`)
  return found[0]
}

check('七个条目按名称逐行各一次，六平台之外另有公司官网', () => {
  const rows = platformNavigation()
  assert.deepEqual(rows.map(item => item.name), ['Boss', 'Liepin', '51job', 'Zhilian', 'OfferToday', 'JobsDB', '公司官网'])
  assert.equal(rows.length, 7)
  for (const name of ['Boss', 'Liepin', '51job', 'Zhilian', 'OfferToday', 'JobsDB', '公司官网'])
    assert.equal(rows.filter(item => item.name === name).length, 1, `${name} 只能出现一次`)
  assert.deepEqual(new Set(rows.map(item => item.id)).size, 7)
})

check('每行只暴露展示字段，没有授权/执行函数', () => {
  const rows = platformNavigation()
  for (const item of rows) {
    assert.deepEqual(Object.keys(item).sort(), ['capabilities', 'id', 'limits', 'links', 'name', 'runtimeNotes', 'summary'])
    for (const key of ['authorize', 'execute', 'dispatch', 'claim', 'send', 'run'])
      assert.equal(key in item, false, `${item.id} 不得暴露 ${key}`)
    for (const link of item.links)
      assert.equal(link.href.startsWith('/'), true, `${item.id} 的链接必须是站内入口，避免查看导航触发外发`)
  }
})

check('Boss 一行同时提供 /boss 旧入口和 /workbench 工作台链接', () => {
  const boss = row(platformNavigation(), 'boss')
  assert.deepEqual(boss.links.map(link => link.href), ['/boss', '/workbench'])
  assert.deepEqual(boss.links.map(link => link.label), ['旧审核入口', '工作台'])
  const cv = boss.capabilities.find(item => item.id === 'cv-delivery')
  assert.equal(cv.status, 'unsupported')
  assert.match(cv.detail, /CV 未尝试/)
})

check('没有运行时数据时 Boss 明确未知/未连接，不冒充可用', () => {
  const capability = row(platformNavigation(), 'boss').capabilities.find(item => item.id === 'controlled-first-contact')
  assert.equal(capability.status, 'unknown')
  assert.match(capability.detail, /未连接/)
  assert.notEqual(capability.status, 'available')
  assert.equal(NAVIGATION_STATUS_LABEL.unknown, '未知（未连接）')
})

check('Boss 受控首击严格跟随 runtime.bossOutboundSupported 与 bossCapabilityReason', () => {
  const unavailable = row(platformNavigation({ bossOutboundSupported: false, bossCapabilityReason: '待核实-测试原因' }), 'boss')
    .capabilities.find(item => item.id === 'controlled-first-contact')
  assert.equal(unavailable.status, 'blocked')
  assert.match(unavailable.detail, /待核实-测试原因/)

  const available = row(platformNavigation({ bossOutboundSupported: true, bossCapabilityReason: '受控首击-测试原因' }), 'boss')
    .capabilities.find(item => item.id === 'controlled-first-contact')
  assert.equal(available.status, 'limited')
  assert.match(available.detail, /受控首击-测试原因/)
  assert.notEqual(available.status, 'verified')
})

check('Liepin、51job、Zhilian 各有旧入口且受控单目标为 unsupported', () => {
  const rows = platformNavigation()
  for (const [id, name, href] of [
    ['liepin', 'Liepin', '/liepin'],
    ['job51', '51job', '/51job'],
    ['zhilian', 'Zhilian', '/zhilian'],
  ]) {
    const item = row(rows, id)
    assert.equal(item.name, name)
    assert.deepEqual(item.links.map(link => link.href), [href])
    const capability = item.capabilities.find(entry => entry.id === 'controlled-single-target')
    assert.equal(capability.status, 'unsupported')
    assert.match(capability.detail, /旧 batch Worker/)
  }
})

check('JobsDB 文案说明 apply-single、冻结材料/授权/占位及单岗实投边界', () => {
  const jobsdb = row(platformNavigation({ jobsDbSingleJobSupported: true }), 'jobsdb')
  const copy = [jobsdb.summary, ...jobsdb.limits, ...jobsdb.capabilities.map(item => item.detail)].join('\n')
  for (const required of [
    'POST /api/workbench/jobsdb/apply',
    'apply-single',
    'applications.db',
    '冻结材料',
    '有效授权',
    '占位',
    '单岗 CLI 实投只证明该次提交',
    '批次',
    'CV',
    '送达凭据',
  ])
    assert.equal(copy.includes(required), true, `JobsDB 文案缺少：${required}`)
  assert.deepEqual(jobsdb.links.map(link => link.href), ['/workbench'])
  assert.equal(jobsdb.capabilities.find(item => item.id === 'batch-outbound').status, 'blocked')
  assert.equal(jobsdb.capabilities.find(item => item.id === 'cv-evidence').status, 'unknown')
})

check('OfferToday 两个 dispatchProofByBuild 均为空，生产自动发送 blocked', () => {
  assert.equal(OFFERTODAY_DISPATCH_PROOF_BY_BUILD.legacy, null)
  assert.equal(OFFERTODAY_DISPATCH_PROOF_BY_BUILD.current, null)

  const offerToday = row(platformNavigation(), 'offertoday')
  const legacy = offerToday.capabilities.find(item => item.id === 'dispatch-proof-legacy')
  const current = offerToday.capabilities.find(item => item.id === 'dispatch-proof-current')
  for (const [capability, label] of [[legacy, 'legacy'], [current, 'current']]) {
    assert.equal(capability.status, 'blocked')
    assert.equal(capability.detail.includes('空（null）'), true, `${label} 必须显示空绑定`)
    assert.equal(capability.detail.includes('blocked'), true)
  }
  assert.equal(offerToday.capabilities.find(item => item.id === 'production-auto-send').status, 'blocked')
  const delivery = offerToday.capabilities.find(item => item.id === 'verified-cv-delivery')
  assert.equal(delivery.status, 'verified')
  assert.match(delivery.detail, /送達/)
  assert.match(delivery.detail, /招聘者回复并邀请发简历/)
  assert.match(delivery.detail, /单次授权|一次授权/)
})

check('OfferToday 公开读取严格跟随 runtime.offerTodayConfigured，未连接不冒充可用', () => {
  const unconnected = row(platformNavigation(), 'offertoday').capabilities.find(item => item.id === 'public-job-read')
  assert.equal(unconnected.status, 'unknown')
  assert.match(unconnected.detail, /未连接/)

  const configured = row(platformNavigation({ offerTodayConfigured: true }), 'offertoday')
    .capabilities.find(item => item.id === 'public-job-read')
  assert.equal(configured.status, 'available')

  const unconfigured = row(platformNavigation({ offerTodayConfigured: false }), 'offertoday')
    .capabilities.find(item => item.id === 'public-job-read')
  assert.equal(unconfigured.status, 'blocked')
  assert.match(unconfigured.detail, /脚本未配置/)
})

check('OfferToday 未读取到 platforms 与后端未提供入口 URL 分开表述', () => {
  const notRead = row(platformNavigation({ offerTodayConfigured: true }), 'offertoday')
  assert.match(notRead.runtimeNotes.join(' '), /未读取到运行时入口 URL/)
  const notProvided = row(platformNavigation({ offerTodayConfigured: true }, {}), 'offertoday')
  assert.match(notProvided.runtimeNotes.join(' '), /后端未提供 OfferToday 入口 URL/)
})

check('公司官网只展示运行时执行器配置', () => {
  const unknown = row(platformNavigation(), 'company-site')
  assert.equal(unknown.capabilities.find(item => item.id === 'generic-executor').status, 'unknown')
  assert.match(unknown.capabilities.find(item => item.id === 'generic-executor').detail, /未连接/)

  const configured = row(platformNavigation({ agentConfigured: true }), 'company-site')
  assert.equal(configured.capabilities.find(item => item.id === 'generic-executor').status, 'available')
  assert.deepEqual(configured.links.map(link => link.href), ['/workbench'])
})

check('纯函数：同一运行时快照稳定，修改返回行不污染下一次展示', () => {
  const runtime = { bossOutboundSupported: true, jobsDbSingleJobSupported: true, agentConfigured: true }
  const first = platformNavigation(runtime)
  const second = platformNavigation(runtime)
  assert.deepEqual(first, second)

  first[0].capabilities[0].status = 'available'
  first[0].name = 'tampered'
  const third = platformNavigation(runtime)
  assert.equal(third[0].name, 'Boss')
  assert.equal(third[0].capabilities[0].status, 'limited')
})

check('运行时/平台提示只读展示，不生成外部链接或执行入口', () => {
  const rows = platformNavigation(
    { bossOutboundSupported: false, jobsDbSingleJobSupported: true, agentConfigured: true, offerTodayConfigured: true },
    { bossUrl: 'https://www.zhipin.com/web/geek/job', offerTodayUrl: 'https://www.offertoday.com/hk/recommend', jobsdbAvailable: true },
  )
  for (const item of rows) {
    for (const link of item.links) assert.equal(link.href.startsWith('/'), true)
    for (const note of item.runtimeNotes) assert.equal(typeof note, 'string')
  }
  assert.equal(row(rows, 'boss').runtimeNotes.join(' ').includes('zhipin.com'), true)
  assert.equal(row(rows, 'offertoday').runtimeNotes.join(' ').includes('offertoday.com'), true)
  assert.equal(row(rows, 'jobsdb').runtimeNotes.join(' ').includes('存在'), true)
})

console.log(`platform-navigation: ${passed.length} tests passed`)
for (const name of passed) console.log(`  ✓ ${name}`)
