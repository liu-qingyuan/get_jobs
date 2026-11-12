'use client'

import Link from 'next/link'
import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react'
import { BiBriefcase, BiCopy, BiDownload, BiPlus, BiRefresh, BiBell } from 'react-icons/bi'
import { Button } from '@/components/ui/button'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'
import PageHeader from '@/app/components/PageHeader'
import {
  type Channel, type EventType, type Fact, type FactInput, type Language, type OfferTodayDiscovery, type Region, type Resume,
  type Task, type TaskInput, type TaskKind, type TaskState, type WorkbenchState,
  workbenchFile, workbenchGet, workbenchJson, workbenchUpload,
} from '@/lib/workbench'

const panel = 'rounded-2xl border border-slate-200 bg-white p-5 shadow-sm dark:border-slate-700 dark:bg-slate-900'
const control = 'w-full rounded-lg border border-slate-300 bg-white px-3 py-2 text-sm text-slate-900 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500 dark:border-slate-600 dark:bg-slate-800 dark:text-white'
const small = 'text-sm text-slate-600 dark:text-slate-300'
const regionName: Record<Region, string> = { HK: '香港', MAINLAND: '内地' }
const languageName: Record<Language, string> = { zh: '中文', en: '英文' }
const kindName: Record<TaskKind, string> = { APPLICATION: '岗位申请', RESUME: '简历草稿', GAP: '项目补强', CHECK: '只读进度查询' }
const channelName: Record<Channel, string> = { OFFICIAL: '公司官网', JOBSDB: 'JobsDB', BOSS: 'Boss', OFFERTODAY: 'OfferToday HK' }
const stateName: Record<TaskState, string> = {
  PENDING: '待批准', APPROVED: '已批准', RUNNING: '执行中', NEEDS_INPUT: '待人工处理',
  FAILED: '执行失败', UNKNOWN: '提交结果未知', SUBMITTED: '已记录回执', COMPLETED: '已完成',
}
const eventName: Record<string, string> = {
  APPROVED: '材料已批准', RUNNING: '执行器已启动', PROCESS_ENDED: '执行器结束', RECOVERED: '中断恢复', DRAFT: '已生成待审核草稿',
  PROGRESS: '进展', NEEDS_INPUT: '需要人工处理', FAILED: '失败', SUBMITTING: '提交前占位（结果未知）',
  SUBMITTED: '确认已提交', EMPLOYER_STATUS: '人工核对雇主状态',
  CONTACTED: '已联系（未送达简历）', CV_DELIVERED: '简历已送达',
}
const alertStates: TaskState[] = ['NEEDS_INPUT', 'FAILED', 'UNKNOWN']
const resumeInstruction = '仅产出待人工审核的简历草稿；不得自行批准、投递或声称已导出 PDF。'
const blankTask: TaskInput = {
  kind: 'APPLICATION', channel: 'OFFICIAL', region: 'HK', language: 'en', direction: 'AI_APPLICATION',
  company: '', title: '', url: '', description: '', note: '',
}
const blankFact: FactInput = { key: '', label: '', value: '', scope: 'COMMON', confirmed: false, source: '工作台手动录入' }
type ResumeDraft = { label: string; region: Region; language: Language; content: string; parentId: string; jobUrl: string }
const blankResume: ResumeDraft = { label: '', region: 'HK', language: 'en', content: '', parentId: '', jobUrl: '' }

function date(value?: string): string {
  if (!value) return '—'
  const parsed = new Date(value)
  return Number.isNaN(parsed.getTime()) ? value : parsed.toLocaleString('zh-CN')
}

function errorText(error: unknown): string {
  return error instanceof Error ? error.message : '操作失败，请稍后重试。'
}

function taskLabel(task: Task): string {
  return task.company ? `${task.company} · ${task.title}` : task.title
}

function taskStateName(task: Task): string {
  return task.channel === 'OFFERTODAY' && task.state === 'SUBMITTED' && !task.cvReceipt
    ? '缺少简历送达回执，待核实' : stateName[task.state]
}

