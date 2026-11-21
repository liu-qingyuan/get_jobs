'use client'

import { useState } from 'react'
import { Button } from '@/components/ui/button'
import { type Resume, type ScopeAction, type ScopeDecision, type WorkbenchState, workbenchFile, workbenchJson } from '@/lib/workbench'

const small = 'text-sm text-slate-600 dark:text-slate-300'
const control = 'mt-1 w-full rounded-lg border border-slate-300 bg-white px-3 py-2 text-sm dark:border-slate-600 dark:bg-slate-800'
const statuses = { POLICY_APPROVED: '策略核准 · 未发送', DRAFT_ONLY: '仅草稿 · 不可外发', MATERIAL_EXCEPTION: '材料例外 · 未核准', INVALIDATED: '已失效 · 须按当前范围重新准备' }
type Props = { state: WorkbenchState; selectedIds: string[]; working: boolean; act: (label: string, request: () => Promise<unknown>) => Promise<void> }

export default function MaterialPreparation({ state, selectedIds, working, act }: Props) {
  const [limits, setLimits] = useState({ secondsPerBatch: 30, modelTokensPerCandidate: 1000 })
  const [check, setCheck] = useState<{ id: string; action: ScopeAction; decision: ScopeDecision } | null>(null)
  const current = state.scope.current
  const disabled = working || state.runtime.busy
  function inspect(id: string, action: ScopeAction) {
    void act('动作前只读核验', async () => setCheck({ id, action, decision: await workbenchJson<ScopeDecision>(`/preparations/${encodeURIComponent(id)}/check`, 'POST', { action }) }))
  }
  function download(resume: Resume) {
    void act('下载独立材料', async () => {
      const url = URL.createObjectURL(await workbenchFile(resume.id))
      const link = document.createElement('a'); link.href = url; link.download = resume.filename
      document.body.appendChild(link); link.click(); link.remove(); URL.revokeObjectURL(url)
    })
  }
  return <section className="rounded-2xl border border-slate-200 bg-white p-5 shadow-sm dark:border-slate-700 dark:bg-slate-900" aria-label="自动备料与单岗核准包">
    <h2 className="text-lg font-semibold">自动备料与单岗核准包</h2>
    <p className={`${small} mt-2`}>复用核准基线的可核验原字节，或选择 / 排序适用已确认事实的完整原值，另存独立 DOCX。开放式改写、新数字、商业经历、年限和无依据结论只保留草稿；AI 自述审核通过无效。任意 PDF/DOCX 不能仅凭配套正文自动核准，无法核验时明确例外。原四类基线不覆盖。</p>
    <p className={`${small} mt-2`}>一次准备所选候选，不要求逐岗人审。仅当前有效匹配且无必要核实 / 经验缺口的候选可进入核验。OfferToday 还须在范围中固定并核对实际联系方式，附件必须为可核验 PDF/DOCX（本路径生成 DOCX），消息为下方冻结原文。本入口不会创建申请、claim、发送或把核准当作送达。</p>
    <form className="mt-3 flex flex-wrap items-end gap-3" aria-label="批量准备材料" onSubmit={event => { event.preventDefault(); setCheck(null); void act('批量准备材料', () => workbenchJson('/candidates/prepare', 'POST', { scopeVersion: current?.version, candidateIds: selectedIds, ...limits })) }}>
      <label className={small}>备料批次秒数上限<input className={control} type="number" min="1" max="120" required value={limits.secondsPerBatch} onChange={e => setLimits({ ...limits, secondsPerBatch: Number(e.target.value) })} /></label>
      <label className={small}>每岗备料 token 上限<input className={control} type="number" min="1" max="100000" required value={limits.modelTokensPerCandidate} onChange={e => setLimits({ ...limits, modelTokensPerCandidate: Number(e.target.value) })} /></label>
      <Button type="submit" disabled={disabled || !state.runtime.agentConfigured || !current || current.effectiveStatus !== 'ACTIVE' || !selectedIds.length}>批量准备所选候选（{selectedIds.length}/20）</Button>
    </form>
    <p className={`${small} mt-2`}>每个未命中岗位预扣全部秒数 / token，失败不退款；相同输入与失败跨重启复用，不自动重复模型。事实或文件变化不会续签旧授权；须重新确认范围。动作核验是即时只读解释，不授权、不扣量、不获取 claim；后续执行必须再次核验每个不可逆阶段，不能缓存此处允许响应用于重试。</p>
    {!state.preparations.packages.length && <p className={`${small} mt-3`}>尚无准备结果；先匹配候选，再批量准备。</p>}
    <div className="mt-4 space-y-3">{[...state.preparations.packages].reverse().map(pkg => <article key={pkg.id} className="rounded-xl border border-slate-200 p-4 dark:border-slate-700" aria-label={`材料包 ${pkg.target.company} ${pkg.id}`}>
      <h3 className="font-semibold">{pkg.target.company} · {pkg.target.title}</h3><p className="mt-1 font-semibold">{statuses[pkg.status]}</p>
      <p className={`${small} mt-1 whitespace-pre-wrap break-words`}>{pkg.reason}</p>
      <p className={`${small} mt-2 break-all`}>包 {pkg.id} · 来源 {pkg.source}（策略，不是人审） · 范围 v{pkg.scopeVersion} · {pkg.rulesVersion} · {new Date(pkg.createdAt).toLocaleString('zh-CN')}</p>
      <p className={`${small} break-all`}>{pkg.target.url} · {pkg.target.region}/{pkg.target.language} · 变换 {pkg.target.transformation} · 所选基线 {pkg.target.resumeId} · 匹配依据 {pkg.analysisId || '无'}</p>
      {pkg.proposal && !pkg.snapshot && <details className="mt-3 text-sm"><summary>查看未核准提案（不作为可发送材料）</summary><pre className="mt-2 max-h-80 overflow-auto whitespace-pre-wrap break-words">{JSON.stringify(pkg.proposal, null, 2)}</pre></details>}
      {pkg.snapshot && <div className="mt-3 text-sm">
        <p className="font-semibold">独立材料：{pkg.snapshot.resume.label} · {pkg.snapshot.resume.filename}</p>
        <p className={`${small} break-all`}>版本 ID {pkg.snapshot.resume.id} · 父版本 {pkg.snapshot.resume.parentId || '原基线'} · 附件 SHA256 {pkg.snapshot.resume.sha256} · 正文 SHA256 {pkg.snapshot.resume.contentSha256}</p>
        <details className="mt-2"><summary>查看冻结材料正文与事实依据</summary>
          <pre className="mt-2 max-h-80 overflow-auto whitespace-pre-wrap break-words">{pkg.snapshot.resume.content}</pre>
          {pkg.snapshot.facts.map(fact => <p key={fact.id} className={`${small} mt-2 whitespace-pre-wrap break-words`}>[{fact.id} · v{fact.version ?? 1} · {fact.scope} · {fact.source}] {fact.value}{fact.sourceSha256 && ` · 来源 SHA256 ${fact.sourceSha256}`}</p>)}
        </details>
        <p className="mt-3 font-semibold">冻结精确消息（未发送）</p><pre className="mt-1 whitespace-pre-wrap break-words">{pkg.snapshot.initialMessage}</pre>
        <p className={`${small} break-all`}>消息 SHA256 {pkg.snapshot.initialMessageSha256}</p>
        {pkg.target.channel === 'OFFERTODAY' && <p className={`${small} mt-2 whitespace-pre-wrap break-words`}>范围内联系方式核对：{pkg.snapshot.contactVerified ? '已核对' : '未核对'} · {pkg.snapshot.contactFacts?.map(fact => `${fact.label} v${fact.version ?? 1}：${fact.value}`).join('；')}</p>}
        <div className="mt-3 flex flex-wrap gap-2"><Button type="button" variant="outline" disabled={disabled} onClick={() => download(pkg.snapshot!.resume)}>下载此独立版本</Button>{(pkg.target.channel === 'OFFERTODAY' ? ['CONTACT', 'SEND_CV'] as const : ['APPLY'] as const).map(action => <Button key={action} type="button" variant="outline" disabled={disabled} onClick={() => inspect(pkg.id, action)}>只核验 {action}（不执行）</Button>)}</div>
      </div>}
      {check?.id === pkg.id && <div className="mt-3 rounded-lg bg-slate-50 p-3 text-sm dark:bg-slate-800" role="status"><p>{check.action}：{check.decision.allowed ? '此刻符合条件，仍未授权 / 执行' : '拒绝执行资格'}</p><p>结果不持久有效；实际动作前必须重核。</p><ul>{check.decision.reasons.map((reason, i) => <li key={i} className="whitespace-pre-wrap break-words">{reason}</li>)}</ul></div>}
    </article>)}</div>
  </section>
}
