import { apiUrl } from '@/lib/api'

export type Region = 'HK' | 'MAINLAND'
export type Language = 'zh' | 'en'
export type TaskKind = 'APPLICATION' | 'RESUME' | 'GAP' | 'CHECK'
export type Channel = 'OFFICIAL' | 'JOBSDB' | 'BOSS' | 'OFFERTODAY'
export type Direction = 'AI_APPLICATION' | 'ML' | 'EMBODIED'
export type TaskState = 'PENDING' | 'APPROVED' | 'RUNNING' | 'NEEDS_INPUT' | 'FAILED' | 'UNKNOWN' | 'SUBMITTED' | 'COMPLETED'
export type EventType = 'PROGRESS' | 'NEEDS_INPUT' | 'FAILED' | 'SUBMITTING' | 'SUBMITTED' | 'EMPLOYER_STATUS' | 'CONTACTED' | 'CV_DELIVERED'

export interface Fact {
  id: string
  key: string
  label: string
  value: string
  scope: 'COMMON' | 'HK' | 'MAINLAND' | `SITE:${string}`
  confirmed: boolean
  source: string
  updatedAt: string
  version?: number
  sourceId?: string
  sourceSha256?: string
  quote?: string
  needsReview?: boolean
  evidenceKind?: 'LOCAL_PROJECT'
  projectId?: string
  attemptId?: string
  verificationId?: string
}

export interface Resume {
  id: string
  label: string
  region: Region
  language: Language
  parentId?: string
  jobUrl?: string
  content: string
  filename: string
  sha256: string
  contentSha256?: string
  approved: boolean
  createdAt: string
}

export interface TaskEvent {
  id: string
  type: EventType | 'APPROVED' | 'RUNNING' | 'PROCESS_ENDED' | 'RECOVERED' | 'DRAFT'
  message: string
  evidence?: string
  source: string
  at: string
}

export interface Task {
  id: string
  kind: TaskKind
  channel: Channel
  region: Region
  language: Language
  direction: Direction
  company: string
  title: string
  url: string
  description: string
  resumeId?: string
  initialMessage?: string
  contactVerified?: boolean
  platformJobId?: string
  attemptId?: string
  snapshot?: {
    resume: Resume
    initialMessage: string
    initialMessageSha256: string
    contactVerified: boolean
    facts: Fact[]
  }
  contactReceipt?: Record<string, unknown>
  cvReceipt?: Record<string, unknown>
  note: string
  projectPath?: string
  projectPlan?: string
  state: TaskState
  approvalSource?: 'SCOPE_POLICY'
  packageId?: string
  events: TaskEvent[]
  employerStatus?: string
  employerCheckedAt?: string
  createdAt: string
  updatedAt: string
}

export interface MaterialSource {
  id: string
  name: string
  scope: 'COMMON' | Region
  sha256: string
  version: number
  filtered?: boolean
}

export interface MaterialCandidate {
  id: string
  key: string
  label: string
  value: string
  quote: string
  sourceId: string
  sourceName: string
  sourceSha256: string
  scope: Fact['scope']
  status: 'NEEDS_REVIEW' | 'CHANGED' | 'REUSED' | 'DUPLICATE' | 'CONFLICT' | 'UNVERIFIED'
  previousValue?: string
}

export interface MaterialReview {
  id: string
  createdAt: string
  confirmedAt?: string
  stale: boolean
  sources: MaterialSource[]
  resumes: Resume[]
  candidates: MaterialCandidate[]
  missing: string[]
}

