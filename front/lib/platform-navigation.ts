/**
 * 配置管理中心 · 平台导航 UI read model。
 *
 * 本模块只拥有“展示什么”：条目、链接、能力与限制。它不创建任务、不授予动作、
 * 不启动执行器，也不参与后端平台分派或权限判断。页面只从这里读取行数据，
 * 再从 `/api/workbench/state` 的 `runtime` / `platforms` 补充状态显示。
 *
 * 保持零依赖、纯函数，便于 Node 直接执行测试（Node 25 类型剥离）。
 */

export type PlatformId =
  | 'boss'
  | 'liepin'
  | 'job51'
  | 'zhilian'
  | 'offertoday'
  | 'jobsdb'
  | 'company-site'

/**
 * available: 运行时明确声明可用
 * verified: 有可核对的一次性证据（不是“生产已启用”）
 * limited: 受控/受限路径可用
 * unsupported: 平台能力未实现，请求应被拒绝
 * blocked: 运行时或生产绑定明确关闭
 * unknown: 未连接/未读取到运行时，不能冒充可用
 */
export type NavigationStatus = 'available' | 'verified' | 'limited' | 'unsupported' | 'blocked' | 'unknown'

export interface PlatformLink {
  label: string
  href: string
}

export interface PlatformCapability {
  id: string
  label: string
  status: NavigationStatus
  detail: string
}

export interface PlatformRow {
  id: PlatformId
  name: string
  summary: string
  links: PlatformLink[]
  capabilities: PlatformCapability[]
  limits: string[]
  /** 来自 `/api/workbench/state.platforms` 的只读提示；不构成链接或授权。 */
  runtimeNotes: string[]
}

export interface NavigationRuntime {
  agentConfigured?: boolean
  offerTodayConfigured?: boolean
  bossOutboundSupported?: boolean
  bossCapabilityReason?: string
  jobsDbConfigured?: boolean
  jobsDbOutboundSupported?: boolean
  jobsDbCapabilityReason?: string
  jobsDbSingleJobSupported?: boolean
  jobsDbSingleJobReason?: string
}

export interface NavigationPlatforms {
  bossUrl?: string
  offerTodayUrl?: string
  jobsdbAvailable?: boolean
}

/**
 * OfferToday 两版生产发送绑定。脚本 `scripts/offertoday/browser.js` 当前对
 * legacy / current 都没有生产实现；这里只复制为只读展示数据，不参与执行。
 */
export const OFFERTODAY_DISPATCH_PROOF_BY_BUILD: Readonly<{ legacy: null; current: null }> = Object.freeze({
  legacy: null,
  current: null,
})

export const NAVIGATION_STATUS_LABEL: Record<NavigationStatus, string> = {
  available: '可用',
  verified: '已验证',
  limited: '受限',
  unsupported: 'unsupported',
  blocked: 'blocked',
  unknown: '未知（未连接）',
}

const BOSS_CV_NOT_ATTEMPTED =
  'CV / 附件送达未获能力声明：CV 未尝试，首击成功也不能证明精确正文或简历已送达。'
const UNSUPPORTED_SINGLE_TARGET =
  '受控单目标能力 unsupported：旧 batch Worker 保留，但工作台不会 claim、不会启动旧批次。'

/** 未传运行时时的统一口径；绝不能把它显示成可用。 */
const NO_RUNTIME = '未读取到运行时状态（未连接）：无法确认能力，不冒充可用。'

function text(value: unknown): string {
  return typeof value === 'string' ? value.trim() : ''
}

function bossCapability(runtime: NavigationRuntime): PlatformCapability {
  const reason = text(runtime.bossCapabilityReason)
  if (runtime.bossOutboundSupported === true) {
    return {
      id: 'controlled-first-contact',
      label: '受控单岗首击 / 自动招呼或精确正文',
      status: 'limited',
      detail: reason || '运行时声明受控单岗首击可用；仅按受控路径执行，CV 未尝试。',
    }
  }
  if (runtime.bossOutboundSupported === false) {
    return {
      id: 'controlled-first-contact',
      label: '受控单岗首击 / 自动招呼或精确正文',
      status: 'blocked',
      detail: reason || '运行时未声明 Boss 受控首击可用；不 claim、不启动旧批次，不冒充可用。',
    }
  }
  return {
    id: 'controlled-first-contact',
    label: '受控单岗首击 / 自动招呼或精确正文',
    status: 'unknown',
    detail: NO_RUNTIME,
  }
}

