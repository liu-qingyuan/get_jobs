/**
 * 岗位审核卡片 UI read model 的纯数据测试。
 *
 * 运行（Node 25 直接剥离类型，无需新增依赖）：
 *   cd front && node --no-warnings test/review-cards.test.mjs
 *
 * 这里只断言筛选、中文标签、渠道组合、顺序保留和选中项收敛；卡面内容与零写请求
 * 由 Java `GET /api/workbench/review-cards` 合同测试与 Playwright 静态导出冒烟证明。
 */
import assert from 'node:assert/strict'
import {
  REVIEW_CHANNEL_LABELS,
  REVIEW_FILTERS,
  REVIEW_REQUIREMENT_LABELS,
  REVIEW_STATUS_LABELS,
  REVIEW_VERDICT_LABELS,
  canonicalReviewChannel,
  filterReviewCards,
  matchesReviewFilter,
  reviewChannelLabel,
  reviewRequirementLabel,
  reviewStatusLabel,
  reviewVerdictLabel,
  selectVisibleReviewCard,
} from '../lib/review-cards.ts'

const passed = []
function check(name, fn) {
  fn()
  passed.push(name)
}

/** 后端 review-cards 合同的合成卡片；测试只读，不进入生产页面。 */
function card(overrides = {}) {
  return {
    candidateId: 'candidate-1',
    channel: 'OFFERTODAY',
    company: 'Synthetic Co',
    title: 'AI engineer',
    url: 'https://example.test/job/1',
    region: 'HK',
    language: 'en',
    lastSeenAt: '2026-11-29T00:00:00Z',
    stale: false,
    description: 'Build and evaluate AI search applications with Python and synthetic data.',
    descriptionComplete: true,
    status: 'ANALYSIS_READY',
    statusLabel: '已分析',
    verdict: 'MATCH',
    verdictLabel: '建议投',
    reason: '',
    nextAction: '暂无建议',
    requirements: [],
    preparation: { status: 'NOT_PREPARED', label: '未准备' },
    ...overrides,
  }
}

check('五个筛选按固定顺序排列：全部 / 建议投 / 需补强 / 不适用 / 未分析', () => {
  assert.deepEqual(REVIEW_FILTERS.map(filter => filter.label), ['全部', '建议投', '需补强', '不适用', '未分析'])
  assert.deepEqual(REVIEW_FILTERS.map(filter => filter.id), ['ALL', 'MATCH', 'STRENGTHEN', 'INELIGIBLE', 'UNANALYZED'])
  assert.equal(new Set(REVIEW_FILTERS.map(filter => filter.id)).size, 5)
})

check('建议投只取 verdict === MATCH；待定、不匹配与不适用都不归入', () => {
  const cards = [
    card({ candidateId: 'match', verdict: 'MATCH', verdictLabel: '建议投' }),
    card({ candidateId: 'undetermined', verdict: 'UNDETERMINED', verdictLabel: '待定' }),
    card({ candidateId: 'mismatch', verdict: 'MISMATCH', verdictLabel: '不匹配' }),
    card({ candidateId: 'strengthen', status: 'EXPERIENCE_GAP', statusLabel: '经验缺口', verdict: 'MISMATCH' }),
    // 后端在范围到期/资格失效时把旧 MATCH 收回为不适用；这里按当前合同形态复现。
    card({ candidateId: 'ineligible', status: 'INELIGIBLE', statusLabel: '不适用', verdict: 'UNDETERMINED', verdictLabel: '待定' }),
  ]
  const suggest = filterReviewCards(cards, { filter: 'MATCH' })
  assert.deepEqual(suggest.map(item => item.candidateId), ['match'])
  assert.equal(matchesReviewFilter(cards[1], 'MATCH'), false, 'UNDETERMINED 不是建议投')
  assert.equal(matchesReviewFilter(cards[4], 'MATCH'), false, '不适用不是建议投')
})

check('需补强同时覆盖待补事实与经验缺口，且不混入建议投', () => {
  const cards = [
    card({ candidateId: 'needs', status: 'NEEDS_FACTS', statusLabel: '待补事实', verdict: 'UNDETERMINED', verdictLabel: '待定' }),
    card({ candidateId: 'gap', status: 'EXPERIENCE_GAP', statusLabel: '经验缺口', verdict: 'MISMATCH', verdictLabel: '不匹配' }),
    card({ candidateId: 'match', verdict: 'MATCH', verdictLabel: '建议投' }),
    card({ candidateId: 'not-analyzed', status: 'NOT_ANALYZED', statusLabel: '未分析', verdict: 'UNDETERMINED', verdictLabel: '待定' }),
  ]
  assert.deepEqual(filterReviewCards(cards, { filter: 'STRENGTHEN' }).map(item => item.candidateId), ['needs', 'gap'])
  assert.equal(matchesReviewFilter(cards[0], 'STRENGTHEN'), true)
  assert.equal(matchesReviewFilter(cards[1], 'STRENGTHEN'), true)
  assert.equal(matchesReviewFilter(cards[2], 'STRENGTHEN'), false)
})