export type ScopeAction = 'DISCOVER' | 'MATCH' | 'PREPARE' | 'APPLY' | 'CONTACT' | 'SEND_CV' | 'PROJECT_EXECUTE' | 'PROJECT_VERIFY' | 'PROJECT_FEEDBACK'
export type ScopeMeter = 'candidates' | 'modelTokens' | 'seconds' | 'outbound'
export interface ScopeConfig {
  expectedVersion?: number
  channels: string[]
  hosts: string[]
  regions: string[]
  languages: string[]
  directions: string[]
  includeTerms: string[]
  excludeTerms: string[]
  factIds: string[]
  resumeIds: string[]
  contactFactIds?: string[]
  contactVerified?: boolean
  projectIds?: string[]
  projectResources?: string[]
  projectFeedbackIds?: string[]
  transformations: string[]
  actions: ScopeAction[]
  budgets: Record<ScopeMeter, number>
  expiresAt: string
}
export interface ScopePolicy extends ScopeConfig {
  id: string
  version: number
  status: 'ACTIVE' | 'PAUSED' | 'REVOKED'
  effectiveStatus?: string
  source: 'USER_SCOPE'
  createdAt: string
  materials: { facts: Fact[]; resumes: Resume[] }
}
export interface ScopeDecision {
  allowed: boolean
  source: 'SCOPE_POLICY'
  scopeVersion?: number
  reasons: string[]
  reasonCodes: string[]
  cost?: Record<ScopeMeter, number>
}
export interface ScopeWorkspace {
  current?: ScopePolicy
  history: ScopePolicy[]
  usage: Record<ScopeMeter, number>
  authorizations: Array<{
    id: string; scopeVersion: number; status: 'FROZEN' | 'CONSUMED'; source: 'SCOPE_POLICY'
    request: { action: Exclude<ScopeAction, 'PROJECT_EXECUTE' | 'PROJECT_VERIFY' | 'PROJECT_FEEDBACK'>; target: { title: string; url: string } } |
      { action: 'PROJECT_EXECUTE' | 'PROJECT_VERIFY' | 'PROJECT_FEEDBACK'; projectId: string; directory: string }
    result?: { outcome: string; evidence: string }
  }>
}

export type MatchKind = 'SUPPORTED' | 'PARTIAL' | 'NEEDS_FACTS' | 'EXPERIENCE_GAP' | 'HARD_REQUIREMENT' | 'NOT_APPLICABLE'
export interface MatchingAnalysis {
  id?: string; status: 'NOT_ANALYZED' | 'ANALYSIS_READY' | 'NEEDS_FACTS' | 'EXPERIENCE_GAP' | 'ANALYSIS_EXCEPTION' | 'INELIGIBLE'
  stale: boolean; scopeVersion?: number; rulesVersion?: string; jdSha256?: string; createdAt?: string; exception?: string
  questions?: Array<string | { factId?: string; label?: string; reason: string }>
  findings?: Array<{
    kind: MatchKind; requirementQuote: string; reason: string
    evidence: Array<{ type: 'FACT' | 'RESUME'; id: string; quote: string; source: Partial<Fact & Resume> }>
    suggestion?: { goal: string; acceptance: string }
  }>
}
export interface CandidateJob {
  id: string; channel: Channel; region: Region; language: Language; direction: Direction
  company: string; title: string; url: string; description: string; platformJobId?: string
  status: 'READY' | 'INCOMPLETE' | 'EXPIRED' | 'REFUSED'
  scopeVersion: number; sourceUrl: string; retrievedAt: string; firstSeenAt: string; discoveries: number; reasons: string[]
  descriptionComplete: boolean; lastReadComplete: boolean; lastSeenAt: string; lastReadDescription?: string
  analysis: MatchingAnalysis
}
export interface CandidateRun {
  id: string; status: 'DISCOVERING' | 'CHECKPOINT' | 'COMPLETED'; scopeVersion: number; pagesRead: number; updatedAt: string
  events: Array<{ code: string; channel: string; sourceUrl: string; message: string; at: string }>
  sources: Array<{ channel: Channel; url: string; nextUrl: string; cursor: string; done: boolean }>
}
export interface CandidateWorkspace { jobs: CandidateJob[]; runs: CandidateRun[] }

export interface PreparationPackage {
  id: string; candidateId: string; analysisId?: string; scopeVersion: number; source: 'SCOPE_POLICY'
  rulesVersion: string; createdAt: string; reason: string
  status: 'POLICY_APPROVED' | 'DRAFT_ONLY' | 'MATERIAL_EXCEPTION' | 'INVALIDATED'
  target: Pick<CandidateJob, 'url' | 'title' | 'company' | 'channel' | 'region' | 'language'> & { resumeId?: string; transformation?: string }
  proposal?: { content?: string; initialMessage?: string; factIds?: string[]; transformation: string; resumeId: string }
  snapshot?: { resume: Resume; facts: Fact[]; initialMessage: string; initialMessageSha256: string; contactVerified?: boolean; contactFacts?: Fact[] }
}

export interface WorkflowCycle {
  id: string; status: 'RUNNING' | 'CHECKPOINT' | 'PAUSED' | 'EXCEPTION' | 'PREPARED' | 'COMPLETED'
  scopeVersion: number; stage?: string; updatedAt?: string
  events: Array<{ code: string; message: string; at: string }>
  entries: Array<{ candidateId: string; status: string; reason?: string; packageId?: string; taskId?: string; execution?: Task }>
}