export default function WorkbenchPage() {
  const [state, setState] = useState<WorkbenchState | null>(null)
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState('')
  const [actionError, setActionError] = useState('')
  const [notice, setNotice] = useState('')
  const [updatedAt, setUpdatedAt] = useState('')
  const [working, setWorking] = useState('')
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [tab, setTab] = useState('tasks')
  const [newAlerts, setNewAlerts] = useState<string[]>([])
  const [notifications, setNotifications] = useState(false)
  const seenStates = useRef<Map<string, TaskState> | null>(null)
  const notifyRef = useRef(false)
  const loadingRef = useRef(false)

  const [taskForm, setTaskForm] = useState<TaskInput>(blankTask)
  const [discovery, setDiscovery] = useState<OfferTodayDiscovery | null>(null)
  const newTaskNode = useRef<HTMLElement>(null)
  const [factForm, setFactForm] = useState<FactInput>(blankFact)
  const [scopeType, setScopeType] = useState<'COMMON' | 'HK' | 'MAINLAND' | 'SITE'>('COMMON')
  const [siteHost, setSiteHost] = useState('')
  const [preview, setPreview] = useState<Fact[] | null>(null)
  const [importSelected, setImportSelected] = useState<number[]>([])
  const [resumeForm, setResumeForm] = useState<ResumeDraft>(blankResume)
  const [resumeFile, setResumeFile] = useState<File | null>(null)
  const [resumeFilter, setResumeFilter] = useState('all')
  const fileInput = useRef<HTMLInputElement>(null)
  const resumeFormNode = useRef<HTMLFormElement>(null)
  const taskDetailNode = useRef<HTMLElement>(null)
  const [context, setContext] = useState<{ taskId: string; updatedAt: string; text: string } | null>(null)
  const [eventType, setEventType] = useState<EventType>('PROGRESS')
  const [eventMessage, setEventMessage] = useState('')
  const [eventEvidence, setEventEvidence] = useState('')
  const [conversationId, setConversationId] = useState('')
  const [messageId, setMessageId] = useState('')

  const refresh = useCallback(async () => {
    if (loadingRef.current || (document.visibilityState !== 'visible' && !notifyRef.current)) return
    loadingRef.current = true
    try {
      const next = await workbenchGet<WorkbenchState>('/state')
      const observed = new Map(next.tasks.map(task => [task.id, task.state]))
      if (seenStates.current) {
        const transitions = next.tasks.filter(task =>
          seenStates.current?.has(task.id) && seenStates.current.get(task.id) !== task.state && alertStates.includes(task.state)
        )
        if (transitions.length) {
          setNewAlerts(current => [...transitions.map(task => `${taskLabel(task)}：${taskStateName(task)}`), ...current].slice(0, 6))
          if (notifyRef.current && typeof Notification !== 'undefined' && Notification.permission === 'granted') {
            transitions.forEach(task => {
              try {
                new Notification('求职工作台需要关注', {
                  body: `${taskLabel(task)}：${taskStateName(task)}`,
                })
              } catch { /* 桌面通知失败也不阻断站内状态同步 */ }
            })
          }
        }
      }
      seenStates.current = observed // 首次加载仅建立基线，绝不通知旧状态
      setState(next)
      setSelectedId(current => current && next.tasks.some(task => task.id === current) ? current : next.tasks[0]?.id ?? null)
      setUpdatedAt(new Date().toLocaleTimeString('zh-CN'))
      setLoadError('')
    } catch (error) {
      setLoadError(errorText(error))
    } finally {
      setLoading(false)
      loadingRef.current = false
    }
  }, [])

  useEffect(() => {
    void refresh()
    const timer = window.setInterval(() => { void refresh() }, 3000)
    const onVisible = () => { if (document.visibilityState === 'visible') void refresh() }
    document.addEventListener('visibilitychange', onVisible)
    return () => { window.clearInterval(timer); document.removeEventListener('visibilitychange', onVisible) }
  }, [refresh])

  function selectTask(id: string) {
    setSelectedId(id)
    setEventType('PROGRESS')
    setEventMessage('')
    setEventEvidence('')
    setConversationId('')
    setMessageId('')
  }

  async function act(label: string, request: () => Promise<unknown>, after?: () => void) {
    if (working) return
    setWorking(label)
    setActionError('')
    setNotice('')
    try {
      await request()
      after?.()
      setContext(null)
      setNotice(`${label}成功。请以服务端状态为准。`)
    } catch (error) {
      setActionError(errorText(error))
    } finally {
      setWorking('')
      void refresh()
    }
  }

  async function discoverOfferToday() {
    if (working) return
    setWorking('读取当前职位')
    setActionError('')
    setNotice('')
    setDiscovery(null)
    try {
      const result = await workbenchJson<OfferTodayDiscovery>('/offertoday/discover', 'POST')
      setDiscovery({ ...result, jobs: result.jobs.slice(0, 40) })
      if (result.connected === false) setActionError(result.note || '未连接当前已授权的 Chrome 标签页。')
      else setNotice('只读获取当前已加载职位（最多 40 条）；未自动翻页或创建任务。')
    } catch (error) {
      setActionError(errorText(error))
    } finally {
      setWorking('')
    }
  }

  function chooseOfferToday(job: OfferTodayDiscovery['jobs'][number]) {
    setTaskForm({ ...blankTask, channel: 'OFFERTODAY', region: 'HK', language: taskForm.language,
      direction: taskForm.direction, company: job.company || '', title: job.title, url: job.url,
      description: job.description || '', initialMessage: '', contactVerified: false })
    setActionError('')
    setNotice('已预填待创建申请；请核对岗位、独立港区附件和首条消息，再亲自创建与批准。')
    setTab('tasks')
    window.requestAnimationFrame(() => newTaskNode.current?.scrollIntoView({ behavior: 'smooth', block: 'start' }))
  }

  async function enableNotifications() {
    if (notifications) {
      notifyRef.current = false
      setNotifications(false)
      return
    }
    if (typeof Notification === 'undefined') {
      setActionError('此浏览器不支持桌面通知；工作台内的待处理提示仍可使用。')
      return
    }
    try {
      const permission = await Notification.requestPermission() // 只在点击按钮后申请
      notifyRef.current = permission === 'granted'
      setNotifications(permission === 'granted')
      if (permission !== 'granted') setActionError('未获得通知权限；仍会显示站内提醒。')
      else setActionError('')
    } catch {
      setActionError('无法启用桌面通知；仍会显示站内提醒。')
    }
  }

  const selected = state?.tasks.find(task => task.id === selectedId)
  const offerTodayForm = taskForm.kind === 'APPLICATION' && taskForm.channel === 'OFFERTODAY'
  const offerTodaySelected = selected?.kind === 'APPLICATION' && selected.channel === 'OFFERTODAY'
  const approvedContext = selected && selected.state === 'APPROVED' && context?.taskId === selected.id &&
    context.updatedAt === selected.updatedAt ? context.text : null
  const matchingResumes = state?.resumes.filter(resume =>
    resume.approved && resume.region === taskForm.region && resume.language === taskForm.language &&
    (!offerTodayForm || (resume.region === 'HK' && /\.(pdf|docx)$/i.test(resume.filename)))
  ) ?? []
  const selectedResume = state?.resumes.find(resume => resume.id === selected?.resumeId)
  const approvalReady = !!(selected && selected.kind !== 'GAP' && selectedResume?.approved &&
    selectedResume.region === selected.region && selectedResume.language === selected.language &&
    (!offerTodaySelected || (selected.region === 'HK' && /\.(pdf|docx)$/i.test(selectedResume.filename) &&
      !!selected.initialMessage?.trim() && selected.contactVerified === true)))
  const canApprove = !!(selected && ['PENDING', 'FAILED', 'NEEDS_INPUT'].includes(selected.state) &&
    !(offerTodaySelected && selected.state === 'NEEDS_INPUT') && approvalReady)
  const canStart = !!(selected && selected.state === 'APPROVED' && !state?.runtime.busy && (
    offerTodaySelected ? state?.runtime.offerTodayConfigured && !!selected.snapshot?.initialMessageSha256 &&
      selected.snapshot?.contactVerified && !!selected.snapshot?.resume?.sha256 :
      state?.runtime.agentConfigured && (selected.kind === 'RESUME' || selected.kind === 'CHECK' ||
        (selected.kind === 'APPLICATION' && selected.channel === 'OFFICIAL'))
  ))
  const incidents = state?.tasks.filter(task => alertStates.includes(task.state)) ?? []
  const lastExecutionEvent = selected?.events.filter(item => item.type !== 'EMPLOYER_STATUS').slice(-1)[0]
  const eventOptions: EventType[] = offerTodaySelected && selected
    ? selected.state === 'UNKNOWN' ? ['PROGRESS', ...(!selected.contactReceipt ? ['CONTACTED' as EventType] :
        !selected.cvReceipt ? ['CV_DELIVERED' as EventType] : [])]
      : selected.state === 'RUNNING' ? ['PROGRESS', 'NEEDS_INPUT', 'FAILED'] : []
    : selected ? [
      ...(selected.state === 'APPROVED' || selected.state === 'NEEDS_INPUT' ? ['PROGRESS'] as EventType[] : []),
      ...(selected.state === 'RUNNING' ? ['PROGRESS', 'NEEDS_INPUT', 'FAILED'] as EventType[] : []),
      ...(selected.kind === 'APPLICATION' && selected.state === 'RUNNING' ? ['SUBMITTING'] as EventType[] : []),
      ...(selected.kind === 'APPLICATION' && selected.state === 'UNKNOWN' ? ['SUBMITTED'] as EventType[] : []),
      ...(selected.kind === 'APPLICATION' ? ['EMPLOYER_STATUS'] as EventType[] : []),
    ] : []
  const chosenEventType = eventOptions.includes(eventType) ? eventType : eventOptions[0]
  const receiptEvent = chosenEventType === 'CONTACTED' || chosenEventType === 'CV_DELIVERED'

  function submitTask(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (offerTodayForm && (!taskForm.initialMessage?.trim() || !taskForm.contactVerified ||
      !matchingResumes.some(resume => resume.id === taskForm.resumeId))) {
      setActionError('OfferToday 必须先填写首条消息、核对联系方式与线上资料，并选择已审核的港区 PDF/DOCX 附件。')
      return
    }
    const { initialMessage, contactVerified, ...taskFields } = taskForm
    const payload: TaskInput = {
      ...taskFields,
      ...(offerTodayForm ? { initialMessage, contactVerified } : {}),
      company: taskForm.company.trim(), title: taskForm.title.trim(), url: taskForm.url.trim(),
      description: taskForm.description.trim(),
      note: taskForm.kind === 'RESUME'
        ? `${resumeInstruction}${taskForm.note.trim() ? `\n${taskForm.note.trim()}` : ''}` : taskForm.note.trim(),
      resumeId: taskForm.kind === 'GAP' ? undefined : taskForm.resumeId || undefined,
      projectPath: taskForm.kind === 'GAP' ? taskForm.projectPath?.trim() : undefined,
      projectPlan: taskForm.kind === 'GAP' ? taskForm.projectPlan?.trim() : undefined,
    }
    void act('创建任务', async () => {
      const task = await workbenchJson<Task>('/tasks', 'POST', payload)
      selectTask(task.id)
    }, () => setTaskForm(blankTask))
  }

  function approveTask(task: Task) {
    const resume = state?.resumes.find(item => item.id === task.resumeId)
    if (!resume?.approved || resume.region !== task.region || resume.language !== task.language || task.kind === 'GAP') return
    if (task.channel === 'OFFERTODAY') {
      if (task.kind !== 'APPLICATION' || task.region !== 'HK' || task.state === 'NEEDS_INPUT' ||
        !/\.(pdf|docx)$/i.test(resume.filename) || !task.initialMessage?.trim() || !task.contactVerified) return
      const facts = state?.facts.filter(fact => fact.confirmed &&
        (fact.scope === 'COMMON' || fact.scope === 'HK' || fact.scope.toLowerCase() === 'site:www.offertoday.com')) ?? []
      if (!window.confirm(`批准 OfferToday「${task.company} ${task.title}」？\n\n首条消息（完整原文，不自动改写）：\n${task.initialMessage}\n\n已审核附件：${resume.label} · ${resume.filename}\nCV SHA256：${resume.sha256}\n当前适用的已确认资料（批准时由服务端重新读取并冻结）：\n${facts.length ? facts.map(fact => `${fact.label}：${fact.value}`).join('\n') : '无'}\n\n已核对平台实际联系方式、最新偏好及当前线上资料。批准只冻结材料，不联系、不上传；材料变动须重新授权。`)) return
    } else if (!window.confirm(`批准「${task.company} ${task.title}」？\n简历：${resume.label}（SHA256 ${resume.sha256}）\n服务端将固定此岗位、已审核简历和当前已确认的适用资料快照。材料变动须重新授权。`)) return
    void act('批准任务', () => workbenchJson<Task>(`/tasks/${encodeURIComponent(task.id)}/approve`, 'POST'))
  }

  function startTask(task: Task) {
    const prompt = task.channel === 'OFFERTODAY'
      ? `确定启动 OfferToday「${task.title}」？使用零模型脚本、当前已授权 Chrome 连接和本次批准的附件；启动不等于送达。未连接或控件不确定会等待人工，不会自动重复联系。`
      : `确定启动「${task.title}」？将把本次批准的资料/简历交给已配置的外部 AI 执行器，其模型可能是外部服务并消耗额度。启动不等于已投递。`
    if (!window.confirm(prompt)) return
    void act('启动任务', () => workbenchJson<Task>(`/tasks/${encodeURIComponent(task.id)}/start`, 'POST'))
  }

  function checkStatus(task: Task) {
    if (!window.confirm('仅查询此岗位的最新招聘状态，不提交申请、不修改官网资料。是否启动 AI 查询？')) return
    void act('查询招聘进度', async () => {
      const query = await workbenchJson<Task>(`/tasks/${encodeURIComponent(task.id)}/check-status`, 'POST')
      selectTask(query.id)
    })
  }

  async function loadContext(task: Task) {
    if (working || task.state !== 'APPROVED') return
    setWorking('读取上下文')
    setActionError('')
    try {
      const response = await workbenchGet<{ text: string }>(`/tasks/${encodeURIComponent(task.id)}/context`)
      if (task.id === selectedId) setContext({ taskId: task.id, updatedAt: task.updatedAt, text: response.text })
    } catch (error) {
      setActionError(errorText(error))
    } finally {
      setWorking('')
    }
  }

  async function copyContext() {
    if (approvedContext === null) return
    try {
      await navigator.clipboard.writeText(approvedContext)
      setNotice('已复制批准快照；这只是交接材料，不代表已执行或已提交。')
    } catch {
      setActionError('复制失败，请手动选中文本复制。')
    }
  }

  function reportEvent(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!selected || !chosenEventType || !eventMessage.trim()) return
    if (['SUBMITTED', 'EMPLOYER_STATUS', 'CONTACTED', 'CV_DELIVERED'].includes(chosenEventType) && !eventEvidence.trim()) {
      setActionError('提交回执或人工核对必须填写可核对的证据。')
      return
    }
    if (receiptEvent && (!offerTodaySelected || selected.state !== 'UNKNOWN' || !selected.platformJobId ||
      !selected.attemptId || !selected.snapshot?.initialMessageSha256 || !selected.snapshot.resume?.sha256 ||
      !conversationId.trim() || !messageId.trim())) {
      setActionError('OfferToday 回执缺少冻结岗位/尝试/附件信息，或尚未填写会话 ID 与消息 ID；不能记录送达。')
      return
    }
    if (chosenEventType === 'SUBMITTING' && !window.confirm('提交前登记后，任务将变为结果未知；即使中断也不能自动重试。确认已到实际提交前？')) return
    if (chosenEventType === 'SUBMITTED' && !window.confirm('确认回执确实对应此岗位的这次提交？没有明确回执请保持结果未知。')) return
    if (receiptEvent && !window.confirm(chosenEventType === 'CONTACTED'
      ? '确认此岗位此次尝试的首条消息有新鲜会话回执？仅记录已联系，绝不表示简历已送达。'
      : '确认此岗位此次尝试的简历附件已送达同一会话，且与批准的 CV SHA256 一致？选择文件或点击发送不算回执。')) return
    const id = selected.id
    const evidence = receiptEvent && selected.snapshot ? JSON.stringify({
      jobId: selected.platformJobId, attemptId: selected.attemptId,
      conversationId: conversationId.trim(), messageId: messageId.trim(), proof: eventEvidence.trim(),
      ...(chosenEventType === 'CONTACTED'
        ? { initialMessageSha256: selected.snapshot.initialMessageSha256 }
        : { resumeSha256: selected.snapshot.resume.sha256 }),
    }) : eventEvidence.trim()
    void act('记录人工报告', () => workbenchJson<Task>(`/tasks/${encodeURIComponent(id)}/events`, 'POST', {
      type: chosenEventType, message: eventMessage.trim(), ...(evidence ? { evidence } : {}),
    }), () => { setEventMessage(''); setEventEvidence(''); setConversationId(''); setMessageId(''); setEventType('PROGRESS') })
  }

  function submitFact(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const host = siteHost.trim().toLowerCase()
    if (scopeType === 'SITE' && (!host || !/^[a-z0-9.-]+$/.test(host) || host.startsWith('.') || host.endsWith('.'))) {
      setActionError('网站作用域请填写主机名，例如 careers.example.com。')
      return
    }
    const scope: Fact['scope'] = scopeType === 'SITE' ? `SITE:${host}` : scopeType
    void act('保存资料', () => workbenchJson<Fact>('/facts', 'POST', {
      ...factForm, key: factForm.key.trim(), label: factForm.label.trim(), value: factForm.value.trim(), scope,
    }), () => { setFactForm(blankFact); setScopeType('COMMON'); setSiteHost('') })
  }

  function editFact(fact: Fact) {
    setFactForm({ id: fact.id, key: fact.key, label: fact.label, value: fact.value,
      scope: fact.scope, confirmed: fact.confirmed, source: fact.source })
    setScopeType(fact.scope.startsWith('SITE:') ? 'SITE' : fact.scope as 'COMMON' | 'HK' | 'MAINLAND')
    setSiteHost(fact.scope.startsWith('SITE:') ? fact.scope.slice(5) : '')
  }

  function deleteFact(fact: Fact) {
    if (!window.confirm(`删除「${fact.label}」？已批准任务的服务端快照不会因此改变。`)) return
    void act('删除资料', () => workbenchJson<void>(`/facts/${encodeURIComponent(fact.id)}`, 'DELETE'))
  }

  function previewImport() {
    void act('获取导入预览', async () => {
      const response = await workbenchJson<{ facts: Fact[] }>('/import/preview', 'POST')
      setPreview(response.facts)
      setImportSelected([])
    })
  }

  function saveImport() {
    if (!preview || !importSelected.length) return
    const selectedFacts = importSelected.map(index => ({ fact: preview[index], index }))
    const known = new Set(state?.facts.map(fact => `${fact.scope}\u0000${fact.key}`))
    void act('保存选中的待核实资料', async () => {
      for (const { fact, index } of selectedFacts) {
        const key = `${fact.scope}\u0000${fact.key}`
        if (known.has(key)) {
          setImportSelected(current => current.filter(item => item !== index))
          continue
        }
        // 预览不自动持久化、不沿用来源数据的确认标志或 ID。
        await workbenchJson<Fact>('/facts', 'POST', {
          key: fact.key, label: fact.label, value: fact.value, scope: fact.scope,
          confirmed: false, source: fact.source || 'JobsDB 导入预览',
        } satisfies FactInput)
        known.add(key)
        setImportSelected(current => current.filter(item => item !== index))
      }
      setPreview(null)
      setImportSelected([])
    })
  }

  function submitResume(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!resumeFile && !resumeForm.content.trim()) {
      setActionError('请至少选择文件或填写简历正文。')
      return
    }
    const data = new FormData()
    data.set('label', resumeForm.label.trim())
    data.set('region', resumeForm.region)
    data.set('language', resumeForm.language)
    data.set('content', resumeForm.content)
    if (resumeForm.parentId) data.set('parentId', resumeForm.parentId)
    if (resumeForm.jobUrl.trim()) data.set('jobUrl', resumeForm.jobUrl.trim())
    if (resumeFile) data.set('file', resumeFile)
    void act('新增简历版本', () => workbenchUpload(data), () => {
      setResumeForm(blankResume)
      setResumeFile(null)
      if (fileInput.current) fileInput.current.value = ''
    })
  }

  function baseResume(resume: Resume) {
    setResumeForm({ label: `${resume.label} · 岗位版`, region: resume.region, language: resume.language,
      content: '', parentId: resume.id, jobUrl: '' })
    setResumeFile(null)
    if (fileInput.current) fileInput.current.value = ''
    resumeFormNode.current?.scrollIntoView({ behavior: 'smooth', block: 'start' })
  }

  function approveResume(resume: Resume) {
    if (!window.confirm(`确认已审核「${resume.label}」的内容/附件？批准只作用于此不可变版本，不同步其他地区或语言。`)) return
    void act('批准简历', () => workbenchJson<Resume>(`/resumes/${encodeURIComponent(resume.id)}/approve`, 'POST'))
  }

  async function downloadResume(resume: Resume) {
    if (working) return
    setWorking('下载简历')
    setActionError('')
    try {
      const blob = await workbenchFile(resume.id)
      const url = URL.createObjectURL(blob)
      const anchor = document.createElement('a')
      anchor.href = url
      anchor.download = resume.filename || `${resume.label}.txt`
      document.body.appendChild(anchor)
      anchor.click()
      anchor.remove()
      window.setTimeout(() => URL.revokeObjectURL(url), 1000)
    } catch (error) {
      setActionError(errorText(error))
    } finally {
      setWorking('')
    }
  }

  return (
    <div className="space-y-5 pb-12">
      <PageHeader icon={<BiBriefcase />} title="求职工作台" subtitle="资料先核实，简历按版本审核，岗位单次授权；结果以证据为准。"
        actions={<Button variant="outline" size="sm" onClick={() => void refresh()}><BiRefresh />刷新</Button>} />

      {loadError && <p role="alert" className="rounded-xl border border-rose-300 bg-rose-50 p-3 text-sm text-rose-800 dark:bg-rose-950 dark:text-rose-200">读取状态失败：{loadError}。{state ? '下方为上次获取的数据。' : '尚未获取真实数据。'}</p>}
      {actionError && <p role="alert" className="rounded-xl border border-rose-300 bg-rose-50 p-3 text-sm text-rose-800 dark:bg-rose-950 dark:text-rose-200">{actionError}</p>}
      {notice && <p role="status" className="rounded-xl border border-emerald-300 bg-emerald-50 p-3 text-sm text-emerald-900 dark:bg-emerald-950 dark:text-emerald-200">{notice}</p>}
      {loading && <p role="status" className={small}>正在读取本地工作台状态…</p>}
      {!state ? (!loading && <p className={small}>服务不可用时不会显示虚构的投递进展，请启动后端后刷新。</p>) : <>
        <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
          <div className={panel}><p className={small}>任务总数</p><p className="mt-1 text-3xl font-bold">{state.tasks.length}</p><p className={small}>已提交有回执 {state.tasks.filter(task => task.state === 'SUBMITTED' && (task.channel !== 'OFFERTODAY' || !!task.cvReceipt)).length}</p></div>
          <div className={panel}><p className={small}>待关注</p><p className="mt-1 text-3xl font-bold text-amber-700 dark:text-amber-300">{incidents.length}</p><p className={small}>失败 · 人工处理 · 结果未知</p></div>
          <div className={panel}><p className={small}>已确认资料</p><p className="mt-1 text-3xl font-bold">{state.facts.filter(fact => fact.confirmed).length}</p><p className={small}>未确认 {state.facts.filter(fact => !fact.confirmed).length} 条，不用于授权快照</p></div>
          <div className={panel}><p className={small}>执行器</p><p className="mt-2 font-semibold">官网/简历 AI：{!state.runtime.agentConfigured ? '未配置' : state.runtime.busy ? '忙碌中' : '已配置'}</p><p className={small}>OfferToday 零模型脚本：{state.runtime.offerTodayConfigured ? '已配置' : '未配置'} · {state.runtime.busy ? '执行器忙碌' : state.runtime.offerTodayConfigured ? '可显式启动' : '不可启动'}；连接在启动时核验</p></div>
        </div>
        <div className="flex flex-wrap items-center justify-between gap-3 text-sm text-slate-600 dark:text-slate-300">
          <span>最后读取：{updatedAt || '—'} · 约每 3 秒同步执行状态；后台需开启通知，浏览器可能节流；关闭页面不推送</span>
          <Button variant="outline" size="sm" onClick={() => void enableNotifications()}><BiBell />{notifications ? '关闭本页桌面通知' : '自愿开启桌面通知'}</Button>
        </div>
        {(incidents.length > 0 || newAlerts.length > 0) && <section className="rounded-xl border border-amber-300 bg-amber-50 p-4 dark:border-amber-800 dark:bg-amber-950" aria-label="待处理提醒">
          <h2 className="font-semibold text-amber-900 dark:text-amber-200">待处理提醒</h2>
          {newAlerts.length > 0 && <div className="mt-2 text-sm text-amber-900 dark:text-amber-200"><p>本次打开页面后的新状态：</p><ul className="list-inside list-disc">{newAlerts.map((item, index) => <li key={`${index}-${item}`}>{item}</li>)}</ul><button type="button" className="underline" onClick={() => setNewAlerts([])}>清除新提醒</button></div>}
          <div className="mt-2 flex flex-wrap gap-2">{incidents.map(task => <button key={task.id} type="button" onClick={() => { selectTask(task.id); setResumeFile(null); setTab('tasks'); window.requestAnimationFrame(() => taskDetailNode.current?.scrollIntoView({ behavior: 'smooth', block: 'start' })) }} className="rounded-lg border border-amber-300 px-3 py-1 text-left text-sm text-amber-900 hover:bg-amber-100 dark:text-amber-200 dark:hover:bg-amber-900">{task.title} · {taskStateName(task)}</button>)}</div>
        </section>}

        <Tabs value={tab} onValueChange={value => { if (value !== 'resumes') setResumeFile(null); setTab(value) }} className="space-y-4">
          <TabsList className="h-auto flex-wrap gap-1"><TabsTrigger value="tasks">任务与进展</TabsTrigger><TabsTrigger value="resumes">简历版本</TabsTrigger><TabsTrigger value="facts">个人资料</TabsTrigger><TabsTrigger value="platforms">平台入口</TabsTrigger></TabsList>
          <TabsContent value="tasks" className="space-y-4">
            <section ref={newTaskNode} className={panel} aria-labelledby="new-task-title">
              <h2 id="new-task-title" className="text-lg font-semibold">新建任务</h2>
              <p className={`${small} mt-1`}>创建只记录待办；批准固定材料，启动需单独操作。OfferToday HK 在此逐岗审核并启动；Boss/JobsDB 仍走原入口。</p>
              <form onSubmit={submitTask} className="mt-4 grid gap-4 md:grid-cols-2">
                <label className={small}>任务类型<select className={`${control} mt-1`} value={taskForm.kind} onChange={event => setTaskForm({ ...taskForm, kind: event.target.value as TaskKind, channel: event.target.value === 'APPLICATION' ? taskForm.channel : 'OFFICIAL', resumeId: undefined, initialMessage: undefined, contactVerified: false })}><option value="APPLICATION">岗位申请</option><option value="RESUME">外部 AI 简历草稿</option><option value="GAP">项目补强卡片（仅记录）</option></select></label>
                {taskForm.kind === 'APPLICATION' ? <label className={small}>渠道<select className={`${control} mt-1`} value={taskForm.channel} onChange={event => { const channel = event.target.value as Channel; setTaskForm({ ...taskForm, channel, region: channel === 'OFFERTODAY' ? 'HK' : taskForm.region, resumeId: undefined, initialMessage: channel === 'OFFERTODAY' ? '' : undefined, contactVerified: false }) }}><option value="OFFICIAL">公司官网</option><option value="JOBSDB">JobsDB（原 CLI）</option><option value="BOSS">Boss（原页面）</option><option value="OFFERTODAY">OfferToday HK（逐岗授权）</option></select></label> : <p className={`${small} self-end pb-2`}>{taskForm.kind === 'GAP' ? '此卡片仅记录，不涉及投递渠道。' : '简历草稿由配置的外部执行器处理。'}</p>}
                <label className={small}>地区{offerTodayForm ? '（OfferToday 仅香港）' : ''}<select className={`${control} mt-1`} value={taskForm.region} disabled={offerTodayForm} onChange={event => setTaskForm({ ...taskForm, region: event.target.value as Region, resumeId: undefined })}><option value="HK">香港</option><option value="MAINLAND">内地</option></select></label>
                <label className={small}>语言<select className={`${control} mt-1`} value={taskForm.language} onChange={event => setTaskForm({ ...taskForm, language: event.target.value as Language, resumeId: undefined })}><option value="zh">中文</option><option value="en">英文</option></select></label>
                <label className={small}>方向<select className={`${control} mt-1`} value={taskForm.direction} onChange={event => setTaskForm({ ...taskForm, direction: event.target.value as TaskInput['direction'] })}><option value="AI_APPLICATION">AI 应用</option><option value="ML">机器学习</option><option value="EMBODIED">具身智能</option></select></label>
                <label className={small}>公司 / 项目名<input className={`${control} mt-1`} value={taskForm.company} onChange={event => setTaskForm({ ...taskForm, company: event.target.value })} required={taskForm.kind === 'APPLICATION'} /></label>
                <label className={small}>岗位 / 卡片标题<input className={`${control} mt-1`} value={taskForm.title} onChange={event => setTaskForm({ ...taskForm, title: event.target.value })} required /></label>
                <label className={small}>岗位链接<input className={`${control} mt-1`} type="url" value={taskForm.url} onChange={event => setTaskForm({ ...taskForm, url: event.target.value })} required={taskForm.kind === 'APPLICATION'} placeholder="https://…" /></label>
                <label className={`${small} md:col-span-2`}>岗位描述 / 差距<textarea className={`${control} mt-1 min-h-20`} required={taskForm.kind === 'GAP'} value={taskForm.description} onChange={event => setTaskForm({ ...taskForm, description: event.target.value })} /></label>
                {offerTodayForm && <><label className={`${small} md:col-span-2`}>首条消息（精确原文，默认空白）<textarea className={`${control} mt-1 min-h-28`} required value={taskForm.initialMessage || ''} onChange={event => setTaskForm({ ...taskForm, initialMessage: event.target.value })} placeholder="请亲自撰写此岗位的首条消息；不会调用模型改写" /></label><label className={`${small} flex items-start gap-2 md:col-span-2`}><input type="checkbox" className="mt-1" required checked={!!taskForm.contactVerified} onChange={event => setTaskForm({ ...taskForm, contactVerified: event.target.checked })} />我已核对OfferToday实际联系方式与最新偏好一致，并核对当前线上资料</label></>}
                {taskForm.kind !== 'GAP' ? <label className={`${small} md:col-span-2`}>{offerTodayForm ? '已审核香港 PDF/DOCX 附件（仅所选语言）' : '已审核匹配简历'}<select className={`${control} mt-1`} required={offerTodayForm} value={taskForm.resumeId || ''} onChange={event => setTaskForm({ ...taskForm, resumeId: event.target.value || undefined })}><option value="">暂不选择（无法批准此任务）</option>{matchingResumes.map(resume => <option key={resume.id} value={resume.id}>{resume.label} · {regionName[resume.region]}{languageName[resume.language]} · {resume.filename} · {resume.id}</option>)}</select><span className="mt-1 block text-xs">{offerTodayForm ? '文本草稿不能冒充附件；中文和英文版本独立审核，不自动改写或使用站内旧附件。' : '批准要求已审核且地区、语言一致；如建任务后才新增简历，需另建任务选定版本。'}</span></label> : <>
                  <label className={small}>项目路径建议<input className={`${control} mt-1`} value={taskForm.projectPath || ''} onChange={event => setTaskForm({ ...taskForm, projectPath: event.target.value })} placeholder="仅建议，不执行编码" /></label>
                  <label className={small}>补强计划<textarea className={`${control} mt-1 min-h-20`} required value={taskForm.projectPlan || ''} onChange={event => setTaskForm({ ...taskForm, projectPlan: event.target.value })} /></label>
                </>}
                <label className={`${small} md:col-span-2`}>备注<input className={`${control} mt-1`} value={taskForm.note} onChange={event => setTaskForm({ ...taskForm, note: event.target.value })} /></label>
                {taskForm.kind === 'RESUME' && <p className={`${small} md:col-span-2`}>写入任务备注的固定要求：{resumeInstruction} 执行器返回草稿后另存未批准版本，仍需人工审核。</p>}
                {taskForm.kind === 'GAP' && <p className={`${small} md:col-span-2`}>项目补强卡片只保留需求、计划与路径建议，不可批准、启动或自动编码。</p>}
                <div className="md:col-span-2"><Button type="submit" disabled={!!working}><BiPlus />{working === '创建任务' ? '创建中…' : '记录新任务'}</Button></div>
              </form>
            </section>

            <div className="grid items-start gap-4 lg:grid-cols-[minmax(220px,1fr)_minmax(0,2fr)]">
              <section className={panel} aria-label="任务列表"><h2 className="mb-3 text-lg font-semibold">任务账本</h2>{state.tasks.length === 0 ? <p className={small}>尚无任务。先建立岗位或补强卡片。</p> : <div className="max-h-[38rem] space-y-2 overflow-y-auto">{[...state.tasks].sort((a, b) => b.updatedAt.localeCompare(a.updatedAt)).map(task => <button type="button" key={task.id} onClick={() => selectTask(task.id)} aria-pressed={task.id === selectedId} className={`w-full rounded-xl border p-3 text-left focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500 ${task.id === selectedId ? 'border-indigo-500 bg-indigo-50 dark:bg-indigo-950' : 'border-slate-200 hover:bg-slate-50 dark:border-slate-700 dark:hover:bg-slate-800'}`}><span className="block font-medium">{task.title}</span><span className={`${small} block`}>{task.company || kindName[task.kind]} · {task.kind === 'GAP' ? '仅记录' : task.kind === 'RESUME' ? '外部 AI' : channelName[task.channel]}</span><span className="mt-1 block text-xs font-semibold text-indigo-700 dark:text-indigo-300">{taskStateName(task)} · {date(task.updatedAt)}</span></button>)}</div>}</section>
              <section ref={taskDetailNode} className={panel} aria-label="任务详情">{!selected ? <p className={small}>选择任务查看批准、进展与证据。</p> : <>
                <div className="flex flex-wrap items-start justify-between gap-2"><div><h2 className="text-xl font-semibold">{selected.title}</h2><p className={small}>{selected.company || '未指定公司'} · {kindName[selected.kind]} · {selected.kind === 'GAP' ? '仅记录' : selected.kind === 'RESUME' ? '外部 AI' : channelName[selected.channel]} · {regionName[selected.region]}{languageName[selected.language]}</p></div><span className="rounded-full bg-indigo-100 px-3 py-1 text-sm font-semibold text-indigo-800 dark:bg-indigo-950 dark:text-indigo-200">{taskStateName(selected)}</span></div>
                <p className={`${small} mt-3 break-all`}>创建 {date(selected.createdAt)} · 更新 {date(selected.updatedAt)} · 方向 {selected.direction}</p>
                {selected.url && /^https?:\/\//i.test(selected.url) && <a className="mt-2 inline-block text-sm text-indigo-700 underline dark:text-indigo-300" href={selected.url} target="_blank" rel="noopener noreferrer">打开岗位链接 ↗</a>}
                {selected.description && <p className="mt-3 whitespace-pre-wrap text-sm">{selected.description}</p>}
                {selected.note && <p className={`${small} mt-2 whitespace-pre-wrap`}>备注：{selected.note}</p>}
                {selected.kind === 'GAP' ? <div className="mt-4 rounded-xl bg-slate-50 p-3 text-sm dark:bg-slate-800"><p>待补强（只记录，不执行）</p><p className="mt-1 break-all">路径建议：{selected.projectPath || '未填写'}</p><p className="mt-1 whitespace-pre-wrap">计划：{selected.projectPlan || '未填写'}</p></div> : <>
                  <p className={`${small} mt-4`}>{selected.kind === 'CHECK' ? '只读查询：不交付简历附件，不允许申请或修改官网资料。' : `所选简历：${selectedResume ? `${selectedResume.label} · ${selectedResume.approved ? '已审核' : '未审核'} · ${selectedResume.sha256}` : '未选择；此任务无法批准，需创建新任务绑定已审核版本'}`}</p>
                  {selected.kind === 'RESUME' && <p className={`${small} mt-2`}>外部 AI 返回草稿后另存新版本，原简历不变；新版本仍需人工审核。不自动导出 PDF。</p>}
                  {offerTodaySelected && <div className="mt-3 rounded-xl border border-slate-200 p-3 text-sm dark:border-slate-700"><p className="font-semibold">OfferToday · {selected.snapshot ? '已批准冻结的首条消息与附件' : '待批准的首条消息'}</p><pre className="mt-2 whitespace-pre-wrap break-words">{selected.snapshot ? selected.snapshot.initialMessage : selected.initialMessage || '未填写'}</pre>{selected.snapshot && <><p className={`${small} mt-2 break-all`}>消息 SHA256：{selected.snapshot.initialMessageSha256} · 附件 SHA256：{selected.snapshot.resume?.sha256 || '缺失'}</p><p className={small}>已冻结适用事实：{selected.snapshot.facts?.length ?? 0} 条；联系方式核对：{selected.snapshot.contactVerified ? '已确认' : '未确认'}</p></>}<p className={`${small} mt-2`}>已联系：{selected.contactReceipt ? '已登记本岗联系回执' : '无回执'} · 简历已送达：{selected.cvReceipt ? '已登记附件送达回执' : '无回执'}。工作台校验回执归属与材料哈希，真实发送证据由执行器或人工提供；点击或脚本退出不算送达。</p></div>}
                  {selected.channel !== 'OFFICIAL' && selected.channel !== 'OFFERTODAY' && selected.kind === 'APPLICATION' && <p className="mt-2 rounded-lg bg-amber-50 p-3 text-sm text-amber-900 dark:bg-amber-950 dark:text-amber-200">平台投递不支持从工作台 HTTP 直接启动。请走原平台入口；复制已批准上下文仅供交接，不能视为已执行。</p>}
                  {selected.state === 'UNKNOWN' && <p className="mt-2 rounded-lg bg-rose-50 p-3 text-sm text-rose-900 dark:bg-rose-950 dark:text-rose-200">{offerTodaySelected ? '结果未知：禁止自动重跑或再次联系。仅有同岗、同次尝试的联系回执不代表简历送达；附件回执须匹配同一会话与冻结 SHA256。' : '结果未知：不可重新批准或重投。只在找到明确、对应此岗位的回执后记录已提交。'}</p>}
                  {offerTodaySelected && selected.state === 'NEEDS_INPUT' && <p className="mt-2 rounded-lg bg-amber-50 p-3 text-sm text-amber-900 dark:bg-amber-950 dark:text-amber-200">遇到人工阻塞：脚本不会自动继续或重新联系；请核对实际页面状态，不要把点击/附件选择当作送达。</p>}
                  <div className="mt-4 flex flex-wrap gap-2"><Button type="button" size="sm" disabled={!canApprove || !!working} onClick={() => approveTask(selected)}>批准岗位与材料快照</Button><Button type="button" variant="outline" size="sm" disabled={!canStart || !!working} onClick={() => startTask(selected)}>{offerTodaySelected ? '启动 OfferToday 零模型脚本' : '启动外部执行器'}</Button>{selected.state === 'APPROVED' && <Button type="button" variant="outline" size="sm" disabled={!!working} onClick={() => void loadContext(selected)}>读取批准上下文</Button>}</div>
                  {offerTodaySelected ? !state.runtime.offerTodayConfigured && <p className={`${small} mt-2`}>OfferToday 脚本未配置，无法启动；与官网 AI 配置无关，批准不代表执行。</p> : !state.runtime.agentConfigured && <p className={`${small} mt-2`}>未配置外部执行器，官网/简历任务不能启动；批准不代表执行。</p>}
                  {selected.state === 'APPROVED' && state.runtime.busy && <p className={`${small} mt-2`}>执行器正在处理其他任务，请等待空闲。</p>}
                  {approvedContext !== null && <div className="mt-4"><label className={small} htmlFor="approved-context">批准范围的上下文（不是执行或提交证明）</label><textarea id="approved-context" readOnly className={`${control} mt-1 min-h-36 font-mono`} value={approvedContext} /><Button type="button" variant="outline" size="sm" className="mt-2" onClick={() => void copyContext()}><BiCopy />复制上下文</Button></div>}
                </>}
                {selected.kind === 'APPLICATION' && (offerTodaySelected ? <div className="mt-4 rounded-xl border border-slate-200 p-3 text-sm dark:border-slate-700"><p className="font-semibold">OfferToday 招聘状态</p><p className={small}>本版无可靠官网查询证据，不支持只读 AI 状态查询；不自动监控，也不借用官网执行器。</p></div> : <div className="mt-4 rounded-xl border border-slate-200 p-3 text-sm dark:border-slate-700"><p className="font-semibold">雇主招聘状态（仅主动核对）</p><p>{selected.employerStatus || '未核对'} · 上次核对：{date(selected.employerCheckedAt)}</p><p className={small}>企业拒绝不等于申请执行失败；没有自动/24 小时监控。</p>{['SUBMITTED', 'UNKNOWN'].includes(selected.state) && <Button type="button" size="sm" variant="outline" className="mt-2" disabled={!!working || !state.runtime.agentConfigured || state.runtime.busy} onClick={() => checkStatus(selected)}>检查最新进度（只读 AI 任务）</Button>}</div>)}
                <div className="mt-5 border-t border-slate-200 pt-4 dark:border-slate-700"><h3 className="font-semibold">当前步骤与事件</h3><p className={`${small} mt-1`}>{lastExecutionEvent ? `${eventName[lastExecutionEvent.type]}：${lastExecutionEvent.message}` : '暂无执行报告；任务状态不代表浏览器实时画面。'}</p><ol className="mt-3 max-h-72 space-y-3 overflow-y-auto">{selected.events.map(item => <li key={item.id} className="rounded-lg border-l-2 border-indigo-400 bg-slate-50 px-3 py-2 text-sm dark:bg-slate-800"><p className="font-medium">{eventName[item.type] || item.type} · {date(item.at)}</p><p className="whitespace-pre-wrap">{item.message}</p>{item.evidence && !offerTodaySelected && <p className="mt-1 break-all whitespace-pre-wrap text-emerald-800 dark:text-emerald-300">证据 / 回执：{item.evidence}</p>}<p className="text-xs text-slate-500 dark:text-slate-400">报告来源：{item.source || '未标注'}</p></li>)}</ol></div>
                {eventOptions.length > 0 && <form onSubmit={reportEvent} className="mt-5 space-y-3 rounded-xl border border-slate-200 p-3 dark:border-slate-700">
                  <h3 className="font-semibold">人工报告 / 核对记录</h3>
                  <p className={small}>{offerTodaySelected ? '仅记录实际核实的同岗回执：联系与附件送达分开验证；UNKNOWN 不重投。不要粘贴私密会话历史。' : '以下记录由你手动填写，不是执行器验证。手动交接请先记录进展，提交前再登记占位。雇主状态只在主动检查后更新。'}</p>
                  <label className={`${small} block`}>事件类型<select className={`${control} mt-1`} value={chosenEventType} onChange={event => setEventType(event.target.value as EventType)}>{eventOptions.map(type => <option key={type} value={type}>{eventName[type]}</option>)}</select></label>
                  <label className={`${small} block`}>{chosenEventType === 'EMPLOYER_STATUS' ? '人工查到的招聘状态' : '实际观察 / 需要处理的事项'}<textarea className={`${control} mt-1 min-h-20`} required value={eventMessage} onChange={event => setEventMessage(event.target.value)} /></label>
                  {receiptEvent && <><label className={`${small} block`}>会话 ID（{chosenEventType === 'CV_DELIVERED' ? '须与联系回执相同' : '本次联系回执'}）<input className={`${control} mt-1`} required value={conversationId} onChange={event => setConversationId(event.target.value)} /></label><label className={`${small} block`}>消息 ID（此次操作的新回执）<input className={`${control} mt-1`} required value={messageId} onChange={event => setMessageId(event.target.value)} /></label></>}
                  <label className={`${small} block`}>证据 / 回执{['SUBMITTED', 'EMPLOYER_STATUS', 'CONTACTED', 'CV_DELIVERED'].includes(chosenEventType) ? '（必填）' : '（可选）'}<input className={`${control} mt-1`} required={['SUBMITTED', 'EMPLOYER_STATUS', 'CONTACTED', 'CV_DELIVERED'].includes(chosenEventType)} value={eventEvidence} onChange={event => setEventEvidence(event.target.value)} placeholder={receiptEvent ? '可核对的回执线索；不粘贴会话全文或私密历史' : '回执编号、确认页面或人工核对来源'} /></label>
                  <Button type="submit" size="sm" disabled={!!working}>记录人工报告</Button>
                </form>}
              </>}</section>
            </div>
          </TabsContent>

          <TabsContent value="resumes" className="space-y-4">
            <section className={panel}><h2 className="text-lg font-semibold">新增简历版本</h2><p className={`${small} mt-1`}>文件或正文至少一项；每次保存都是新版本，审核前不能用于批准任务。不同地区、语言不自动同步或翻译。</p>
              <form ref={resumeFormNode} onSubmit={submitResume} className="mt-4 grid gap-4 md:grid-cols-2"><label className={small}>版本名称<input className={`${control} mt-1`} required value={resumeForm.label} onChange={event => setResumeForm({ ...resumeForm, label: event.target.value })} /></label>
                <label className={small}>父版本（可选）<select className={`${control} mt-1`} value={resumeForm.parentId} onChange={event => setResumeForm({ ...resumeForm, parentId: event.target.value })}><option value="">独立新版本</option>{state.resumes.map(resume => <option key={resume.id} value={resume.id}>{resume.label} · {resume.id}</option>)}</select></label>
                <label className={small}>地区<select className={`${control} mt-1`} value={resumeForm.region} onChange={event => setResumeForm({ ...resumeForm, region: event.target.value as Region })}><option value="HK">香港</option><option value="MAINLAND">内地</option></select></label>
                <label className={small}>语言<select className={`${control} mt-1`} value={resumeForm.language} onChange={event => setResumeForm({ ...resumeForm, language: event.target.value as Language })}><option value="zh">中文</option><option value="en">英文</option></select></label>
                <label className={`${small} md:col-span-2`}>目标岗位链接（可选）<input className={`${control} mt-1`} type="url" value={resumeForm.jobUrl} onChange={event => setResumeForm({ ...resumeForm, jobUrl: event.target.value })} placeholder="https://…" /></label>
                <label className={`${small} md:col-span-2`}>简历正文<textarea className={`${control} mt-1 min-h-36`} value={resumeForm.content} onChange={event => setResumeForm({ ...resumeForm, content: event.target.value })} placeholder="可与文件同时提交；不自动改写其他版本" /></label>
                <label className={`${small} md:col-span-2`}>上传文件（可替代正文）<input ref={fileInput} className={`${control} mt-1`} type="file" accept=".pdf,.docx,.txt,.md" onChange={event => setResumeFile(event.target.files?.[0] || null)} />{resumeFile && <span className="block text-xs">待上传：{resumeFile.name}</span>}</label>
                <div className="md:col-span-2"><Button type="submit" disabled={!!working}>保存为未批准版本</Button></div>
              </form></section>
            <section className={panel}><div className="flex flex-wrap items-center justify-between gap-3"><h2 className="text-lg font-semibold">版本库 · 四种地区/语言</h2><label className={small}>筛选 <select className={`${control} ml-2 inline-block w-auto`} value={resumeFilter} onChange={event => setResumeFilter(event.target.value)}><option value="all">全部</option><option value="HK:zh">香港 · 中文</option><option value="HK:en">香港 · 英文</option><option value="MAINLAND:zh">内地 · 中文</option><option value="MAINLAND:en">内地 · 英文</option></select></label></div>
              <div className="mt-4 space-y-3">{state.resumes.filter(resume => resumeFilter === 'all' || `${resume.region}:${resume.language}` === resumeFilter).map(resume => <article key={resume.id} className="rounded-xl border border-slate-200 p-4 dark:border-slate-700"><div className="flex flex-wrap justify-between gap-2"><div><h3 className="font-semibold">{resume.label} <span className="text-sm font-normal">· {regionName[resume.region]}{languageName[resume.language]}</span></h3><p className={`${small} mt-1 break-all`}>{resume.approved ? '已批准' : '待审核'} · {date(resume.createdAt)} · ID {resume.id}</p><p className={`${small} break-all`}>SHA256 {resume.sha256 || '—'} · 文件 {resume.filename || '无'} {resume.parentId ? `· 父版本 ${resume.parentId}` : ''}</p>{resume.jobUrl && <p className={`${small} break-all`}>岗位：{resume.jobUrl}</p>}</div><div className="flex flex-wrap items-start gap-2"><Button type="button" size="sm" variant="outline" onClick={() => baseResume(resume)}>以此创建岗位版</Button><Button type="button" size="sm" variant="outline" aria-label={`下载简历 ${resume.label}`} disabled={!!working} onClick={() => void downloadResume(resume)}><BiDownload />下载</Button>{!resume.approved && <Button type="button" size="sm" disabled={!!working} onClick={() => approveResume(resume)}>审核并批准</Button>}</div></div>{resume.content && <details className="mt-3"><summary className="cursor-pointer text-sm text-indigo-700 dark:text-indigo-300">查看此版正文以人工核对</summary><pre className="mt-2 max-h-80 overflow-auto whitespace-pre-wrap break-words rounded-lg bg-slate-50 p-3 text-sm dark:bg-slate-800">{resume.content}</pre></details>}</article>)}{state.resumes.filter(resume => resumeFilter === 'all' || `${resume.region}:${resume.language}` === resumeFilter).length === 0 && <p className={small}>该分类暂无真实简历版本。</p>}</div>
            </section>
          </TabsContent>

          <TabsContent value="facts" className="space-y-4">
            <section className={panel}><h2 className="text-lg font-semibold">个人资料 · 按作用域保存</h2><p className={`${small} mt-1`}>仅确认过的资料参与岗位批准快照。不要填写密码、验证码、浏览器会话；旧资料先预览再逐条核实。</p>
              <form onSubmit={submitFact} className="mt-4 grid gap-4 md:grid-cols-2"><label className={small}>资料键<input className={`${control} mt-1`} required value={factForm.key} onChange={event => setFactForm({ ...factForm, key: event.target.value })} placeholder="例如 work_authorization" /></label><label className={small}>显示名称<input className={`${control} mt-1`} required value={factForm.label} onChange={event => setFactForm({ ...factForm, label: event.target.value })} /></label><label className={small}>作用域<select className={`${control} mt-1`} value={scopeType} onChange={event => setScopeType(event.target.value as typeof scopeType)}><option value="COMMON">通用</option><option value="HK">香港</option><option value="MAINLAND">内地</option><option value="SITE">指定网站</option></select></label>{scopeType === 'SITE' && <label className={small}>网站主机名<input className={`${control} mt-1`} required value={siteHost} onChange={event => setSiteHost(event.target.value)} placeholder="careers.example.com" /></label>}<label className={`${small} md:col-span-2`}>实际内容<textarea className={`${control} mt-1 min-h-20`} required value={factForm.value} onChange={event => setFactForm({ ...factForm, value: event.target.value })} /></label><label className={`${small} flex items-center gap-2 md:col-span-2`}><input type="checkbox" checked={factForm.confirmed} onChange={event => setFactForm({ ...factForm, confirmed: event.target.checked })} />我已核实这条资料，可以在匹配作用域用于后续授权</label><div className="flex flex-wrap gap-2 md:col-span-2"><Button type="submit" disabled={!!working}>{factForm.id ? '保存修改' : '新增资料'}</Button>{factForm.id && <Button type="button" variant="outline" onClick={() => { setFactForm(blankFact); setScopeType('COMMON'); setSiteHost('') }}>取消编辑</Button>}</div></form>
            </section>
            <section className={panel}><div className="flex flex-wrap items-center justify-between gap-2"><h2 className="text-lg font-semibold">已有资料</h2><Button type="button" size="sm" variant="outline" disabled={!!working} onClick={previewImport}>预览本地 JobsDB 已知资料</Button></div>{state.facts.length === 0 ? <p className={`${small} mt-3`}>暂无资料。</p> : <div className="mt-4 space-y-2">{state.facts.map(fact => <article key={fact.id} className="rounded-xl border border-slate-200 p-3 dark:border-slate-700"><div className="flex flex-wrap justify-between gap-2"><div className="min-w-0"><p className="font-medium">{fact.label} <span className={small}>({fact.key} · {fact.scope})</span></p><p className="break-words whitespace-pre-wrap text-sm">{fact.value}</p><p className={`${small} mt-1`}>{fact.confirmed ? '已确认' : '待核实，不进快照'} · 来源 {fact.source} · {date(fact.updatedAt)}</p></div><div className="flex gap-2"><Button type="button" size="sm" variant="outline" onClick={() => editFact(fact)}>编辑</Button><Button type="button" size="sm" variant="outline" disabled={!!working} onClick={() => deleteFact(fact)}>删除</Button></div></div></article>)}</div>}
              {preview !== null && <div className="mt-5 rounded-xl border border-indigo-200 bg-indigo-50 p-4 dark:border-indigo-800 dark:bg-indigo-950"><h3 className="font-semibold">导入预览 · 不会自动保存或确认</h3><p className={`${small} mt-1`}>只勾选要留待人工核实的条目；已存在相同作用域和键的资料请在上方直接编辑，避免覆盖已确认内容。</p><div className="mt-3 space-y-2">{preview.length === 0 && <p className={small}>没有可预览的已知资料。</p>}{preview.map((fact, index) => { const existing = state.facts.some(item => item.key === fact.key && item.scope === fact.scope); return <label key={`${fact.scope}-${fact.key}-${index}`} className="flex gap-2 rounded-lg bg-white p-2 text-sm dark:bg-slate-900"><input type="checkbox" disabled={existing} checked={importSelected.includes(index)} onChange={event => setImportSelected(current => event.target.checked ? [...current, index] : current.filter(item => item !== index))} /><span className="min-w-0 break-words"><strong>{fact.label}</strong> · {fact.scope} · {fact.value}<span className="block text-xs text-slate-500">来源 {fact.source} {existing ? '· 已存在，不覆盖' : '· 保存后仍待核实'}</span></span></label> })}</div><div className="mt-3 flex gap-2"><Button type="button" size="sm" disabled={!importSelected.length || !!working} onClick={saveImport}>保存选中项（未确认）</Button><Button type="button" size="sm" variant="outline" onClick={() => { setPreview(null); setImportSelected([]) }}>关闭预览</Button></div></div>}
            </section>
          </TabsContent>

          <TabsContent value="platforms" className="grid gap-4 lg:grid-cols-2">
            <section className={panel}><h2 className="text-lg font-semibold">Boss · 使用现有页面</h2><p className={`${small} mt-2`}>工作台不会通过 HTTP 触发批量投递；平台原有申请、会话与执行进度仍由现有入口管理。</p><Link href="/boss" className="mt-3 inline-block rounded-lg bg-indigo-600 px-4 py-2 text-sm font-medium text-white hover:bg-indigo-700">打开 Boss 页面 ↗</Link><p className={`${small} mt-2 break-all`}>后端提供的入口：{state.platforms.bossUrl || '未提供（使用 /boss）'}</p></section>
            <section className={panel}><h2 className="text-lg font-semibold">OfferToday HK · 逐岗申请</h2><p className={`${small} mt-2`}>使用已有的已授权 Chrome 连接；只在你点击时读取当前已加载职位（最多 40 条），不自动翻页、建任务、联系或上传附件。</p><div className="mt-3 flex flex-wrap gap-2"><a href={state.platforms.offerTodayUrl} target="_blank" rel="noopener noreferrer" className="inline-block rounded-lg bg-indigo-600 px-4 py-2 text-sm font-medium text-white hover:bg-indigo-700">打开港区平台 ↗</a><Button type="button" variant="outline" size="sm" disabled={!!working || !state.runtime.offerTodayConfigured} onClick={() => void discoverOfferToday()}>只读读取当前已加载职位</Button></div><p className={`${small} mt-2`}>{state.runtime.offerTodayConfigured ? '脚本已配置；浏览器连接仍需在读取/执行时校验。' : '脚本未配置，无法读取或启动；不依赖官网 AI。'}</p><p className={`${small} mt-2`}>脚本已接入已核对的原生聊天、附件选择与平台历史回执；尚未进行真实发送验收，不保证所有页面变体可执行。未知控件、账号变化或材料不符时停止，不删除旧附件、改默认简历或重复联系。工作台只校验回执归属与材料哈希，不把本地点击或退出码当送达。</p>
              {discovery && <div className="mt-3 space-y-2"><p className={small}>{discovery.connected === true ? '已连接当前已授权标签页；仅展示本次已加载职位。' : discovery.connected === false ? '当前未连接已授权标签页，不展示虚构职位。' : '连接状态未确认；以下仅为服务端返回的只读结果。'}{discovery.note ? ` ${discovery.note}` : ''}</p>{discovery.connected !== false && (discovery.jobs.length === 0 ? <p className={small}>本次没有可显示的已加载职位；不会自动翻页。</p> : <div className="max-h-72 space-y-2 overflow-y-auto">{discovery.jobs.map((job, index) => <div key={`${job.url}-${index}`} className="rounded-lg border border-slate-200 p-3 text-sm dark:border-slate-700"><p className="font-medium">{job.title}</p>{job.company && <p className={small}>{job.company}</p>}<p className={`${small} break-all`}>{job.url}</p><Button type="button" size="sm" variant="outline" className="mt-2" onClick={() => chooseOfferToday(job)}>预填待创建任务</Button></div>)}</div>)}</div>}
            </section>
            <section className={panel}><h2 className="text-lg font-semibold">JobsDB · 只读账本</h2><p className={`${small} mt-2`}>{state.platforms.jobsdbAvailable ? 'JobsDB CLI 运行环境存在；以下只读展示最近 100 条已有记录。' : '未检测到 JobsDB CLI 环境；已有账本仍可只读显示，不模拟记录。'}</p><p className={`${small} mt-2`}>如需自行检查，在本项目目录的终端手动运行只读命令（不会从此网页启动）：</p><code className="mt-2 block break-all rounded-lg bg-slate-100 p-3 text-xs dark:bg-slate-800">bash gradlew --no-daemon jobsdb --console=plain --args=&apos;history&apos;</code><p className={`${small} mt-2`}>搜索、准备和申请由原 CLI 流程完成；此页不运行 batch/apply，也不替代提交前人工核实联系方式。</p>{state.platforms.jobsdbError && <p role="alert" className="mt-2 text-sm text-rose-700 dark:text-rose-300">{state.platforms.jobsdbError}</p>}<div className="mt-3 max-h-64 space-y-2 overflow-y-auto">{state.platforms.jobsdbHistory.length === 0 ? <p className={small}>无可显示的 JobsDB 历史。</p> : state.platforms.jobsdbHistory.map((item, index) => <div key={`${item.id}-${index}`} className="rounded-lg border border-slate-200 p-2 text-sm dark:border-slate-700"><span className="font-medium">{item.id}</span> · {item.status} · {date(item.updated_at)}</div>)}</div></section>
          </TabsContent>
        </Tabs>
      </>}
    </div>
  )
}