check('不适用与未分析各自只按状态归类', () => {
  const cards = [
    card({ candidateId: 'ineligible', status: 'INELIGIBLE', statusLabel: '不适用', verdict: 'UNDETERMINED', verdictLabel: '待定', reason: '不适用（范围已到期）' }),
    card({ candidateId: 'not-analyzed', status: 'NOT_ANALYZED', statusLabel: '未分析', verdict: 'UNDETERMINED', verdictLabel: '待定' }),
    card({ candidateId: 'exception', status: 'ANALYSIS_EXCEPTION', statusLabel: '分析异常', verdict: 'UNDETERMINED', verdictLabel: '待定' }),
  ]
  assert.deepEqual(filterReviewCards(cards, { filter: 'INELIGIBLE' }).map(item => item.candidateId), ['ineligible'])
  assert.deepEqual(filterReviewCards(cards, { filter: 'UNANALYZED' }).map(item => item.candidateId), ['not-analyzed'])
})

check('全部返回查询顺序，所有筛选都不重排卡片', () => {
  // 故意不按后端分组顺序排列，证明纯模型只过滤、不重新排序。
  const cards = [
    card({ candidateId: 'strengthen', status: 'EXPERIENCE_GAP', statusLabel: '经验缺口', verdict: 'MISMATCH' }),
    card({ candidateId: 'match', verdict: 'MATCH', verdictLabel: '建议投' }),
    card({ candidateId: 'not-analyzed', status: 'NOT_ANALYZED', statusLabel: '未分析', verdict: 'UNDETERMINED', verdictLabel: '待定' }),
    card({ candidateId: 'ineligible', status: 'INELIGIBLE', statusLabel: '不适用', verdict: 'UNDETERMINED', verdictLabel: '待定' }),
    card({ candidateId: 'match-2', verdict: 'MATCH', verdictLabel: '建议投' }),
  ]
  assert.deepEqual(filterReviewCards(cards, { filter: 'ALL' }), cards)
  assert.deepEqual(filterReviewCards(cards, { filter: 'MATCH' }).map(item => item.candidateId), ['match', 'match-2'])
  assert.deepEqual(filterReviewCards(cards, { filter: 'STRENGTHEN' }).map(item => item.candidateId), ['strengthen'])
  assert.deepEqual(cards.map(item => item.candidateId), ['strengthen', 'match', 'not-analyzed', 'ineligible', 'match-2'])
})

check('渠道与状态同时生效，且 51JOB 兼容别名归一到 JOB51', () => {
  const cards = [
    card({ candidateId: 'offer-match', channel: 'OFFERTODAY', verdict: 'MATCH', verdictLabel: '建议投' }),
    card({ candidateId: 'offer-gap', channel: 'OFFERTODAY', status: 'EXPERIENCE_GAP', statusLabel: '经验缺口', verdict: 'MISMATCH' }),
    card({ candidateId: 'boss-gap', channel: 'BOSS', status: 'EXPERIENCE_GAP', statusLabel: '经验缺口', verdict: 'MISMATCH' }),
    card({ candidateId: 'job51-gap', channel: 'JOB51', status: 'NEEDS_FACTS', statusLabel: '待补事实', verdict: 'UNDETERMINED', verdictLabel: '待定' }),
    card({ candidateId: 'offer-ineligible', channel: 'OFFERTODAY', status: 'INELIGIBLE', statusLabel: '不适用', verdict: 'UNDETERMINED', verdictLabel: '待定' }),
  ]
  assert.deepEqual(
    filterReviewCards(cards, { filter: 'STRENGTHEN', channel: 'OFFERTODAY' }).map(item => item.candidateId),
    ['offer-gap'],
  )
  assert.deepEqual(filterReviewCards(cards, { channel: 'OFFERTODAY' }).map(item => item.candidateId), ['offer-match', 'offer-gap', 'offer-ineligible'])
  assert.deepEqual(filterReviewCards(cards, { channel: 'OFFERTODAY', filter: 'INELIGIBLE' }).map(item => item.candidateId), ['offer-ineligible'])
  assert.deepEqual(filterReviewCards(cards, { channel: '51JOB' }).map(item => item.candidateId), ['job51-gap'])
  assert.deepEqual(filterReviewCards(cards, { channel: 'JOB51' }).map(item => item.candidateId), ['job51-gap'])
  assert.equal(canonicalReviewChannel('51job'), 'JOB51')
  assert.equal(canonicalReviewChannel(' offertoday '), 'OFFERTODAY')
  assert.equal(canonicalReviewChannel(''), '')
  assert.equal(canonicalReviewChannel(null), '')
  assert.deepEqual(filterReviewCards(cards, { channel: 'JOBSDB' }), [])
})