function bossRow(runtime: NavigationRuntime, platforms?: NavigationPlatforms): PlatformRow {
  const bossUrl = text(platforms?.bossUrl)
  return {
    id: 'boss',
    name: 'Boss',
    summary: '受控单岗首击；旧审核入口与工作台是同一个逻辑平台。',
    links: [
      { label: '旧审核入口', href: '/boss' },
      { label: '工作台', href: '/workbench' },
    ],
    capabilities: [
      bossCapability(runtime),
      {
        id: 'cv-delivery',
        label: 'CV 送达',
        status: 'unsupported',
        detail: BOSS_CV_NOT_ATTEMPTED,
      },
    ],
    limits: [
      '点击「立即沟通」后平台可能先发未知自动招呼；不能倒推「没有发送」，也不能据此确认精确正文。',
      '工作台不会通过 HTTP 触发 Boss 旧批次；导航只展示入口，不代表授权或已执行。',
    ],
    runtimeNotes: bossUrl ? [`后端提供的入口：${bossUrl}（导航链接仍使用本地 /boss）`] : [],
  }
}

function unsupportedPlatformRow(id: PlatformId, name: string, href: string): PlatformRow {
  return {
    id,
    name,
    summary: '保留既有旧页面与旧 batch Worker；受控单目标能力未实现。',
    links: [{ label: '旧页面', href }],
    capabilities: [
      {
        id: 'controlled-single-target',
        label: '受控单目标动作',
        status: 'unsupported',
        detail: UNSUPPORTED_SINGLE_TARGET,
      },
    ],
    limits: [
      '绕过 UI 直接请求同一受控动作应得到 blocked 或现有边界的明确拒绝；工作台不注册这些渠道的 effect，也不会启动旧批次。',
      '导航不新增后端分派或权限注册表，不改变请求拒绝结果。',
    ],
    runtimeNotes: [],
  }
}

function offerTodayRow(runtime: NavigationRuntime, platforms?: NavigationPlatforms): PlatformRow {
  const url = text(platforms?.offerTodayUrl)
  let publicRead: PlatformCapability
  if (runtime.offerTodayConfigured === true) {
    publicRead = {
      id: 'public-job-read',
      label: '公开岗位读取',
      status: 'available',
      detail:
        '运行时显示脚本已配置：只读读取当前已加载的公开岗位（最多 40 条）；不自动翻页、不创建任务、不联系。读取时仍需核验已授权连接。',
    }
  } else if (runtime.offerTodayConfigured === false) {
    publicRead = {
      id: 'public-job-read',
      label: '公开岗位读取',
      status: 'blocked',
      detail: '运行时显示 OfferToday 脚本未配置；公开岗位读取和逐岗申请均不能从此页启动，也不借用官网 AI。',
    }
  } else {
    publicRead = {
      id: 'public-job-read',
      label: '公开岗位读取',
      status: 'unknown',
      detail: '未读取到运行时状态（未连接）：无法确认公开岗位读取是否可用，不冒充可用。',
    }
  }
  return {
    id: 'offertoday',
    name: 'OfferToday',
    summary: '公开岗位只读读取与逐岗申请入口；已有一次经单次授权的 CV 送达证据，生产自动发送保持关闭。',
    links: [{ label: '工作台（逐岗审核）', href: '/workbench' }],
    capabilities: [
      publicRead,
      {
        id: 'verified-cv-delivery',
        label: '已验证的一次 CV 送达',
        status: 'verified',
        detail:
          '2026-11-28 招聘者回复并邀请发简历后，经一次授权提交；平台返回成功，会话显示「送達」。只证明该会话的这次送达，不等同生产脚本自动发送已启用。',
      },
      {
        id: 'production-auto-send',
        label: '生产自动发送',
        status: 'blocked',
        detail: '生产自动发送 blocked：发送绑定未核实，必须在 claim 前停止，不能用配置或任务字段打开。',
      },
      {
        id: 'dispatch-proof-legacy',
        label: 'dispatchProofByBuild.legacy',
        status: 'blocked',
        detail: `空（${String(OFFERTODAY_DISPATCH_PROOF_BY_BUILD.legacy)}）：legacy 发送绑定未核实，生产自动发送保持 blocked。`,
      },
      {
        id: 'dispatch-proof-current',
        label: 'dispatchProofByBuild.current',
        status: 'blocked',
        detail: `空（${String(OFFERTODAY_DISPATCH_PROOF_BY_BUILD.current)}）：current 发送绑定未核实，生产自动发送保持 blocked。`,
      },
    ],
    limits: [
      '两版生产发送绑定均为空（legacy: 空 · current: 空）；一次性实站送达不等于生产自动批量发送已启用。',
      '点击、附件选择、本地 hash、transport packet 或会话渲染都不是送达证据；只有绑定同岗、同次、同会话的新回执才算。',
    ],
    runtimeNotes: [
      url
        ? `后端提供的入口：${url}（只读展示，导航不在本页打开外部平台）`
        : platforms === undefined
          ? '未读取到运行时入口 URL。'
          : '后端未提供 OfferToday 入口 URL。',
      runtime.offerTodayConfigured === true
        ? '运行时显示 OfferToday 脚本已配置；连接、页面变体与发送仍需在每次读取/执行时核验。'
        : runtime.offerTodayConfigured === false
          ? '运行时显示 OfferToday 脚本未配置；不依赖官网 AI，不能启动。'
          : NO_RUNTIME,
    ],
  }
}

