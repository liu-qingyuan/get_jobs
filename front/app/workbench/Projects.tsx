'use client'

import { useState } from 'react'
import { Button } from '@/components/ui/button'
import { type WorkbenchState, workbenchJson } from '@/lib/workbench'

const panel = 'rounded-2xl border border-slate-200 bg-white p-5 shadow-sm dark:border-slate-700 dark:bg-slate-900'
const small = 'text-sm text-slate-600 dark:text-slate-300'
const control = 'mt-1 w-full rounded-lg border border-slate-300 bg-white px-3 py-2 text-sm dark:border-slate-600 dark:bg-slate-800'
const statuses = { PROPOSED: '建议 · 未授权执行', BLOCKED: '范围 / 资源阻塞', QUEUED: '已排队 · 等待共享槽', RUNNING: '项目执行中', UNVERIFIED: '产物待独立验证', FAILED: '执行失败 · 未确认成果', STOPPED: '已停止 · 待显式恢复', REJECTED: '独立验收未通过 · 无新成果', VERIFIED: '独立验收通过 · 回流仍按当前范围'  }
type Props = { state: WorkbenchState; working: boolean; act: (label: string, request: () => Promise<unknown>) => Promise<void>; showScope: () => void }

export default function Projects({ state, working, act, showScope }: Props) {
  const [limits, setLimits] = useState({ seconds: 30, modelTokens: 0 })
  return <section className={panel} aria-label="独立项目队列">
    <div className="flex flex-wrap items-center justify-between gap-2"><h2 className="text-lg font-semibold">独立项目队列 · 补强不冒充经历</h2><Button variant="outline" onClick={showScope}>设置项目范围</Button></div>
    <p className={`${small} mt-2`}>从下方当前 GAP 建立项目建议，再在求职范围一次明确选择具体项目、固定目录、本地资源和累计预算。队列独立于岗位；共享资源冲突仍串行。失败或耗时项目不改变其他岗位的生命周期，停止后可恢复。模型/进程自报完成只进入待验证，不增加任职经历或商业年限，不覆盖简历和历史回执。</p>
    {!state.runtime.projectConfigured && <p className={`${small} mt-2`}>尚未配置独立可信项目执行器。操作人须另外配置 WORKBENCH_PROJECT_COMMAND 和源码/私有数据外的 workbench.projects-root；不能借用官网执行器。可信程序不是 OS 沙箱，必须遵守资源上限及无网络/账号/外发合同。</p>}
    <div className="my-3 grid gap-3 sm:grid-cols-2">
      <label className={small}>每次项目运行秒数上限<input className={control} type="number" min="1" max="120" value={limits.seconds} onChange={e => setLimits({ ...limits, seconds: Number(e.target.value) })} /></label>
      <label className={small}>每次项目模型 token 上限（0 表示不用模型）<input className={control} type="number" min="0" max="100000" value={limits.modelTokens} onChange={e => setLimits({ ...limits, modelTokens: Number(e.target.value) })} /></label>
    </div>
    {!state.projects.projects.length && <p className={small}>暂无项目。具体 GAP 的目标与验收建议可建立独立提议；旧手工 GAP 卡片仍仅记录，不能自动执行。</p>}
    {state.projects.projects.map(project => <article key={project.id} className="mt-3 rounded-xl border border-slate-200 p-4 dark:border-slate-700" aria-label={`补强项目 ${project.goal}`}>
      <h3 className="font-semibold">{project.goal}</h3><p className="mt-1 font-semibold text-indigo-700 dark:text-indigo-300">{statuses[project.status]}{project.stopRequested ? ' · 正在停止并释放资源' : ''}</p>
      <p className="mt-2 whitespace-pre-wrap text-sm">可复核验收：{project.acceptance}</p>
      <p className={`${small} mt-2 break-all`}>允许目录：{project.directory || '未配置，不能执行'} · 项目 {project.id}</p>
      <p className={`${small} mt-1 break-all`}>原 GAP 仅作历史依据：{project.requirementQuote} · 分析 {project.analysisId} · 候选 {project.candidateId}</p>
      <p className={`${small} mt-1`}>{project.limitation}</p>
      <div className="mt-3 flex flex-wrap gap-2">
        {['UNVERIFIED', 'REJECTED', 'VERIFIED'].includes(project.status) && <Button type="button" disabled={working || state.runtime.busy || !state.runtime.verifierConfigured || !state.scope.current} onClick={() => {
          const attempt = project.attempts[project.attempts.length - 1]
          if (!attempt || !window.confirm(`按当前范围独立验收此版本？\n目标：${project.goal}\n验收：${project.acceptance}\n尝试 ${attempt.id}；上限 ${limits.seconds} 秒。未通过不产生可用事实；入库还须单独允许此项目的有限本地成果。`)) return
          void act('独立验收项目', () => workbenchJson(`/projects/${project.id}/verify`, 'POST', { scopeVersion: state.scope.current?.version, attemptId: attempt.id, seconds: limits.seconds }))
        }}>独立验收 / 按范围回流成果</Button>}
        {['PROPOSED', 'BLOCKED', 'FAILED', 'STOPPED', 'REJECTED'].includes(project.status) && <Button type="button" disabled={working || !state.runtime.projectConfigured || !project.directory || !state.scope.current} onClick={() => {
          if (!window.confirm(`按当前范围开始/恢复项目？\n目标：${project.goal}\n验收：${project.acceptance}\n唯一允许目录：${project.directory}\n本次预扣 ${limits.seconds} 秒 / ${limits.modelTokens} token，失败不退款。项目产物必须独立验证，不新增经历。`)) return
          void act('项目入队', () => workbenchJson(`/projects/${project.id}/start`, 'POST', { scopeVersion: state.scope.current?.version, ...limits }))
        }}>按当前范围启动 / 恢复项目</Button>}
        {['QUEUED', 'RUNNING'].includes(project.status) && <Button type="button" variant="outline" disabled={working || project.stopRequested} onClick={() => void act('停止项目', () => workbenchJson(`/projects/${project.id}/stop`, 'POST'))}>停止项目</Button>}
      </div>
      {project.attempts.map((attempt, i) => <details key={attempt.id} className="mt-3 text-sm" open={!!attempt.evidence}><summary>运行 {i + 1} · {attempt.status || '运行中'} · 资源授权 {attempt.authorizationId}</summary>
        {attempt.evidence && <div className="mt-2 space-y-2">
          <p>执行器自述（未独立验证）：{attempt.evidence.summary}</p><p className="whitespace-pre-wrap">验收自述：{attempt.evidence.acceptanceReport}</p>
          {attempt.evidence.artifacts.map(file => <p key={file.path} className="break-all">产物 {file.path} · {file.description} · {file.bytes} bytes · SHA256 {file.sha256}</p>)}
        </div>}
      </details>)}
      {(project.verifications ?? []).map(record => <details key={record.id} className="mt-3 rounded-lg border border-indigo-200 p-3 text-sm" open><summary>独立验证 · {record.status} · {record.source}</summary><p className="mt-2 break-all">验证 {record.id} · 尝试 {record.attemptId} · 范围 v{record.scopeVersion}</p><p className="break-all">可信 verifier 身份 SHA256 {record.verifierIdentity} · 合同/证据 SHA256 {record.evidenceSha256}</p>{record.reason && <p className="mt-2">未晋升依据：{record.reason}</p>}{record.checks?.map((check, i) => <p key={i} className="mt-2 whitespace-pre-wrap">{check.passed ? '通过' : '未通过'} · {check.criterion}：{check.evidence}</p>)}{record.contract.artifacts.map(file => <p key={file.path} className="mt-1 break-all">留存证据 {file.path} · {file.bytes} bytes · SHA256 {file.sha256}</p>)}{state.facts.filter(fact => fact.verificationId === record.id).map(fact => <p key={fact.id} className="mt-2 whitespace-pre-wrap">有限成果事实 {fact.id} · v{fact.version}：{fact.value} · 来源 {fact.source}</p>)}</details>)}
      {!state.runtime.verifierConfigured && ['UNVERIFIED', 'REJECTED', 'VERIFIED'].includes(project.status) && <p className={`${small} mt-2`}>尚未配置 WORKBENCH_PROJECT_VERIFIER_COMMAND；必须是独立可信验收程序，不能采信执行器的完成/测试自述。</p>}
      <ol className="mt-3 max-h-48 space-y-1 overflow-y-auto text-sm">{project.events.map((event, i) => <li key={i}>{new Date(event.at).toLocaleTimeString('zh-CN')} · {event.code} · {event.message}</li>)}</ol>
    </article>)}
  </section>
}
