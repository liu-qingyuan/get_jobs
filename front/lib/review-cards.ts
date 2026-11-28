/**
 * 岗位审核卡片 UI read model。
 *
 * 本模块只拥有“哪些卡可见、中文怎么显示、选中哪张”：它不发请求、不排序、
 * 不创建 task/claim、不授权执行，也不改变后端 `GET /api/workbench/review-cards`
 * 的合同。页面按后端返回顺序展示，渠道过滤只缩小集合。
 *
 * 保持零依赖、纯函数，便于 Node 直接执行测试（Node 25 类型剥离）。
 */

export type ReviewFilter = 'ALL' | 'MATCH' | 'STRENGTHEN' | 'INELIGIBLE' | 'UNANALYZED'
export type ReviewRequirementKind =
  | 'SUPPORTED'
  | 'PARTIAL'
  | 'NEEDS_FACTS'
  | 'EXPERIENCE_GAP'
  | 'HARD_REQUIREMENT'
  | 'NOT_APPLICABLE'

export interface ReviewEvidence {
  type: 'FACT' | 'RESUME' | string
  id: string
  quote: string
  label: string
}

export interface ReviewSuggestion {
  goal: string
  acceptance: string
}

export interface ReviewRequirement {
  kind: ReviewRequirementKind | string
  requirementQuote: string
  reason: string
  gap: boolean
  gapReason: string
  evidence: ReviewEvidence[]
  suggestion?: ReviewSuggestion
}

export interface ReviewPreparation {
  status: string
  label: string
}

/** `GET /api/workbench/review-cards` 的单张卡；详情全部来自同一次响应。 */
export interface JobReviewCard {
  candidateId: string
  channel: string
  company: string
  title: string
  url: string
  region: string
  language: string
  lastSeenAt: string
  stale: boolean
  description: string
  descriptionComplete: boolean
  status: string
  statusLabel: string
  verdict: string
  verdictLabel: string
  reason: string
  nextAction: string
  requirements: ReviewRequirement[]
  preparation: ReviewPreparation
}

export const REVIEW_FILTERS: ReadonlyArray<{ id: ReviewFilter; label: string }> = [
  { id: 'ALL', label: '全部' },
  { id: 'MATCH', label: '建议投' },
  { id: 'STRENGTHEN', label: '需补强' },
  { id: 'INELIGIBLE', label: '不适用' },
  { id: 'UNANALYZED', label: '未分析' },
]

/** 后端 label 缺失时的中文回退；内部枚举不进入页面文案。 */
export const REVIEW_STATUS_LABELS: Record<string, string> = {
  INELIGIBLE: '不适用',
  MISMATCH: '不匹配',
  UNDETERMINED: '待定',
  MATCH: '建议投',
  ANALYSIS_READY: '已分析',
  NEEDS_FACTS: '待补事实',
  EXPERIENCE_GAP: '经验缺口',
  NOT_ANALYZED: '未分析',
  ANALYSIS_EXCEPTION: '分析异常',
}

export const REVIEW_VERDICT_LABELS: Record<string, string> = {
  MATCH: '建议投',
  MISMATCH: '不匹配',
  UNDETERMINED: '待定',
}

export const REVIEW_REQUIREMENT_LABELS: Record<string, string> = {
  SUPPORTED: '直接支持',
  PARTIAL: '可迁移 · 部分支持',
  NEEDS_FACTS: '事实不明',
  EXPERIENCE_GAP: '真实经验不足',
  HARD_REQUIREMENT: '硬性条件不足',
  NOT_APPLICABLE: '地区 / 语言不适用',
}

export const REVIEW_CHANNEL_LABELS: Record<string, string> = {
  OFFICIAL: '公司官网',
  BOSS: 'Boss',
  LIEPIN: 'Liepin',
  JOB51: '51job',
  ZHILIAN: 'Zhilian',
  OFFERTODAY: 'OfferToday HK',
  JOBSDB: 'JobsDB',
}