function jobsDbRow(runtime: NavigationRuntime, platforms?: NavigationPlatforms): PlatformRow {
  const singleReason = text(runtime.jobsDbSingleJobReason)
  const outboundReason = text(runtime.jobsDbCapabilityReason)
  let single: PlatformCapability
  if (runtime.jobsDbSingleJobSupported === true) {
    single = {
      id: 'single-job-apply',
      label: '受控单岗 APPLY（apply-single）',
      status: 'limited',
      detail:
        singleReason || '受控单岗 APPLY 可用；受冻结材料、有效授权和 applications.db 占位限制，批次、联系、附件外发仍禁用。',
    }
  } else if (runtime.jobsDbSingleJobSupported === false) {
    single = {
      id: 'single-job-apply',
      label: '受控单岗 APPLY（apply-single）',
      status: 'blocked',
      detail: singleReason || 'JobsDB 受控单岗申请未配置独立脚本，不能借用官网 AI。',
    }
  } else {
    single = {
      id: 'single-job-apply',
      label: '受控单岗 APPLY（apply-single）',
      status: 'unknown',
      detail: NO_RUNTIME,
    }
  }
  return {
    id: 'jobsdb',
    name: 'JobsDB',
    summary: '工作台受控单岗申请沿用既有 apply-single 与原 applications.db 占位。',
    links: [{ label: '工作台（单岗申请）', href: '/workbench' }],
    capabilities: [
      single,
      {
        id: 'batch-outbound',
        label: '批次 / 联系 / 附件外发',
        status: 'blocked',
        detail: outboundReason || '批次、联系、附件外发未获批准；直接请求在 claim 前拒绝。',
      },
      {
        id: 'cv-evidence',
        label: 'CV 送达凭据',
        status: 'unknown',
        detail:
          '已有单岗 CLI 实投只证明该次提交；页面不据此宣称批次获准，也不宣称对应 CV 已有送达凭据。',
      },
    ],
    limits: [
      '工作台 `POST /api/workbench/jobsdb/apply` 通过 `apply-single` 受控申请一个岗位，受冻结材料、有效授权和 `applications.db` 占位限制。',
      '已有单岗 CLI 实投只证明该次提交；页面不据此宣称批次获准或对应 CV 已有送达凭据。',
      '缺授权、材料或占位时直接请求应得到 blocked/duplicate，零 claim、零批次启动。',
    ],
    runtimeNotes: [
      `JobsDB CLI 运行环境：${
        platforms?.jobsdbAvailable === true ? '存在' : platforms?.jobsdbAvailable === false ? '未检测到' : '未知'
      }（只读展示，不在页面启动命令）。`,
      runtime.jobsDbConfigured === true ? '运行时显示 JobsDB 脚本已配置。' : runtime.jobsDbConfigured === false ? '运行时显示 JobsDB 脚本未配置。' : NO_RUNTIME,
    ],
  }
}

function companySiteRow(runtime: NavigationRuntime): PlatformRow {
  let executor: PlatformCapability
  if (runtime.agentConfigured === true) {
    executor = {
      id: 'generic-executor',
      label: '通用执行器配置',
      status: 'available',
      detail: '运行时显示官网/简历通用执行器已配置；仅展示，启动仍须逐任务批准与执行器自身核验。',
    }
  } else if (runtime.agentConfigured === false) {
    executor = {
      id: 'generic-executor',
      label: '通用执行器配置',
      status: 'blocked',
      detail: '运行时显示通用执行器未配置；不能启动，也不借用其他渠道。',
    }
  } else {
    executor = {
      id: 'generic-executor',
      label: '通用执行器配置',
      status: 'unknown',
      detail: NO_RUNTIME,
    }
  }
  return {
    id: 'company-site',
    name: '公司官网',
    summary: '工作台候选与官网任务的只读入口；通用执行器配置来自运行时。',
    links: [{ label: '工作台候选 / 官网任务', href: '/workbench' }],
    capabilities: [executor],
    limits: [
      '官网只读候选/任务入口；导航本身不创建任务、不启动外部命令、不声明已投递。',
      '通用执行器配置来自运行时，仅展示；执行器是操作人配置的可信本机程序，不是 OS 沙箱。',
    ],
    runtimeNotes: [],
  }
}

/**
 * 纯函数：同一份运行时快照总是得到同一份展示行。七行各出现一次。
 * 传入空对象或缺少字段时，相关能力显示未知/未连接，绝不冒充可用。
 */
export function platformNavigation(
  runtime: NavigationRuntime = {},
  platforms?: NavigationPlatforms,
): PlatformRow[] {
  return [
    bossRow(runtime, platforms),
    unsupportedPlatformRow('liepin', 'Liepin', '/liepin'),
    unsupportedPlatformRow('job51', '51job', '/51job'),
    unsupportedPlatformRow('zhilian', 'Zhilian', '/zhilian'),
    offerTodayRow(runtime, platforms),
    jobsDbRow(runtime, platforms),
    companySiteRow(runtime),
  ]
}