check('筛选掉当前选中项后，选中收敛到首个可见卡，不残留其他岗位详情', () => {
  const cards = [
    card({ candidateId: 'match', verdict: 'MATCH', verdictLabel: '建议投' }),
    card({ candidateId: 'gap', status: 'EXPERIENCE_GAP', statusLabel: '经验缺口', verdict: 'MISMATCH' }),
    card({ candidateId: 'match-2', verdict: 'MATCH', verdictLabel: '建议投' }),
  ]
  const strengthen = filterReviewCards(cards, { filter: 'STRENGTHEN' })
  assert.equal(selectVisibleReviewCard(strengthen, 'gap'), 'gap')
  const suggest = filterReviewCards(cards, { filter: 'MATCH' })
  assert.equal(selectVisibleReviewCard(suggest, 'gap'), 'match', '被筛掉的选中项必须让位给首个可见卡')
  assert.equal(selectVisibleReviewCard(suggest, 'match-2'), 'match-2')
  assert.equal(selectVisibleReviewCard([], 'gap'), null, '没有可见卡时不得保留旧详情选中项')
  assert.equal(selectVisibleReviewCard(cards, null), 'match')
})

check('中文状态/结论标签优先使用后端 label，缺失时有中文回退且不泄露英文枚举', () => {
  for (const [status, label] of Object.entries(REVIEW_STATUS_LABELS))
    assert.equal(reviewStatusLabel(card({ status, statusLabel: '' })), label, status)
  for (const [verdict, label] of Object.entries(REVIEW_VERDICT_LABELS))
    assert.equal(reviewVerdictLabel(card({ verdict, verdictLabel: '' })), label, verdict)

  assert.equal(reviewStatusLabel(card({ status: 'NEEDS_FACTS', statusLabel: '待补事实' })), '待补事实')
  assert.equal(reviewStatusLabel(card({ status: 'NEEDS_FACTS', statusLabel: '' })), '待补事实')
  assert.equal(reviewStatusLabel(card({ status: 'ANALYSIS_READY', statusLabel: 'ANALYSIS_READY' })), '已分析', '英文 label 不得进入页面')
  assert.equal(reviewVerdictLabel(card({ verdict: 'MATCH', verdictLabel: 'MATCH' })), '建议投', '英文结论枚举不得进入页面')
  assert.equal(reviewVerdictLabel(card({ verdict: 'MATCH', verdictLabel: '' })), '建议投')
  assert.equal(reviewStatusLabel(card({ status: 'NOT_A_REAL_STATUS', statusLabel: '' })), '状态未知')
  assert.equal(reviewVerdictLabel(card({ verdict: 'NOT_A_REAL_VERDICT', verdictLabel: '' })), '待定')
  assert.equal(reviewRequirementLabel('EXPERIENCE_GAP'), '真实经验不足')
  assert.equal(reviewRequirementLabel('NEEDS_FACTS'), '事实不明')
  assert.equal(reviewRequirementLabel('UNKNOWN_KIND'), '其他要求')
  for (const label of [...Object.values(REVIEW_STATUS_LABELS), ...Object.values(REVIEW_VERDICT_LABELS), ...Object.values(REVIEW_REQUIREMENT_LABELS)])
    assert.match(label, /[\u4e00-\u9fff]/, `标签必须是中文：${label}`)
})

check('平台、地区、语言使用中文字段；未知平台不显示英文枚举', () => {
  assert.equal(reviewChannelLabel('OFFERTODAY'), 'OfferToday HK')
  assert.equal(reviewChannelLabel('JOBSDB'), 'JobsDB')
  assert.equal(reviewChannelLabel('JOB51'), '51job')
  assert.equal(reviewChannelLabel(''), '全部平台')
  assert.equal(reviewChannelLabel('INDEED'), '其他平台')
  assert.equal(REVIEW_CHANNEL_LABELS.BOSS, 'Boss')
})

console.log(`review-cards: ${passed.length} tests passed`)
for (const name of passed) console.log(`  ✓ ${name}`)