export const REVIEW_REGION_LABELS: Record<string, string> = { HK: '香港', MAINLAND: '内地' }
export const REVIEW_LANGUAGE_LABELS: Record<string, string> = { zh: '中文', en: '英文' }
export const REVIEW_PREPARATION_LABELS: Record<string, string> = {
  PREPARED: '已准备',
  NOT_PREPARED: '未准备',
  MATERIAL_EXCEPTION: '材料异常',
  DERIVED_STALE: '派生过期',
}

const CHINESE = /[\u4e00-\u9fff]/

/** 后端 label 只有中文才采用；未知或英文枚举回退到固定中文表。 */
function chineseOr(value: string | undefined, fallback: string | undefined): string {
  const provided = (value ?? '').trim()
  if (provided && CHINESE.test(provided)) return provided
  return fallback || ''
}

export function reviewStatusLabel(card: Pick<JobReviewCard, 'status' | 'statusLabel'>): string {
  return chineseOr(card.statusLabel, REVIEW_STATUS_LABELS[card.status]) || '状态未知'
}

export function reviewVerdictLabel(card: Pick<JobReviewCard, 'verdict' | 'verdictLabel'>): string {
  return chineseOr(card.verdictLabel, REVIEW_VERDICT_LABELS[card.verdict]) || '待定'
}

export function reviewRequirementLabel(kind: string): string {
  return REVIEW_REQUIREMENT_LABELS[kind] || '其他要求'
}

export function reviewChannelLabel(channel: string): string {
  const canonical = canonicalReviewChannel(channel)
  if (!canonical) return '全部平台'
  return REVIEW_CHANNEL_LABELS[canonical] || '其他平台'
}

export function reviewRegionLabel(region: string): string {
  return REVIEW_REGION_LABELS[region] || '未知地区'
}

export function reviewLanguageLabel(language: string): string {
  return REVIEW_LANGUAGE_LABELS[language] || '未知语言'
}

export function reviewPreparationLabel(preparation: Pick<ReviewPreparation, 'status' | 'label'> | undefined): string {
  return chineseOr(preparation?.label, preparation ? REVIEW_PREPARATION_LABELS[preparation.status] : undefined) || '未准备'
}

/** 51JOB 是后端接受的旧别名；canonical 显示与比较都用 JOB51。 */
export function canonicalReviewChannel(channel?: string | null): string {
  const value = (channel ?? '').trim().toUpperCase()
  return value === '51JOB' ? 'JOB51' : value
}

export function matchesReviewFilter(card: Pick<JobReviewCard, 'status' | 'verdict'>, filter: ReviewFilter): boolean {
  switch (filter) {
    case 'MATCH':
      return card.verdict === 'MATCH'
    case 'STRENGTHEN':
      return card.status === 'NEEDS_FACTS' || card.status === 'EXPERIENCE_GAP'
    case 'INELIGIBLE':
      return card.status === 'INELIGIBLE'
    case 'UNANALYZED':
      return card.status === 'NOT_ANALYZED'
    default:
      return true
  }
}

export interface ReviewFilterInput {
  filter?: ReviewFilter
  channel?: string | null
}

/**
 * 渠道 + 状态组合过滤。保持后端返回顺序，不排序、不去重、不改写卡片内容。
 */
export function filterReviewCards(
  cards: readonly JobReviewCard[],
  input: ReviewFilterInput = {},
): JobReviewCard[] {
  const filter = input.filter ?? 'ALL'
  const channel = canonicalReviewChannel(input.channel)
  return cards.filter(card =>
    matchesReviewFilter(card, filter) &&
    (!channel || canonicalReviewChannel(card.channel) === channel),
  )
}

/**
 * 当前选中项被筛掉时收敛到首个可见卡；没有可见卡则返回 null。
 * 页面据此渲染详情，避免筛掉后残留其他岗位。
 */
export function selectVisibleReviewCard(
  cards: readonly Pick<JobReviewCard, 'candidateId'>[],
  selectedId: string | null | undefined,
): string | null {
  if (selectedId && cards.some(card => card.candidateId === selectedId)) return selectedId
  return cards[0]?.candidateId ?? null
}
