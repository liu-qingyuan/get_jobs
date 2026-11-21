'use client'

import { Button } from '@/components/ui/button'
import { type WorkbenchState, workbenchJson } from '@/lib/workbench'

const names: Record<string, string> = { RUNNING: '正在连续推进', CHECKPOINT: '已暂停在安全断点', PAUSED: '范围 / 预算暂停', EXCEPTION: '逐岗例外待处理', PREPARED: '仅准备完成 · 未发送', COMPLETED: '本轮处理结束', UNKNOWN: '外发结果未知 · 禁止重投', SUBMITTED: '对应投递回执已登记', NEEDS_INPUT: '已知未外发 · 需处理例外', FAILED: '已知未外发 · 执行失败', APPROVED: '策略核准 · 尚未开始', PENDING: '待准备' }
const stages: Record<string, string> = { DISCOVER: '只读发现', MATCH: '有依据匹配', PREPARE: '可证实备料', DISPATCH: '独占逐岗执行' }
type Props = { state: WorkbenchState; working: boolean; act: (label: string, request: () => Promise<unknown>) => Promise<void> }

export default function WorkflowProgress({ state, working, act }: Props) {
  const scope = state.scope.current
  return <section aria-label="整轮进展与安全断点" className="rounded-2xl border border-indigo-200 bg-white p-5 shadow-sm dark:border-slate-700 dark:bg-slate-900">
    <h2 className="text-lg font-semibold">整轮进展与安全断点</h2>
    <p className="mt-2 text-sm">范围内正常岗位自动衔接，不逐岗审批。每次外发前重新核验并取得原子 claim；只有对应回执才记送达，联系不等于 CV 送达。UNKNOWN 只核对证据、不自动重投。准备与匹配例外不妨碍其他合格岗位。</p>
    {scope && <div className="mt-3 flex flex-wrap items-center gap-2 text-sm">
      <span>当前范围 v{scope.version} · {scope.effectiveStatus} · 累计外发 {state.scope.usage.outbound}/{scope.budgets.outbound}</span>
      {scope.status === 'ACTIVE' && <Button variant="outline" disabled={working} onClick={() => void act('暂停范围', () => workbenchJson(`/scope/${scope.version}/pause`, 'POST'))}>暂停范围 / 阻止下一外发</Button>}
      {scope.status !== 'REVOKED' && <Button variant="outline" disabled={working} onClick={() => void act('撤销范围', () => workbenchJson(`/scope/${scope.version}/revoke`, 'POST'))}>撤销范围</Button>}
    </div>}
    {!state.workflow.cycles.length && <p className="mt-3 text-sm text-slate-600 dark:text-slate-300">尚未启动整轮。先确认基线和范围，再用下方一次启动；默认仅准备，不会自动取得发送权限。</p>}
    {[...state.workflow.cycles].reverse().map(cycle => <article key={cycle.id} className="mt-4 rounded-xl border border-slate-200 p-4 dark:border-slate-700">
      <h3 className="font-semibold">{names[cycle.status]} · {stages[cycle.stage ?? ''] ?? '等待推进'} · 范围 v{cycle.scopeVersion}</h3>
      <p className="mt-1 break-all text-xs text-slate-500">轮次 {cycle.id}</p>
      {['CHECKPOINT', 'PAUSED', 'EXCEPTION'].includes(cycle.status) && <><p className="mt-2 text-sm">先解决必要事实 / 登录 / 页面例外，或确认补充预算与当前范围，再恢复同一轮。已耗量不清零，已完成阶段不重放；已联系或 UNKNOWN 候选不会重新发送。</p><Button className="mt-2" variant="outline" disabled={working || state.runtime.busy || scope?.effectiveStatus !== 'ACTIVE'} onClick={() => void act('恢复同一轮安全断点', () => workbenchJson(`/cycles/${cycle.id}/resume`, 'POST', { scopeVersion: scope?.version }))}>处理例外后恢复同一轮</Button></>}
      <div className="mt-3 space-y-3">{cycle.entries.map(entry => {
        const candidate = state.candidates.jobs.find(job => job.id === entry.candidateId)
        const task = entry.execution
        return <div key={entry.candidateId} className="rounded-lg bg-slate-50 p-3 text-sm dark:bg-slate-800">
          <p className="font-semibold">{candidate?.company} · {candidate?.title ?? entry.candidateId} · {names[task?.state ?? entry.status] ?? entry.status}</p>
          <p className="mt-1 whitespace-pre-wrap break-words">{entry.reason}</p>
          {task && <><p className="mt-1 break-all">策略核准 SCOPE_POLICY · 材料包 {task.packageId} · 冻结简历 {task.snapshot?.resume.label} / SHA256 {task.snapshot?.resume.sha256}</p>
            {task.contactReceipt && <p className="mt-1">已有联系证据；{task.cvReceipt ? '本次 CV 回执另行保留' : '未核实 CV，不计完成'}</p>}
            <details className="mt-2"><summary className="cursor-pointer">单岗事件与回执证据</summary><ol className="mt-2 space-y-1">{task.events.map(event => <li key={event.id} className="whitespace-pre-wrap break-words">{event.type} · {event.message}{event.evidence && <pre className="whitespace-pre-wrap break-all text-xs">{event.evidence}</pre>}</li>)}</ol></details></>}
        </div>
      })}</div>
      <details className="mt-3 text-sm"><summary className="cursor-pointer">本轮记录</summary><ol>{cycle.events.map((event, i) => <li key={i}>{event.code} · {event.message}</li>)}</ol></details>
    </article>)}
  </section>
}
