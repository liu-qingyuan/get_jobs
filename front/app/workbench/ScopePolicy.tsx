'use client'

import { useState } from 'react'
import { Button } from '@/components/ui/button'
import { type ScopeAction, type ScopeConfig, type ScopeDecision, type ScopeMeter, type ScopePolicy, type WorkbenchState, workbenchJson } from '@/lib/workbench'

const control = 'w-full rounded-lg border border-slate-300 bg-white px-3 py-2 text-sm dark:border-slate-600 dark:bg-slate-800'
const panel = 'rounded-2xl border border-indigo-200 bg-white p-5 shadow-sm dark:border-indigo-800 dark:bg-slate-900'
const small = 'text-sm text-slate-600 dark:text-slate-300'
const actions: Record<ScopeAction, string> = { DISCOVER: '只读发现', MATCH: '批量匹配', PREPARE: '准备材料', APPLY: '提交申请', CONTACT: '首次联系', SEND_CV: '联系后发送简历' }
const meters: Record<ScopeMeter, string> = { candidates: '候选数量', modelTokens: '模型 token 上限', seconds: '运行秒数', outbound: '不可逆外发动作次数' }
const statuses: Record<string, string> = { ACTIVE: '有效', PAUSED: '已暂停', REVOKED: '已撤销', EXPIRED: '已到期', MATERIAL_CHANGED: '材料已变，须重新确认', EXHAUSTED: '预算已耗尽' }
const choices = {
  channels: { OFFICIAL: '公司官网', OFFERTODAY: 'OfferToday HK', BOSS: 'Boss', JOBSDB: 'JobsDB' },
  regions: { HK: '香港', MAINLAND: '内地' }, languages: { zh: '中文', en: '英文' },
  directions: { AI_APPLICATION: 'AI 应用', ML: '机器学习', EMBODIED: '具身智能' },
  transformations: { ORIGINAL: '使用原文', REORDER: '重排已有事实', REPHRASE: '改写表达（不新增主张）' },
}
function localTime(iso: string) { const date = new Date(iso); return new Date(date.getTime() - date.getTimezoneOffset() * 60000).toISOString().slice(0, 16) }
function draft(scope?: ScopePolicy): ScopeConfig {
  return scope ? { ...scope, expectedVersion: scope.version, expiresAt: localTime(scope.expiresAt) } : {
    channels: ['OFFICIAL'], hosts: [], regions: ['HK'], languages: ['en'], directions: ['AI_APPLICATION'],
    includeTerms: [], excludeTerms: [], factIds: [], resumeIds: [], transformations: ['ORIGINAL'],
    actions: ['DISCOVER', 'MATCH', 'PREPARE'], budgets: { candidates: 40, modelTokens: 10000, seconds: 1800, outbound: 0 },
    expiresAt: localTime(new Date(Date.now() + 24 * 3600000).toISOString()), expectedVersion: 0,
  }
}
function toggle<T extends string>(values: T[], value: T, checked: boolean): T[] { return checked ? [...values, value] : values.filter(item => item !== value) }
function lines(value: string) { return value.split('\n').map(item => item.trim()).filter(Boolean) }

