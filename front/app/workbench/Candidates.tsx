'use client'

import { useState } from 'react'
import { Button } from '@/components/ui/button'
import { type WorkbenchState, workbenchJson } from '@/lib/workbench'

const panel = 'rounded-2xl border border-slate-200 bg-white p-5 shadow-sm dark:border-slate-700 dark:bg-slate-900'
const control = 'mt-1 w-full rounded-lg border border-slate-300 bg-white px-3 py-2 text-sm dark:border-slate-600 dark:bg-slate-800'
const small = 'text-sm text-slate-600 dark:text-slate-300'
const statuses = { READY: '完整 JD · 待匹配', INCOMPLETE: '描述不足 · 不参与匹配', EXPIRED: '岗位已失效', REFUSED: '范围拒绝' }
const runStatuses = { DISCOVERING: '正在只读发现', CHECKPOINT: '读取断点 · 待继续或处理例外', COMPLETED: '本轮来源已读完' }
const lines = (value: string) => value.split('\n').map(line => line.trim()).filter(Boolean)
type Props = { state: WorkbenchState; working: boolean; act: (label: string, request: () => Promise<unknown>) => Promise<void>; showScope: () => void }

export default function Candidates({ state, working, act, showScope }: Props) {
  const current = state.scope.current
  function sources() {
    return {
      official: current?.channels.includes('OFFICIAL') ? current.hosts.filter(host => host !== 'www.offertoday.com').map(host => `https://${host}`).join('\n') : '',
      offer: current?.channels.includes('OFFERTODAY') && current.hosts.includes('www.offertoday.com') ? 'https://www.offertoday.com/hk/recommend' : '',
    }
  }
  const [entry, setEntry] = useState(sources)
  const [region, setRegion] = useState(current?.regions[0] ?? 'HK')
  const [language, setLanguage] = useState(current?.languages[0] ?? 'en')
  const [direction, setDirection] = useState(current?.directions[0] ?? 'AI_APPLICATION')
  const [limits, setLimits] = useState({ maxPages: 2, jobsPerPage: 5, secondsPerPage: 30, modelTokensPerPage: 1000 })
  const workspace = state.candidates
  const disabled = working || state.runtime.busy || !current || current.effectiveStatus !== 'ACTIVE'
  function discover(runId?: string) {
    void act(runId ? '继续只读发现' : '启动只读发现', () => workbenchJson('/candidates/discover', 'POST', {
      ...limits, scopeVersion: current?.version, ...(runId ? { runId } : { sources: [
        ...lines(entry.official).map(url => ({ channel: 'OFFICIAL', url, region, language, direction })),
        ...lines(entry.offer).map(url => ({ channel: 'OFFERTODAY', url, region, language, direction })),
      ] }),
    }))
  }
  return <div className="space-y-4">
    <section className={panel} aria-label="只读候选发现">
      <div className="flex flex-wrap items-center justify-between gap-2"><h2 className="text-lg font-semibold">AI 候选队列 · 一次启动只读发现</h2><Button variant="outline" onClick={showScope}>设置 / 查看求职范围</Button></div>
      <p className={`${small} mt-2`}>在当前范围内读列表并尝试取得完整 JD，自动持久去重。不创建申请、不批准、不联系、不上传简历。网页指令只是数据，不能扩权。候选尚待匹配；技术链路通过不等于真实来源覆盖或推荐质量已验收。</p>
      {!current && <p className="mt-3 text-sm text-amber-800 dark:text-amber-200">请先一次确认求职范围；默认不授权读取或发送。</p>}
      {current && <p className={`${small} mt-3`}>范围 v{current.version} · {current.effectiveStatus} · 已耗候选槽 {state.scope.usage.candidates}/{current.budgets.candidates} · 秒 {state.scope.usage.seconds}/{current.budgets.seconds} · 模型 token {state.scope.usage.modelTokens}/{current.budgets.modelTokens}</p>}
      <form className="mt-4 grid gap-3 md:grid-cols-2" onSubmit={event => { event.preventDefault(); discover() }}>
        <label className={small}>官网只读入口（每行一个）<textarea className={control} value={entry.official} onChange={e => setEntry({ ...entry, official: e.target.value })} placeholder="https://careers.example.com/jobs" /></label>
        <label className={small}>OfferToday 只读列表（每行一个）<textarea className={control} value={entry.offer} onChange={e => setEntry({ ...entry, offer: e.target.value })} placeholder="https://www.offertoday.com/hk/recommend" /></label>
        <div className="flex flex-wrap gap-2 md:col-span-2"><Button type="button" variant="outline" disabled={working || !current} onClick={() => { setEntry(sources()); setRegion(current?.regions[0] ?? 'HK'); setLanguage(current?.languages[0] ?? 'en'); setDirection(current?.directions[0] ?? 'AI_APPLICATION') }}>载入当前范围来源</Button><p className={small}>官网复用已配置通用只读执行器，不造公司驱动；OfferToday 仅复用已授权现有会话，不新建登录态。列表入口一次提供，不需逐岗抄 JD。</p></div>
        <label className={small}>本次地区<select className={control} value={region} onChange={e => setRegion(e.target.value)}><option value="HK">香港</option><option value="MAINLAND">内地</option></select></label>
        <label className={small}>本次语言<select className={control} value={language} onChange={e => setLanguage(e.target.value)}><option value="en">英文</option><option value="zh">中文</option></select></label>
        <label className={small}>本次方向<select className={control} value={direction} onChange={e => setDirection(e.target.value)}><option value="AI_APPLICATION">AI 应用</option><option value="ML">机器学习</option><option value="EMBODIED">具身智能</option></select></label>
        {([['maxPages', '本次最多页面', 20], ['jobsPerPage', '每页候选槽位', 40], ['secondsPerPage', '每页秒数上限', 120], ['modelTokensPerPage', '官网每页模型 token 上限', 100000]] as const).map(([key, label, max]) => <label key={key} className={small}>{label}<input className={control} type="number" min="1" max={max} required value={limits[key]} onChange={e => setLimits({ ...limits, [key]: Number(e.target.value) })} /></label>)}
        <p className={`${small} md:col-span-2`}>每页包含必要详情，开始前预扣整页候选槽、秒数及官网模型上限（OfferToday 零模型），失败/重复不退款。页面上限只限制本次推进；续读保留游标，仍重核当前范围及累计预算，重启不清零。OfferToday 不猜测尚未加载的翻页接口，连接/版本或详情不足均明确列为例外。</p>
        <div><Button type="submit" disabled={disabled}>一次启动范围内只读发现</Button></div>
      </form>
    </section>
    <section className={panel} aria-label="候选岗位队列">
      <h2 className="text-lg font-semibold">候选岗位（{workspace.jobs.length}）</h2>
      {!workspace.jobs.length && <p className={`${small} mt-2`}>尚无候选。先设置范围并启动只读发现；不会展示虚构岗位或成功回执。</p>}
      <div className="mt-3 space-y-3">{[...workspace.jobs].sort((a, b) => b.retrievedAt.localeCompare(a.retrievedAt)).map(job => <article key={job.id} className="rounded-xl border border-slate-200 p-4 dark:border-slate-700">
        <h3 className="font-semibold">{job.company} · {job.title}</h3><p className="mt-1 text-sm font-semibold text-indigo-700 dark:text-indigo-300">{statuses[job.status]}</p>
        <p className={`${small} mt-1`}>{job.channel === 'OFFERTODAY' ? 'OfferToday HK' : '公司官网'} · {job.region}/{job.language} · {job.direction} · 范围 v{job.scopeVersion}</p>
        <a href={job.url} target="_blank" rel="noopener noreferrer" className="mt-2 block break-all text-sm text-indigo-700 underline dark:text-indigo-300">{job.url}</a>
        {job.platformJobId && <p className={`${small} break-all`}>平台身份：{job.platformJobId}</p>}
        <p className={`${small} mt-1 break-all`}>读取来源：{job.sourceUrl} · 取得时间：{new Date(job.retrievedAt).toLocaleString('zh-CN')} · 合并发现 {job.discoveries} 次</p>
        {job.descriptionComplete && !job.lastReadComplete && <p className={`${small} mt-2`}>最近发现 {new Date(job.lastSeenAt).toLocaleString('zh-CN')} 未取得新全文；保留上次完整 JD 与原取得时间，已按当前范围重核，不冒充本次更新。</p>}
        {job.lastReadDescription && <p className={`${small} mt-2 whitespace-pre-wrap break-words`}>本次卡片 / 摘要（也参与排除条件核验）：{job.lastReadDescription}</p>}
        <p className="mt-3 whitespace-pre-wrap break-words text-sm">{job.description || '未取得完整描述；不会用岗位标题冒充 JD。'}</p>
        {job.status !== 'READY' && <ul className="mt-2 list-inside list-disc text-sm text-amber-800 dark:text-amber-200">{job.reasons.map((reason, i) => <li key={i}>{reason}</li>)}</ul>}
      </article>)}</div>
    </section>
    <section className={panel} aria-label="读取进展与例外">
      <h2 className="text-lg font-semibold">读取进展、预算与断点</h2>
      {[...workspace.runs].reverse().map(run => <div key={run.id} className="mt-3 rounded-xl border border-slate-200 p-3 dark:border-slate-700">
        <p className="font-semibold">{runStatuses[run.status]} · 已读 {run.pagesRead} 页 · 范围 v{run.scopeVersion}</p>
        <p className={`${small} break-all`}>轮次 {run.id}</p>
        {run.sources.map((source, i) => <p key={i} className={`${small} mt-1 break-all`}>{source.channel} · {source.url} · {source.done ? '本来源读取结束' : `保留断点 ${source.cursor || source.nextUrl}`}</p>)}
        {run.status === 'CHECKPOINT' && <Button type="button" variant="outline" className="mt-2" disabled={disabled} onClick={() => discover(run.id)}>按当前范围继续只读发现</Button>}
        <ol className="mt-2 max-h-64 space-y-1 overflow-y-auto text-sm">{run.events.map((event, i) => <li key={i} className="break-words">{new Date(event.at).toLocaleTimeString('zh-CN')} · {event.channel} · {event.code} · {event.message}</li>)}</ol>
      </div>)}
    </section>
  </div>
}
