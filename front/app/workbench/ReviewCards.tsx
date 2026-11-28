'use client'

import { useMemo } from 'react'
import { Button } from '@/components/ui/button'
import {
  filterReviewCards,
  reviewChannelLabel,
  reviewLanguageLabel,
  reviewPreparationLabel,
  reviewRegionLabel,
  reviewRequirementLabel,
  reviewStatusLabel,
  reviewVerdictLabel,
  REVIEW_FILTERS,
  REVIEW_CHANNEL_LABELS,
  canonicalReviewChannel,
  selectVisibleReviewCard,
  type JobReviewCard,
  type ReviewFilter,
} from '@/lib/review-cards'

const panel = 'rounded-2xl border border-slate-200 bg-white p-5 shadow-sm dark:border-slate-700 dark:bg-slate-900'
const small = 'text-sm text-slate-600 dark:text-slate-300'
const badge = 'inline-block rounded-full px-2.5 py-0.5 text-xs font-semibold'
const statusBadge: Record<string, string> = {
  MATCH: 'bg-emerald-100 text-emerald-800 dark:bg-emerald-950 dark:text-emerald-200',
  NEEDS_FACTS: 'bg-amber-100 text-amber-900 dark:bg-amber-950 dark:text-amber-200',
  EXPERIENCE_GAP: 'bg-amber-100 text-amber-900 dark:bg-amber-950 dark:text-amber-200',
  INELIGIBLE: 'bg-slate-200 text-slate-800 dark:bg-slate-700 dark:text-slate-100',
  NOT_ANALYZED: 'bg-slate-100 text-slate-700 dark:bg-slate-800 dark:text-slate-300',
  ANALYSIS_EXCEPTION: 'bg-rose-100 text-rose-800 dark:bg-rose-950 dark:text-rose-200',
  ANALYSIS_READY: 'bg-indigo-100 text-indigo-800 dark:bg-indigo-950 dark:text-indigo-200',
}
const channelOptions = ['OFFICIAL', 'OFFERTODAY', 'JOBSDB', 'BOSS', 'LIEPIN', 'JOB51', 'ZHILIAN'] as const

type Props = {
  cards: JobReviewCard[]
  loading: boolean
  error: string
  filter: ReviewFilter
  channel: string
  selectedId: string | null
  onFilter: (filter: ReviewFilter) => void
  onChannel: (channel: string) => void
  onSelect: (candidateId: string) => void
  onRefresh: () => void
  onOpenAudit: () => void
}

function date(value: string): string {
  if (!value) return '—'
  const parsed = new Date(value)
  return Number.isNaN(parsed.getTime()) ? value : parsed.toLocaleString('zh-CN')
}

function requirementSummary(card: JobReviewCard): string {
  const first = card.requirements.find(item => item.requirementQuote)
  if (first) return first.requirementQuote
  if (card.status === 'NOT_ANALYZED') return '尚未分析'
  return '暂无要求摘要'
}

function gapSummary(card: JobReviewCard): string {
  const gap = card.requirements.find(item => item.gap)
  if (gap) return gap.gapReason || gap.reason || '存在缺口'
  if (card.status === 'NOT_ANALYZED') return '尚未分析'
  if (card.status === 'INELIGIBLE') return card.reason || '当前不适用'
  if (!card.requirements.length) return card.reason || '暂无分析依据'
  return '暂无缺口'
}

