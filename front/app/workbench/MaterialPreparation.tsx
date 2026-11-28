'use client'

import { useState } from 'react'
import { Button } from '@/components/ui/button'
import { type JobsDbOutcome, type PreparationPackage, type Resume, type ScopeAction, type ScopeDecision, type WorkbenchState, workbenchFile, workbenchJson } from '@/lib/workbench'

const small = 'text-sm text-slate-600 dark:text-slate-300'
const control = 'mt-1 w-full rounded-lg border border-slate-300 bg-white px-3 py-2 text-sm dark:border-slate-600 dark:bg-slate-800'
const statuses = { POLICY_APPROVED: '策略核准 · 未发送', DRAFT_ONLY: '仅草稿 · 不可外发', MATERIAL_EXCEPTION: '材料例外 · 未核准', INVALIDATED: '已失效 · 须按当前范围重新准备' }
type Props = { state: WorkbenchState; selectedIds: string[]; working: boolean; act: (label: string, request: () => Promise<unknown>) => Promise<void> }

export default function MaterialPreparation({ state, selectedIds, working, act }: Props) {
  const [limits, setLimits] = useState({ secondsPerBatch: 30, modelTokensPerCandidate: 1000 })
  const [check, setCheck] = useState<{ id: string; action: ScopeAction; decision: ScopeDecision } | null>(null)
  const [applyFor, setApplyFor] = useState<PreparationPackage | null>(null)
  const [answers, setAnswers] = useState('[]')
  const [answersConfirmed, setAnswersConfirmed] = useState(false)
  const [answerError, setAnswerError] = useState('')
  const [applied, setApplied] = useState<{ packageId: string; status: string; jobId?: string; reason?: string; deliveryOutcome?: JobsDbOutcome; receipt?: { textSha256?: string } } | null>(null)
  const current = state.scope.current
  const disabled = working || state.runtime.busy
  function inspect(id: string, action: ScopeAction) {
    void act('动作前只读核验', async () => setCheck({ id, action, decision: await workbenchJson<ScopeDecision>(`/preparations/${encodeURIComponent(id)}/check`, 'POST', { action }) }))
  }
  function apply(pkg: PreparationPackage) {
    setAnswerError('')
    let parsed: unknown
    try { parsed = JSON.parse(answers) } catch { setAnswerError('请填入已确认题目的 JSON 数组'); return }
    if (!Array.isArray(parsed)) { setAnswerError('每题须包含 name、label、type、options 和 value'); return }
    void act('受控单岗申请', async () => {
      const result = await workbenchJson<{ status: string; jobId?: string; reason?: string; deliveryOutcome?: JobsDbOutcome; receipt?: { textSha256?: string } }>('/jobsdb/apply', 'POST', { scopeVersion: pkg.scopeVersion, candidateId: pkg.candidateId, preparationId: pkg.id, entries: parsed, answersConfirmed })
      setApplied({ packageId: pkg.id, ...result }); setApplyFor(null)
    })
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
    <p className={`${small} mt-2`}>一次准备所选候选，不要求逐岗人审。仅当前有效匹配且无必要核实 / 经验缺口的候选可进入核验。OfferToday / JobsDB 还须在范围中固定并核对实际联系方式，附件必须为可核验 PDF/DOCX（本路径生成 DOCX），OfferToday 使用冻结消息，JobsDB 只提交单岗申请。本入口不会创建申请、claim、发送或把核准当作送达。</p>
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
      {pkg.target.channel === 'JOBSDB' && <p className="mt-2 text-sm text-amber-800 dark:text-amber-200">{state.runtime.jobsDbCapabilityReason} 本包仅准备，不证明线上 Profile 或 CV 字节一致。</p>}
      {pkg.target.channel === 'BOSS' && <p className="mt-2 text-sm text-amber-800 dark:text-amber-200">{state.runtime.bossCapabilityReason} 本包仅用于准备，不证明在线资料或 CV 送达。</p>}
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
        {pkg.target.channel !== 'JOBSDB' && <><p className="mt-3 font-semibold">冻结精确消息（未发送）</p><pre className="mt-1 whitespace-pre-wrap break-words">{pkg.snapshot.initialMessage}</pre>
        <p className={`${small} break-all`}>消息 SHA256 {pkg.snapshot.initialMessageSha256}</p></>}
        {['OFFERTODAY', 'JOBSDB'].includes(pkg.target.channel) && <p className={`${small} mt-2 whitespace-pre-wrap break-words`}>范围内联系方式核对：{pkg.snapshot.contactVerified ? '已核对' : '未核对'} · {pkg.snapshot.contactFacts?.map(fact => `${fact.label} v${fact.version ?? 1}：${fact.value}`).join('；')}</p>}
        <div className="mt-3 flex flex-wrap gap-2"><Button type="button" variant="outline" disabled={disabled} onClick={() => download(pkg.snapshot!.resume)}>下载此独立版本</Button>{(pkg.target.channel === 'OFFERTODAY' ? ['CONTACT', 'SEND_CV'] as const : pkg.target.channel === 'BOSS' ? ['CONTACT'] as const : ['APPLY'] as const).map(action => <Button key={action} type="button" variant="outline" disabled={disabled} onClick={() => inspect(pkg.id, action)}>只核验 {action}（不执行）</Button>)}{pkg.target.channel === 'JOBSDB' && pkg.status === 'POLICY_APPROVED' && <Button type="button" disabled={disabled || !state.runtime.jobsDbSingleJobSupported} onClick={() => { setApplyFor(pkg); setAnswers('[]'); setAnswersConfirmed(false); setAnswerError('') }}>受控单岗申请</Button>}</div>
        {applyFor?.id === pkg.id && <div className="mt-3 rounded-lg border border-amber-300 bg-amber-50 p-3 text-sm dark:border-amber-700 dark:bg-amber-950" aria-label="受控单岗申请确认">
          <p className="font-semibold">只为本岗提交一次，不自动重投</p>
          <p className={`${small} mt-1`}>使用此冻结 DOCX、本范围已核对联系方式与下方答案；遇到未列出的表单问题会停止，不猜答。上传前消耗一次外发额度。上传后问卷不符会停止提交，保留可能已披露的记录。</p>
          <label className={`${small} mt-3 block`}>单岗问卷 JSON 数组（每题填写 name、原题干 label、控件 type、完整 options 与批准答案 value）<textarea className={control} rows={4} value={answers} onChange={e => { setAnswers(e.target.value); setAnswersConfirmed(false) }} spellCheck={false} /></label>
          <label className={`${small} mt-2 flex gap-2`}><input type="checkbox" checked={answersConfirmed} onChange={e => setAnswersConfirmed(e.target.checked)} />我确认这些答案、冻结附件与范围内联系方式仅用于本岗，且我理解未知问题会停止</label>
          {answerError && <p className="mt-2 text-sm text-rose-700 dark:text-rose-300" role="alert">{answerError}</p>}
          <div className="mt-3 flex gap-2"><Button type="button" disabled={disabled || !answersConfirmed} onClick={() => apply(pkg)}>提交一次</Button><Button type="button" variant="outline" disabled={disabled} onClick={() => setApplyFor(null)}>取消</Button></div>
        </div>}
        {applied?.packageId === pkg.id && <div className="mt-3 rounded-lg bg-slate-50 p-3 text-sm dark:bg-slate-800" role="status"><p className="font-semibold">单岗结果：{applied.status}</p>{applied.deliveryOutcome && <p>消息 {applied.deliveryOutcome.messageSent} · CV 实收 {applied.deliveryOutcome.cvActuallyReceived} · 原生占位 {applied.deliveryOutcome.attemptDisposition}</p>}{applied.jobId && <p className={`${small} break-all`}>岗位 {applied.jobId}{applied.receipt?.textSha256 && ` · 回执 SHA256 ${applied.receipt.textSha256}`}</p>}{applied.reason && <p className={`${small} break-words`}>{applied.reason}</p>}</div>}
      </div>}
      {check?.id === pkg.id && <div className="mt-3 rounded-lg bg-slate-50 p-3 text-sm dark:bg-slate-800" role="status"><p>{check.action}：{check.decision.allowed ? '此刻符合条件，仍未授权 / 执行' : '拒绝执行资格'}</p><p>结果不持久有效；实际动作前必须重核。</p><ul>{check.decision.reasons.map((reason, i) => <li key={i} className="whitespace-pre-wrap break-words">{reason}</li>)}</ul></div>}
    </article>)}</div>
    {state.jobsdbAttempts?.length > 0 && <div className="mt-4 rounded-xl border border-slate-200 p-4 dark:border-slate-700"><h3 className="font-semibold">旧 JobsDB 桥接记录（只读）</h3><p className={`${small} mt-1`}>旧记录保留并继续防重；新受控申请请在任务账本查看同一 task/attempt 及事件。UNKNOWN 不自动重投。</p><div className="mt-2 space-y-2">{[...state.jobsdbAttempts].reverse().slice(0, 5).map(attempt => <p key={attempt.id} className={`${small} break-all`}><span className="font-medium">{attempt.jobId}</span> · {attempt.status} · {new Date(attempt.at).toLocaleString('zh-CN')}{attempt.receipt?.textSha256 ? ` · 回执 SHA256 ${attempt.receipt.textSha256}` : ' · 无绑定回执'}{attempt.reason && ` · ${attempt.reason}`}</p>)}</div></div>}
  </section>
}