type Props = { state: WorkbenchState; working: boolean; act: (label: string, request: () => Promise<unknown>, after?: () => void) => Promise<void> }
export default function ScopePolicyPanel({ state, working, act }: Props) {
  const current = state.scope.current
  const [form, setForm] = useState<ScopeConfig>(() => draft(current))
  const [action, setAction] = useState<string>('PREPARE')
  const [target, setTarget] = useState({ channel: 'OFFICIAL', region: 'HK', language: 'en', direction: 'AI_APPLICATION', company: '', title: '', description: '', url: '', resumeId: current?.resumeIds[0] ?? '', transformation: 'ORIGINAL' })
  const [decision, setDecision] = useState<ScopeDecision | null>(null)
  const confirmed = state.facts.filter(fact => fact.confirmed && !fact.needsReview)
  const approved = state.resumes.filter(resume => resume.approved)
  function save() {
    if (!window.confirm(`一次确认此求职范围？\n渠道：${form.channels.join('、')}\n精确来源：${form.hosts.join('、')}\n地区/语言/方向：${form.regions.join('、')} / ${form.languages.join('、')} / ${form.directions.join('、')}\n选择词：${form.includeTerms.join('、') || '不限'}\n排除词：${form.excludeTerms.join('、') || '无'}\n事实 ${form.factIds.length} 条、独立简历 ${form.resumeIds.length} 份；变换：${form.transformations.join('、')}\n允许动作：${form.actions.map(item => actions[item]).join('、')}\n累计预算上限：${Object.entries(form.budgets).map(([key, value]) => `${meters[key as ScopeMeter]} ${value}`).join('；')}\n有效期：${form.expiresAt}\n\n旧未执行授权失效，已消耗量不重置。保存不启动执行、不替代 claim 或将单岗许可扩大成批次。`)) return
    void act('确认求职范围', async () => {
      const result = await workbenchJson<ScopePolicy>('/scope', 'POST', { ...form, hosts: lines(form.hosts.join('\n')), includeTerms: lines(form.includeTerms.join('\n')), excludeTerms: lines(form.excludeTerms.join('\n')), expiresAt: new Date(form.expiresAt).toISOString() })
      setForm(draft(result)); setDecision(null)
      setTarget(previous => ({ ...previous, resumeId: result.resumeIds.includes(previous.resumeId) ? previous.resumeId : result.resumeIds[0] ?? '' }))
    })
  }
  function change(operation: 'pause' | 'resume' | 'revoke') {
    if (!current || !window.confirm('此操作生成新范围版本，旧未执行授权不会恢复。已开始的动作只收集结果，不继续下一外发阶段。继续？')) return
    void act('更新范围状态', async () => {
      const result = await workbenchJson<ScopePolicy>(`/scope/${current.version}/${operation}`, 'POST')
      setForm(draft(result)); setDecision(null)
    })
  }
  return <div className="space-y-4">
    <section className={panel} aria-label="求职范围设置">
      <h2 className="text-lg font-semibold">一次设置有界、可撤销的求职范围</h2>
      <p className={`${small} mt-1`}>默认仅准备。范围是策略来源，不冒充逐岗人审；保存或预检均不会发送消息、上传简历或执行项目。渠道是否具备受控执行能力由后续执行核验，不因范围允许就显示已接通。</p>
      {current ? <div className="my-4 rounded-lg bg-indigo-50 p-3 dark:bg-indigo-950">
        <h3 className="font-semibold">当前范围 v{current.version} · {statuses[current.effectiveStatus ?? current.status]}</h3>
        <p className={small}>来源：用户一次确认 USER_SCOPE · 有效至 {new Date(current.expiresAt).toLocaleString('zh-CN')}</p>
        <p className={`${small} break-words`}>允许：{current.actions.map(item => actions[item]).join('、')} · {current.channels.join('、')} · {current.hosts.join('、')}</p>
        <div className="mt-2 grid gap-2 sm:grid-cols-2">{(Object.keys(meters) as ScopeMeter[]).map(meter => <p key={meter} className={small}>{meters[meter]}：已耗 {state.scope.usage[meter]} / 累计上限 {current.budgets[meter]}</p>)}</div>
        <div className="mt-3 flex flex-wrap gap-2">
          <Button variant="outline" type="button" disabled={working || current.status !== 'ACTIVE'} onClick={() => change('pause')}>暂停范围</Button>
          <Button variant="outline" type="button" disabled={working || current.status !== 'PAUSED'} onClick={() => change('resume')}>恢复范围（新版本）</Button>
          <Button variant="outline" type="button" disabled={working || current.status === 'REVOKED'} onClick={() => change('revoke')}>撤销范围</Button>
          <Button variant="outline" type="button" disabled={working} onClick={() => { setForm(draft(current)); setDecision(null) }}>载入当前范围设置</Button>
        </div>
        <details className="mt-3 text-sm"><summary>查看当前已固定条件与材料版本</summary><pre className="mt-2 max-h-80 overflow-auto whitespace-pre-wrap break-all">{JSON.stringify(current, null, 2)}</pre></details>
      </div> : <p className={`${small} my-3`}>尚未确认范围，不具备任何范围授权。</p>}
      <form className="mt-4 space-y-4" onSubmit={event => { event.preventDefault(); save() }}>
        <p className={small}>编辑基于 v{form.expectedVersion ?? 0}。变更期间若范围已被其他操作更新，保存将拒绝，不会覆盖。预算是整个工作台本轮体系的累计上限；新版本补充额度须提高总上限，而非重置已耗量。</p>
        <div className="grid gap-4 md:grid-cols-2">{(Object.keys(choices) as Array<keyof typeof choices>).map(field => <fieldset key={field} className="space-y-1"><legend className="font-medium">{({ channels: '允许渠道', regions: '地区范围', languages: '材料语言', directions: '求职方向', transformations: '允许材料变换' })[field]}</legend>{Object.entries(choices[field]).map(([value, name]) => <label key={value} className={`${small} mr-3 inline-flex gap-2`}><input type="checkbox" checked={form[field].includes(value)} onChange={e => setForm({ ...form, [field]: toggle(form[field], value, e.target.checked) })} />{name}</label>)}</fieldset>)}</div>
        <div className="grid gap-3 md:grid-cols-3">
          <label className={small}>来源精确域名（每行一个）<textarea className={`${control} mt-1`} required value={form.hosts.join('\n')} onChange={e => setForm({ ...form, hosts: e.target.value.split('\n') })} placeholder="careers.example.com" /></label>
          <label className={small}>岗位选择词（任一命中；每行一个）<textarea className={`${control} mt-1`} value={form.includeTerms.join('\n')} onChange={e => setForm({ ...form, includeTerms: e.target.value.split('\n') })} /></label>
          <label className={small}>岗位排除词（任一命中；每行一个）<textarea className={`${control} mt-1`} value={form.excludeTerms.join('\n')} onChange={e => setForm({ ...form, excludeTerms: e.target.value.split('\n') })} /></label>
        </div>
        <p className={small}>选择/排除词按公司、标题和 JD 的不区分大小写子串判断，不执行网页/模型指令；域名精确匹配，不自动放行子域名或非标准端口。</p>
        <fieldset className="space-y-2"><legend className="font-medium">范围内已确认事实</legend>{confirmed.length === 0 && <p className={small}>尚无已确认事实；可以只读发现，备料不得补造经历。</p>}{confirmed.map(fact => <label key={fact.id} className={`${small} flex gap-2`}><input type="checkbox" checked={form.factIds.includes(fact.id)} onChange={e => setForm({ ...form, factIds: toggle(form.factIds, fact.id, e.target.checked) })} /><span>{fact.label} · {fact.scope} · v{fact.version ?? 1}：{fact.value}</span></label>)}</fieldset>
        <fieldset className="space-y-2"><legend className="font-medium">范围内独立简历版本</legend>{approved.length === 0 && <p className={small}>请先核准独立简历；无所选版本时不能授权备料/外发。</p>}{approved.map(resume => <label key={resume.id} className={`${small} flex gap-2`}><input type="checkbox" checked={form.resumeIds.includes(resume.id)} onChange={e => setForm({ ...form, resumeIds: toggle(form.resumeIds, resume.id, e.target.checked) })} /><span className="break-all">{resume.label} · {resume.region} / {resume.language}{resume.parentId ? ' · 独立岗位版' : ''} · SHA256 {resume.sha256}</span></label>)}</fieldset>
        <fieldset><legend className="font-medium">允许动作（外发须另外明确勾选）</legend><div className="mt-2 flex flex-wrap gap-3">{(Object.keys(actions) as ScopeAction[]).map(value => <label key={value} className={`${small} inline-flex gap-2`}><input type="checkbox" checked={form.actions.includes(value)} onChange={e => setForm({ ...form, actions: toggle(form.actions, value, e.target.checked) })} />{actions[value]}</label>)}</div><p className={`${small} mt-2`}>项目执行/成果入库尚未开放，不能借用准备或外发授权。任何变换均不得新增事实、翻译覆盖其他地区/语言版本。</p></fieldset>
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">{(Object.keys(meters) as ScopeMeter[]).map(meter => <label key={meter} className={small}>{meters[meter]}<input className={`${control} mt-1`} type="number" min="0" max="1000000000" step="1" required value={form.budgets[meter]} onChange={e => setForm({ ...form, budgets: { ...form.budgets, [meter]: Number(e.target.value) } })} /></label>)}</div>
        <p className={small}>计量在动作前原子扣除所授最大资源，不因失败退款；预检/冻结授权不扣量。每次动作至少 1 秒，发现至少 1 候选、模型匹配/备料至少 1 token、每个不可逆阶段至少 1 次外发；执行消费者还须把实际 Adapter 限在申请的资源上限内。</p>
        <label className={`${small} block`}>范围有效期<input className={`${control} mt-1`} type="datetime-local" required value={form.expiresAt} onChange={e => setForm({ ...form, expiresAt: e.target.value })} /></label>
        <Button type="submit" disabled={working}>{current ? '变更范围（新版本）' : '一次确认求职范围'}</Button>
      </form>
    </section>
    <section className={panel} aria-label="代表性动作预检">
      <h2 className="text-lg font-semibold">代表性动作预检</h2><p className={`${small} mt-1`}>仅使用当前已保存范围；预检是即时解释，不是可冻结许可，也不代表渠道已可执行。UNKNOWN、已运行或已投递的目标持续参与防重。</p>
      <form className="mt-4 grid gap-3 md:grid-cols-2" onSubmit={event => { event.preventDefault(); void act('范围预检', async () => setDecision(await workbenchJson<ScopeDecision>('/scope/preview', 'POST', { scopeVersion: current?.version ?? 0, action, target }))) }} onChange={() => setDecision(null)}>
        <label className={small}>预检动作<select className={`${control} mt-1`} value={action} onChange={e => setAction(e.target.value)}>{Object.entries(actions).map(([value, name]) => <option key={value} value={value}>{name}</option>)}<option value="PROJECT_EXECUTE">项目执行（未开放，将拒绝）</option></select></label>
        {(Object.keys(choices) as Array<keyof typeof choices>).map(field => {
          const key = ({ channels: 'channel', regions: 'region', languages: 'language', directions: 'direction', transformations: 'transformation' } as const)[field]
          return <label key={field} className={small}>预检{({ channels: '渠道', regions: '地区', languages: '语言', directions: '方向', transformations: '材料变换' })[field]}<select className={`${control} mt-1`} value={target[key]} onChange={e => setTarget({ ...target, [key]: e.target.value })}>{Object.entries(choices[field]).map(([value, name]) => <option key={value} value={value}>{name}</option>)}</select></label>
        })}
        {(['company', 'title', 'url', 'description'] as const).map(field => <label key={field} className={small}>{({ company: '预检公司', title: '预检岗位', url: '预检岗位链接', description: '预检 JD' })[field]}<input className={`${control} mt-1`} type={field === 'url' ? 'url' : 'text'} required={field !== 'description'} value={target[field]} onChange={e => setTarget({ ...target, [field]: e.target.value })} /></label>)}
        <label className={small}>预检简历<select className={`${control} mt-1`} value={target.resumeId} onChange={e => setTarget({ ...target, resumeId: e.target.value })}><option value="">未选（不能备料/外发）</option>{approved.map(resume => <option key={resume.id} value={resume.id}>{resume.label} · {resume.region}/{resume.language}</option>)}</select></label>
        <Button type="submit" disabled={working || !current} className="self-end">只预检，不授权或执行</Button>
      </form>
      {decision && <div className="mt-4 rounded-lg border border-slate-300 p-3" role="status"><h3 className="font-semibold">{decision.allowed ? '允许 · 策略预检，不代表已执行' : '拒绝 · 未授权动作不会执行'}</h3><p className={small}>来源 {decision.source} · 范围 v{decision.scopeVersion}（当前 v{current?.version}）{decision.scopeVersion !== current?.version ? ' · 结果已过期，请重新预检' : ''}</p><ul className="mt-2 list-inside list-disc text-sm">{decision.reasons.map((reason, index) => <li key={index}>{reason}</li>)}</ul></div>}
    </section>
    <section className={panel} aria-label="范围历史与动作依据"><h2 className="font-semibold">范围历史与动作依据</h2><p className={`${small} mt-1`}>旧版本仅供追溯，不复活旧授权。已发生事件不回滚；动作结果由可信消费者报告，不冒充网站独立回执。</p>{[...state.scope.history].reverse().map(scope => <p key={scope.id} className={`${small} mt-2`}>v{scope.version} · {statuses[scope.status]}（历史记录） · {scope.source} · {new Date(scope.createdAt).toLocaleString('zh-CN')}</p>)}{state.scope.authorizations.map(auth => <details key={auth.id} className="mt-3 text-sm"><summary>v{auth.scopeVersion} · {auth.source} · {auth.status === 'FROZEN' ? '冻结，消费前仍须复核' : '已消费，不可重用'} · {actions[auth.request.action]} · {auth.request.target.title}</summary><p className="break-all">目标：{auth.request.target.url} · 授权 ID：{auth.id}</p>{auth.result && <p>结果：{auth.result.outcome} · {auth.result.evidence}</p>}</details>)}</section>
  </div>
}
