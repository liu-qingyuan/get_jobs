'use client'

import { useRef, useState } from 'react'
import { Button } from '@/components/ui/button'
import { type Fact, type MaterialCandidate, type MaterialReview, type WorkbenchState, workbenchGet, workbenchJson } from '@/lib/workbench'

const control = 'w-full rounded-lg border border-slate-300 bg-white px-3 py-2 text-sm dark:border-slate-600 dark:bg-slate-800'
const small = 'text-sm text-slate-600 dark:text-slate-300'
const statusName: Record<MaterialCandidate['status'], string> = {
  NEEDS_REVIEW: '待核实', CHANGED: '来源已变，待核实差异', REUSED: '已确认，直接复用',
  DUPLICATE: '重复证据，不必重复确认', CONFLICT: '矛盾，保持待核实', UNVERIFIED: '缺少可核对证据',
}
const scopeName: Record<string, string> = { COMMON: '通用', HK: '香港', MAINLAND: '内地' }
type SourceInput = { name: string; scope: 'COMMON' | 'HK' | 'MAINLAND'; content: string }
type Props = {
  state: WorkbenchState
  working: boolean
  act: (label: string, request: () => Promise<unknown>, after?: () => void) => Promise<void>
}

export default function MaterialBaseline({ state, working, act }: Props) {
  const [scope, setScope] = useState<SourceInput['scope']>('HK')
  const [name, setName] = useState('经历材料.md')
  const [content, setContent] = useState('')
  const [files, setFiles] = useState<File[]>([])
  const fileInput = useRef<HTMLInputElement>(null)
  const [sourceIds, setSourceIds] = useState<string[]>([])
  const [resumeIds, setResumeIds] = useState<string[]>([])
  const [reviewId, setReviewId] = useState('')
  const [candidateIds, setCandidateIds] = useState<string[]>([])
  const [approvalIds, setApprovalIds] = useState<string[]>([])
  const [history, setHistory] = useState<Fact[] | null>(null)
  const [historyId, setHistoryId] = useState('')
  const reviews = state.materials.reviews
  const review = reviews.find(item => item.id === reviewId) ?? reviews.at(-1)
  const sources = state.materials.sources.filter(source => !state.materials.sources.some(other =>
    other.name === source.name && other.scope === source.scope && other.version > source.version))
  const eligible = review?.candidates.filter(item => ['NEEDS_REVIEW', 'CHANGED'].includes(item.status)) ?? []
  const selected = eligible.filter(item => candidateIds.includes(item.id))
  const selectedResumes = review?.resumes.filter(item => approvalIds.includes(item.id) && !state.resumes.find(r => r.id === item.id)?.approved) ?? []

  function toggle(values: string[], id: string, checked: boolean): string[] {
    return checked ? [...values, id] : values.filter(value => value !== id)
  }

  function analyze() {
    if (!window.confirm('只整理你明确选定的材料。必要正文会交给已配置的可信 AI 命令，模型可能使用外部服务并消耗额度；不会读取目录、连接求职账号或发送申请。同版本结果会直接复用。继续？')) return
    void act('整理选定材料', async () => {
      const uploaded: SourceInput[] = []
      if (content.trim()) uploaded.push({ name: name.trim(), scope, content })
      for (const file of files) {
        if (!/\.(txt|md)$/i.test(file.name) || file.size > 800000) throw new Error('核对材料只接收不超过 800KB 的 TXT/MD；PDF/DOCX 请在简历版本中独立上传，并补充可核对文本。')
        uploaded.push({ name: file.name, scope, content: await file.text() })
      }
      const result = await workbenchJson<MaterialReview>('/baseline/review', 'POST', { sources: uploaded, sourceIds, resumeIds })
      setReviewId(result.id)
      setCandidateIds([])
      setApprovalIds([])
      setContent(''); setFiles([])
      if (fileInput.current) fileInput.current.value = ''
    })
  }

  function confirm() {
    if (!review || review.stale || (!selected.length && !selectedResumes.length)) return
    if (!window.confirm(`我已对照来源核实所选 ${selected.length} 条事实，并已审核所选 ${selectedResumes.length} 份简历的正文和实际附件。此次集中确认只影响所选版本；其余冲突、缺证据和未选项继续待核实，不授权投递。`)) return
    void act('集中确认基线', () => workbenchJson(`/baseline/reviews/${encodeURIComponent(review.id)}/confirm`, 'POST', {
      candidateIds: selected.map(item => item.id), resumeIds: selectedResumes.map(item => item.id),
    }), () => { setCandidateIds([]); setApprovalIds([]) })
  }

  return <section className="rounded-2xl border border-indigo-200 bg-white p-5 shadow-sm dark:border-indigo-800 dark:bg-slate-900" aria-label="一次核对事实基线">
    <h2 className="text-lg font-semibold">已有材料一次核对</h2>
    <p className={`${small} mt-1`}>只读你选定的材料，AI 整理不是事实确认。已有且未变的确认直接复用；矛盾、缺失和无法核实内容不会进入后续材料。不要提供凭据或账号资料。</p>
    <div className="mt-4 grid gap-3 md:grid-cols-2">
      <label className={small}>新材料作用域<select className={`${control} mt-1`} value={scope} onChange={e => setScope(e.target.value as SourceInput['scope'])}><option value="HK">香港</option><option value="MAINLAND">内地</option><option value="COMMON">通用</option></select></label>
      <label className={small}>材料名称<input className={`${control} mt-1`} value={name} onChange={e => setName(e.target.value)} /></label>
      <label className={`${small} md:col-span-2`}>明确提供的材料正文<textarea className={`${control} mt-1 min-h-24`} value={content} onChange={e => setContent(e.target.value)} placeholder="粘贴有权提供的经历材料；不会从本机目录自动查找" /></label>
      <label className={`${small} md:col-span-2`}>或选择 TXT / MD 材料<input ref={fileInput} className={`${control} mt-1`} type="file" multiple accept=".txt,.md" onChange={e => setFiles(Array.from(e.target.files ?? []))} />{files.length > 0 && <span className="block">待整理：{files.map(file => file.name).join('、')}</span>}</label>
    </div>
    {sources.length > 0 && <fieldset className="mt-4 space-y-2"><legend className="font-medium">复用已登记来源（仅勾选项会交给 AI）</legend>{sources.map(source => <label key={source.id} className={`${small} flex gap-2`}><input type="checkbox" checked={sourceIds.includes(source.id)} onChange={e => setSourceIds(toggle(sourceIds, source.id, e.target.checked))} /><span>{source.name} · {scopeName[source.scope]} · v{source.version} · SHA256 {source.sha256.slice(0, 12)}{source.filtered ? ' · 指令/凭据行已隔离' : ''}</span></label>)}</fieldset>}
    <fieldset className="mt-4 space-y-2"><legend className="font-medium">选择独立简历版本参与核对</legend><p className={small}>香港/内地 × 中/英文及岗位版均独立，不自动合并、翻译、核准或覆盖。PDF/DOCX 无正文时只展示附件核准，经历仍待补证据。</p>{state.resumes.length === 0 && <p className={small}>请先在“简历版本”保存需要接入的独立版本。</p>}{state.resumes.map(resume => <label key={resume.id} className={`${small} flex gap-2`}><input type="checkbox" checked={resumeIds.includes(resume.id)} onChange={e => setResumeIds(toggle(resumeIds, resume.id, e.target.checked))} /><span>{resume.label} · {scopeName[resume.region]} · {resume.language === 'zh' ? '中文' : '英文'}{resume.parentId ? ' · 岗位版' : ''} · {resume.approved ? '已核准' : '未核准'}</span></label>)}</fieldset>
    <Button type="button" className="mt-4" disabled={working || state.runtime.busy || (!content.trim() && !files.length && !sourceIds.length && !resumeIds.length)} onClick={analyze}>整理选定材料 / 复用结果</Button>
    {!state.runtime.agentConfigured && <p className={`${small} mt-2`}>AI 未配置：已有结果仍可复用；新的分析会明确阻塞，不伪造候选。</p>}
    {review && <div className="mt-6 space-y-3 border-t border-slate-200 pt-4 dark:border-slate-700">
      <label className={`${small} block`}>核对记录 / 历史依据<select className={`${control} mt-1`} value={review.id} onChange={e => { setReviewId(e.target.value); setCandidateIds([]); setApprovalIds([]) }}>{[...reviews].reverse().map(item => <option key={item.id} value={item.id}>{new Date(item.createdAt).toLocaleString('zh-CN')} · {item.confirmedAt ? '有集中确认记录' : '待核对'} · {item.id.slice(0, 8)}</option>)}</select></label>
      {review.stale && <p role="alert" className="text-sm text-amber-800 dark:text-amber-300">来源已更新或附件不可用，本记录仅作历史依据，不能再次确认。请选择当前版本重新整理。</p>}
      <h3 className="font-semibold">候选事实、重复与矛盾</h3>
      {review.candidates.length === 0 && <p className={small}>没有可核对的候选事实；不会凭空补齐经历。</p>}
      {review.candidates.map(item => <article key={item.id} className="rounded-xl border border-slate-200 p-3 text-sm dark:border-slate-700">
        <label className="flex gap-2"><input type="checkbox" aria-label={`确认事实 ${item.label} ${item.value}`} disabled={working || review.stale || !eligible.some(candidate => candidate.id === item.id)} checked={selected.some(candidate => candidate.id === item.id)} onChange={e => setCandidateIds(toggle(candidateIds, item.id, e.target.checked))} /><span><strong>{item.label}</strong> · {scopeName[item.scope] ?? item.scope} · {statusName[item.status]}</span></label>
        <p className="mt-2 whitespace-pre-wrap break-words">{item.value}</p>
        {item.previousValue && <p className="mt-1 whitespace-pre-wrap text-amber-800 dark:text-amber-300">原确认：{item.previousValue}</p>}
        <blockquote className="mt-2 whitespace-pre-wrap border-l-2 border-indigo-300 pl-3">来源原文：{item.quote}</blockquote>
        <p className={`${small} mt-1 break-all`}>{item.sourceName} · 来源 SHA256 {item.sourceSha256}</p>
      </article>)}
      {review.missing.length > 0 && <div className="rounded-lg bg-amber-50 p-3 text-sm text-amber-900 dark:bg-amber-950 dark:text-amber-200"><h3 className="font-semibold">缺失 / 隔离项 · 仍待核实</h3><ul className="list-inside list-disc">{review.missing.map((item, index) => <li key={index}>{item}</li>)}</ul></div>}
      {review.resumes.length > 0 && <fieldset className="space-y-3"><legend className="font-semibold">本次简历核准（请核对实际附件，不以 AI 整理代替）</legend>{review.resumes.map(resume => {
        const approved = state.resumes.find(item => item.id === resume.id)?.approved
        return <label key={resume.id} className="flex gap-2 rounded-lg border border-slate-200 p-3 text-sm dark:border-slate-700"><input type="checkbox" disabled={working || review.stale || approved} checked={!approved && approvalIds.includes(resume.id)} onChange={e => setApprovalIds(toggle(approvalIds, resume.id, e.target.checked))} /><span className="min-w-0 break-all">{resume.label} · {scopeName[resume.region]} · {resume.language} · {approved ? '已核准，直接复用' : '待人工核准'}<span className="block">{resume.filename} · 附件 SHA256 {resume.sha256}</span><span className="block">正文 SHA256 {resume.contentSha256 || '历史版本，核准时固定'}</span>{resume.parentId && <span className="block">父版本 {resume.parentId} · {resume.jobUrl}</span>}</span></label>
      })}</fieldset>}
      <div className="flex flex-wrap gap-2"><Button type="button" variant="outline" disabled={working || review.stale || !eligible.length} onClick={() => setCandidateIds(eligible.map(item => item.id))}>选择全部可核实事实</Button><Button type="button" disabled={working || review.stale || (!selected.length && !selectedResumes.length)} onClick={confirm}>一次确认所选基线（事实 {selected.length} · 简历 {selectedResumes.length}）</Button></div>
      <p className={small}>未选项保持待核实；已确认且未变的事实无需再选。矛盾请更正来源后重核对，或在下方手动澄清；旧确认依据和任务快照不会覆盖。</p>
    </div>}
    {state.facts.length > 0 && <details className="mt-5 text-sm"><summary className="cursor-pointer font-medium">查看事实确认版本历史</summary><label className={`${small} mt-2 block`}>事实记录<select className={`${control} mt-1`} value={historyId} onChange={e => { setHistoryId(e.target.value); setHistory(null) }}><option value="">选择事实</option>{state.facts.map(fact => <option key={fact.id} value={fact.id}>{fact.label} · {fact.scope} · v{fact.version ?? 1}</option>)}</select></label><Button type="button" size="sm" variant="outline" className="mt-2" disabled={!historyId || working} onClick={() => void act('读取确认历史', async () => {
      const result = await workbenchGet<{ versions: Fact[] }>(`/facts/${encodeURIComponent(historyId)}/history`)
      setHistory(result.versions)
    })}>读取版本历史</Button>{history?.map((fact, index) => <div key={index} className="mt-2 rounded-lg border border-slate-200 p-3 dark:border-slate-700"><p>v{fact.version ?? 1} · {fact.confirmed ? '已确认' : '待核实'} · {fact.updatedAt}</p><p>{fact.value}</p><p className="break-all">{fact.source} · {fact.sourceSha256}</p>{fact.quote && <blockquote>依据：{fact.quote}</blockquote>}</div>)}</details>}
  </section>
}