/** 默认“岗位审核”视图：列表、详情、筛选和空态；只读，不创建 task/claim/项目。 */
export default function ReviewCards({
  cards, loading, error, filter, channel, selectedId,
  onFilter, onChannel, onSelect, onRefresh, onOpenAudit,
}: Props) {
  const visible = useMemo(() => filterReviewCards(cards, { filter, channel }), [cards, filter, channel])
  const effectiveSelectedId = selectVisibleReviewCard(visible, selectedId)
  const selected = visible.find(card => card.candidateId === effectiveSelectedId) ?? visible[0]

  return <div className="space-y-4">
    <section className={panel} aria-label="岗位审核筛选">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h2 className="text-lg font-semibold">岗位审核</h2>
          <p className={`${small} mt-1`}>只读岗位卡：一次读取同时给出列表和详情；看卡不创建任务、不加入准备、不调用平台。审计内容在最后的「高级 / 审计」。</p>
        </div>
        <Button variant="outline" size="sm" onClick={onRefresh} disabled={loading}>刷新岗位卡</Button>
      </div>
      <div className="mt-4 flex flex-wrap items-center gap-2" role="group" aria-label="状态筛选">
        {REVIEW_FILTERS.map(item => <button
          key={item.id}
          type="button"
          aria-pressed={filter === item.id}
          data-review-filter={item.id}
          onClick={() => onFilter(item.id)}
          className={`rounded-full border px-3 py-1 text-sm font-medium focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500 ${
            filter === item.id
              ? 'border-indigo-600 bg-indigo-600 text-white'
              : 'border-slate-300 text-slate-700 hover:bg-slate-50 dark:border-slate-600 dark:text-slate-200 dark:hover:bg-slate-800'
          }`}
        >{item.label}</button>)}
      </div>
      <div className="mt-3 flex flex-wrap items-center gap-2">
        <label className={small} htmlFor="review-channel">平台渠道</label>
        <select
          id="review-channel"
          className="rounded-lg border border-slate-300 bg-white px-3 py-1.5 text-sm text-slate-900 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500 dark:border-slate-600 dark:bg-slate-800 dark:text-white"
          value={canonicalReviewChannel(channel)}
          onChange={event => onChannel(event.target.value)}
        >
          <option value="">全部平台</option>
          {channelOptions.map(item => <option key={item} value={item}>{REVIEW_CHANNEL_LABELS[item]}</option>)}
        </select>
        {channel && <button type="button" onClick={() => onChannel('')} className="text-sm text-indigo-700 underline dark:text-indigo-300">返回岗位审核（全部渠道）</button>}
      </div>
    </section>

    {error && <p role="alert" data-testid="review-cards-error" className="rounded-xl border border-rose-300 bg-rose-50 p-3 text-sm text-rose-800 dark:border-rose-800 dark:bg-rose-950 dark:text-rose-200">
      读取岗位审核卡片失败：{error}。未显示样例卡、虚构分数或假匹配；请确认本地后端后刷新。
    </p>}

    {loading && <p role="status" className={small}>正在读取岗位审核卡片…</p>}

    {!loading && !error && cards.length === 0 && <section className={panel} aria-label="岗位审核空态">
      <h2 className="text-lg font-semibold">{channel ? '该渠道暂无候选岗位' : '暂无岗位审核卡片'}</h2>
      <p className={`${small} mt-2`}>{channel
        ? '当前渠道没有真实候选岗位；不会显示样例卡或虚构分数。可返回全部渠道，或到「高级 / 审计」核对范围与发现进展。'
        : '当前没有真实候选岗位；不会显示样例卡或虚构分数。请先在「高级 / 审计」中确认求职范围并完成只读发现。'}</p>
      {channel && <Button variant="outline" size="sm" className="mt-3" onClick={() => onChannel('')}>返回岗位审核（全部渠道）</Button>}
    </section>}

    {!loading && !error && cards.length > 0 && visible.length === 0 && <section className={panel} aria-label="岗位审核筛选空态">
      <h2 className="text-lg font-semibold">当前筛选没有岗位</h2>
      <p className={`${small} mt-2`}>没有卡片符合“{REVIEW_FILTERS.find(item => item.id === filter)?.label ?? '当前'}”筛选；已保留查询顺序和渠道范围，未展示其他岗位详情。</p>
      <Button variant="outline" size="sm" className="mt-3" onClick={() => onFilter('ALL')}>查看全部状态</Button>
    </section>}

    {!loading && !error && visible.length > 0 && <div className="grid items-start gap-4 lg:grid-cols-[minmax(0,1.15fr)_minmax(0,1fr)]">
      <section className={panel} aria-label="岗位卡片列表">
        <div className="grid gap-3 sm:grid-cols-2">
          {visible.map(card => {
            const active = effectiveSelectedId === card.candidateId
            return <button
              key={card.candidateId}
              type="button"
              data-review-card={card.candidateId}
              aria-pressed={active}
              onClick={() => onSelect(card.candidateId)}
              className={`w-full rounded-xl border p-3 text-left focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500 ${
                active
                  ? 'border-indigo-500 bg-indigo-50 dark:border-indigo-500 dark:bg-indigo-950/60'
                  : 'border-slate-200 hover:bg-slate-50 dark:border-slate-700 dark:hover:bg-slate-800'
              }`}
            >
              <span className="block text-xs text-slate-500 dark:text-slate-400">{reviewChannelLabel(card.channel)} · {reviewRegionLabel(card.region)}{reviewLanguageLabel(card.language)}</span>
              <span className="mt-1 block font-semibold">{card.company} · {card.title}</span>
              <span className={`mt-2 ${badge} ${statusBadge[card.verdict === 'MATCH' && card.status !== 'INELIGIBLE' ? 'MATCH' : card.status] ?? 'bg-slate-100 text-slate-700 dark:bg-slate-800 dark:text-slate-300'}`}>{reviewStatusLabel(card)}</span>
              <span className="mt-1 block text-sm">匹配结论：{reviewVerdictLabel(card)}</span>
              <span className="mt-1 block text-sm text-slate-600 line-clamp-2 dark:text-slate-300">主要要求：{requirementSummary(card)}</span>
              <span className="mt-1 block text-sm text-slate-600 line-clamp-2 dark:text-slate-300">缺口：{gapSummary(card)}</span>
              {card.reason && <span className="mt-1 block text-xs font-medium text-amber-800 dark:text-amber-200">{card.reason}</span>}
            </button>
          })}
        </div>
      </section>

      <section className={panel} aria-label="岗位详情" data-review-detail={selected?.candidateId}>
        {selected ? <ReviewDetail card={selected} onOpenAudit={onOpenAudit} /> : <p className={small}>选择岗位查看要求、证据与缺口。</p>}
      </section>
    </div>}
  </div>
}

