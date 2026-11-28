'use client'

import Link from 'next/link'
import { useCallback, useEffect, useMemo, useState } from 'react'
import { BiCompass, BiRefresh } from 'react-icons/bi'
import { Button } from '@/components/ui/button'
import PageHeader from '@/app/components/PageHeader'
import { NAVIGATION_STATUS_LABEL, platformNavigation } from '@/lib/platform-navigation'
import { type WorkbenchState, workbenchGet } from '@/lib/workbench'

const panel = 'rounded-2xl border border-slate-200 bg-white p-5 shadow-sm dark:border-slate-700 dark:bg-slate-900'
const small = 'text-sm text-slate-600 dark:text-slate-300'

const statusClass: Record<string, string> = {
  available: 'bg-emerald-100 text-emerald-800 dark:bg-emerald-950 dark:text-emerald-200',
  verified: 'bg-teal-100 text-teal-800 dark:bg-teal-950 dark:text-teal-200',
  limited: 'bg-amber-100 text-amber-900 dark:bg-amber-950 dark:text-amber-200',
  unsupported: 'bg-slate-200 text-slate-800 dark:bg-slate-700 dark:text-slate-100',
  blocked: 'bg-rose-100 text-rose-800 dark:bg-rose-950 dark:text-rose-200',
  unknown: 'bg-slate-100 text-slate-700 dark:bg-slate-800 dark:text-slate-300',
}

function errorText(error: unknown): string {
  return error instanceof Error ? error.message : '无法读取工作台运行时状态。'
}

export default function PlatformsPage() {
  const [runtime, setRuntime] = useState<WorkbenchState['runtime'] | null>(null)
  const [platforms, setPlatforms] = useState<WorkbenchState['platforms'] | null>(null)
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState('')

  const refresh = useCallback(async () => {
    setLoading(true)
    try {
      const state = await workbenchGet<Pick<WorkbenchState, 'runtime' | 'platforms'>>('/state')
      setRuntime(state.runtime)
      setPlatforms(state.platforms)
      setLoadError('')
    } catch (error) {
      // 页面是只读导航：API 不可用时仍展示静态条目、链接和限制，不伪造运行时状态。
      setRuntime(null)
      setPlatforms(null)
      setLoadError(errorText(error))
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    void refresh()
  }, [refresh])

  const rows = useMemo(
    () => platformNavigation(runtime ?? {}, platforms ?? undefined),
    [runtime, platforms],
  )

  return (
    <div className="space-y-5 pb-12">
      <PageHeader
        icon={<BiCompass />}
        title="平台导航"
        subtitle="六个平台与公司官网的入口、能力和限制。本页只做只读展示；授权与执行仍由对应平台入口负责。"
        actions={<Button variant="outline" size="sm" onClick={() => void refresh()} disabled={loading}><BiRefresh />{loading ? '读取中…' : '刷新运行时状态'}</Button>}
      />

      <p className={small} role="note">
        导航数据是 UI read model：它不创建任务、不授予动作、不启动执行器，也不参与后端平台分派。运行时状态只影响本页显示。
      </p>

      {loadError && (
        <p role="alert" className="rounded-xl border border-amber-300 bg-amber-50 p-3 text-sm text-amber-900 dark:border-amber-800 dark:bg-amber-950 dark:text-amber-200">
          运行时状态未连接：{loadError}。下方能力按“未知/未连接”显示，不会冒充可用；入口链接仍可打开。
        </p>
      )}

      <section className={panel} aria-labelledby="platform-table-title">
        <h2 id="platform-table-title" className="text-lg font-semibold">平台入口、能力与限制</h2>
        <p className={`${small} mt-1`}>每行只对应一个平台；Boss 一行内同时保留旧审核入口和工作台入口。跳转本身不产生外发动作。</p>
        <div className="mt-4 overflow-x-auto">
          <table className="w-full min-w-[46rem] border-collapse text-left align-top" data-testid="platform-navigation">
            <caption className="sr-only">六个平台与公司官网的入口、能力和限制</caption>
            <thead>
              <tr className="border-b border-slate-200 text-xs uppercase tracking-wide text-slate-500 dark:border-slate-700 dark:text-slate-400">
                <th scope="col" className="w-44 px-3 py-2">平台</th>
                <th scope="col" className="w-48 px-3 py-2">可用入口</th>
                <th scope="col" className="px-3 py-2">能力 / 限制（只读）</th>
              </tr>
            </thead>
            <tbody>
              {rows.map(row => (
                <tr key={row.id} data-platform-row={row.id} className="border-b border-slate-200 align-top last:border-0 dark:border-slate-700">
                  <th scope="row" className="px-3 py-4 align-top">
                    <span className="block text-base font-semibold text-slate-900 dark:text-white">{row.name}</span>
                    <span className={`${small} mt-1 block`}>{row.summary}</span>
                  </th>
                  <td className="px-3 py-4">
                    <ul className="space-y-2">
                      {row.links.map(link => (
                        <li key={`${row.id}-${link.href}`}>
                          <Link href={link.href} className="inline-flex rounded-lg bg-indigo-600 px-3 py-1.5 text-sm font-medium text-white hover:bg-indigo-700 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500 focus-visible:ring-offset-2">
                            {link.label} ↗
                          </Link>
                        </li>
                      ))}
                    </ul>
                  </td>
                  <td className="px-3 py-4">
                    <ul className="space-y-3">
                      {row.capabilities.map(capability => (
                        <li key={`${row.id}-${capability.id}`}>
                          <div className="flex flex-wrap items-center gap-2">
                            <span className={`rounded-full px-2 py-0.5 text-xs font-semibold ${statusClass[capability.status]}`}>
                              {NAVIGATION_STATUS_LABEL[capability.status]}
                            </span>
                            <span className="text-sm font-medium text-slate-900 dark:text-white">{capability.label}</span>
                          </div>
                          <p className={`${small} mt-1 break-words`}>{capability.detail}</p>
                        </li>
                      ))}
                    </ul>
                    {row.limits.length > 0 && (
                      <ul className="mt-3 list-disc space-y-1 pl-5 text-sm text-slate-600 dark:text-slate-300">
                        {row.limits.map(limit => <li key={`${row.id}-limit-${limit}`}>{limit}</li>)}
                      </ul>
                    )}
                    {row.runtimeNotes.length > 0 && (
                      <ul className="mt-3 space-y-1 text-xs text-slate-500 dark:text-slate-400">
                        {row.runtimeNotes.map(note => <li key={`${row.id}-note-${note}`}>{note}</li>)}
                      </ul>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>

      <section className={panel}>
        <h2 className="text-lg font-semibold">这些限制从哪里来</h2>
        <ul className="mt-2 list-disc space-y-1 pl-5 text-sm text-slate-700 dark:text-slate-300">
          <li>平台条目和链接来自本仓库既有前端入口；Boss、OfferToday、JobsDB 与公司官网的状态来自 <code>/api/workbench/state</code> 的 <code>runtime</code> / <code>platforms</code>。</li>
          <li>OfferToday 两版生产发送绑定 <code>dispatchProofByBuild.legacy</code> 与 <code>.current</code> 均为空；一次已验证送达不代表生产自动发送启用。</li>
          <li>JobsDB 单岗申请沿用既有 <code>apply-single</code> 和 <code>applications.db</code> 占位；导航不创建授权、不启动批次。</li>
          <li>Liepin、51job、Zhilian 的受控单目标能力为 <code>unsupported</code>；旧 batch Worker 保留，但直接请求应由现有边界拒绝。</li>
        </ul>
      </section>
    </div>
  )
}