export interface GapProject {
  id: string; candidateId: string; analysisId: string; requirementQuote: string; goal: string; acceptance: string
  directory?: string; limitation: string; allowedActions: string[]; scopeVersion?: number
  status: 'PROPOSED' | 'BLOCKED' | 'QUEUED' | 'RUNNING' | 'UNVERIFIED' | 'FAILED' | 'STOPPED' | 'REJECTED' | 'VERIFIED'
  verifications?: Array<{ id: string; source: string; status: 'VERIFYING' | 'PASSED' | 'REJECTED'; attemptId: string; verifierIdentity: string; evidenceSha256: string; scopeVersion: number; reason?: string
    contract: { goal: string; acceptance: string; artifacts: Array<{ path: string; sha256: string; bytes: number }> }
    checks?: Array<{ criterion: string; passed: boolean; evidence: string }>
  }>
  stopRequested?: boolean; lastProgress?: string; updatedAt: string
  events: Array<{ code: string; message: string; at: string }>
  attempts: Array<{ id: string; startedAt: string; endedAt?: string; status?: string; authorizationId: string
    evidence?: { source: string; verified: boolean; summary: string; acceptanceReport: string; artifacts: Array<{ path: string; description: string; sha256: string; bytes: number }> }
  }>
}

export interface WorkbenchState {
  facts: Fact[]
  resumes: Resume[]
  tasks: Task[]
  materials: { sources: MaterialSource[]; reviews: MaterialReview[] }
  scope: ScopeWorkspace
  candidates: CandidateWorkspace
  preparations: { packages: PreparationPackage[] }
  workflow: { cycles: WorkflowCycle[] }
  projects: { projects: GapProject[] }
  runtime: { verifierConfigured: boolean; projectConfigured: boolean; agentConfigured: boolean; offerTodayConfigured: boolean; busy: boolean; bossReadConfigured: boolean; bossOutboundSupported: boolean; bossCapabilityReason: string; jobsDbConfigured: boolean; jobsDbProductionVerified: boolean; jobsDbOutboundSupported: boolean; jobsDbCapabilityReason: string }
  platforms: {
    bossUrl: string
    offerTodayUrl: string
    jobsdbHistory: Array<{ id: string; status: string; updated_at: string }>
    jobsdbAvailable: boolean
    jobsdbError?: string
  }
}

export type FactInput = Pick<Fact, 'key' | 'label' | 'value' | 'scope' | 'confirmed' | 'source'> & { id?: string }
export type TaskInput = Pick<Task, 'kind' | 'channel' | 'region' | 'language' | 'direction' | 'company' | 'title' | 'url' | 'description' | 'note'> &
  Partial<Pick<Task, 'resumeId' | 'projectPath' | 'projectPlan' | 'initialMessage' | 'contactVerified'>>

export interface OfferTodayDiscovery {
  jobs: Array<{ url: string; title: string; company?: string; description?: string }>
  note?: string
  connected?: boolean
}

const root = '/api/workbench'

async function request(path: string, init?: RequestInit): Promise<Response> {
  let response: Response
  try {
    response = await fetch(apiUrl(path), { cache: 'no-store', ...init })
  } catch {
    throw new Error('无法连接工作台服务，请确认后端已启动。')
  }
  if (!response.ok) {
    const data: unknown = await response.json().catch(() => null)
    const error = data && typeof data === 'object' && 'error' in data ? (data as { error: unknown }).error : null
    throw new Error(typeof error === 'string' && error.trim() ? error : `请求失败（HTTP ${response.status}）`)
  }
  return response
}

export async function workbenchGet<T>(path: string): Promise<T> {
  return (await (await request(`${root}${path}`)).json()) as T
}

export async function workbenchJson<T>(path: string, method: 'POST' | 'DELETE', body?: unknown): Promise<T> {
  const response = await request(`${root}${path}`, {
    method,
    ...(body === undefined ? {} : { headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) }),
  })
  return method === 'DELETE' || response.status === 204 ? undefined as T : (await response.json()) as T
}

export async function workbenchUpload(form: FormData): Promise<Resume> {
  return (await (await request(`${root}/resumes`, { method: 'POST', body: form })).json()) as Resume
}

export async function workbenchFile(id: string): Promise<Blob> {
  return (await request(`${root}/resumes/${encodeURIComponent(id)}/file`)).blob()
}