function ReviewDetail({ card, onOpenAudit }: { card: JobReviewCard; onOpenAudit: () => void }) {
  const gaps = card.requirements.filter(item => item.gap)
  return <div className="space-y-4">
    <div>
      <h2 className="text-xl font-semibold">{card.company} · {card.title}</h2>
      <p className={`${small} mt-1`}>{reviewChannelLabel(card.channel)} · {reviewRegionLabel(card.region)} · {reviewLanguageLabel(card.language)} · 最近出现 {date(card.lastSeenAt)}</p>
      <p className="mt-2 flex flex-wrap items-center gap-2">
        <span className={`${badge} ${statusBadge[card.verdict === 'MATCH' && card.status !== 'INELIGIBLE' ? 'MATCH' : card.status] ?? 'bg-slate-100 text-slate-700 dark:bg-slate-800 dark:text-slate-300'}`}>{reviewStatusLabel(card)}</span>
        <span className="text-sm font-semibold">匹配结论：{reviewVerdictLabel(card)}</span>
        {card.stale && <span className="text-xs text-amber-800 dark:text-amber-200">输入已变化，当前结论待定</span>}
      </p>
      {card.reason && <p className="mt-2 rounded-lg border border-amber-200 bg-amber-50 p-2 text-sm text-amber-900 dark:border-amber-800 dark:bg-amber-950 dark:text-amber-200" data-review-reason>{card.reason}</p>}
      {card.url && /^https?:\/\//i.test(card.url) && <a className="mt-2 inline-block text-sm text-indigo-700 underline dark:text-indigo-300" href={card.url} target="_blank" rel="noopener noreferrer">打开岗位链接 ↗</a>}
    </div>

    <div>
      <h3 className="font-semibold">完整 JD（{card.descriptionComplete ? '完整' : '不完整'}）</h3>
      <pre className="mt-2 max-h-72 overflow-auto whitespace-pre-wrap break-words rounded-lg bg-slate-50 p-3 text-sm dark:bg-slate-800">{card.description || '尚未取得完整岗位描述。'}</pre>
    </div>

    <div>
      <h3 className="font-semibold">要求、理由与证据</h3>
      {card.requirements.length === 0
        ? <p className={`${small} mt-2`}>{card.reason || '暂无分析依据'}</p>
        : <ol className="mt-2 space-y-3">
          {card.requirements.map((requirement, index) => <li key={`${requirement.requirementQuote}-${index}`} className="rounded-lg bg-slate-50 p-3 text-sm dark:bg-slate-800">
            <p className="font-semibold">{reviewRequirementLabel(requirement.kind)}</p>
            <p className="mt-1 whitespace-pre-wrap break-words">岗位原文：“{requirement.requirementQuote}”</p>
            <p className="mt-1 whitespace-pre-wrap break-words">理由：{requirement.reason}</p>
            {requirement.evidence.length > 0 && <ul className="mt-2 space-y-1">
              {requirement.evidence.map((evidence, evidenceIndex) => <li key={`${evidence.id}-${evidenceIndex}`} className={`${small} whitespace-pre-wrap break-words`}>
                依据（{evidence.type === 'RESUME' ? '简历' : '资料'} · {evidence.label || evidence.type}）：“{evidence.quote}”
              </li>)}
            </ul>}
            {requirement.gap && <p className="mt-2 text-sm text-amber-900 dark:text-amber-200">缺口：{requirement.gapReason || requirement.reason}</p>}
            {requirement.suggestion && <p className="mt-2 whitespace-pre-wrap break-words text-sm">建议目标（只读）：{requirement.suggestion.goal}；验收：{requirement.suggestion.acceptance}</p>}
          </li>)}
        </ol>}
    </div>

    <div>
      <h3 className="font-semibold">简历缺口</h3>
      {gaps.length === 0
        ? <p className={`${small} mt-2`}>{card.status === 'INELIGIBLE'
          ? '当前不适用，未计算简历缺口。'
          : card.status === 'NOT_ANALYZED'
            ? '尚未分析。'
            : card.status === 'ANALYSIS_EXCEPTION'
              ? '分析异常，未生成缺口记录。'
              : '暂无缺口记录。'}</p>
        : <ul className="mt-2 list-inside list-disc space-y-1 text-sm">
          {gaps.map((gap, index) => <li key={`${gap.requirementQuote}-${index}`} className="whitespace-pre-wrap break-words">{gap.gapReason || gap.reason}（要求：“{gap.requirementQuote}”）</li>)}
        </ul>}
    </div>

    <dl className="grid gap-2 text-sm sm:grid-cols-2">
      <div className="rounded-lg bg-slate-50 p-3 dark:bg-slate-800"><dt className="font-semibold">准备摘要</dt><dd className="mt-1">{reviewPreparationLabel(card.preparation)}</dd></div>
      <div className="rounded-lg bg-slate-50 p-3 dark:bg-slate-800"><dt className="font-semibold">下一步</dt><dd className="mt-1 whitespace-pre-wrap break-words">{card.nextAction || '暂无建议'}</dd></div>
    </dl>
    <p className={`${small} whitespace-pre-wrap`}>下一步仅为文字；看卡是只读动作，不会创建任务或记录，不会加入准备、生成材料、扣预算、启动执行或发送。</p>
    {card.status === 'ANALYSIS_EXCEPTION' && <Button type="button" variant="outline" size="sm" onClick={onOpenAudit}>高级 / 审计</Button>}
  </div>
}
