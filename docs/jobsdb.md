# JobsDB HK — 独立 Scrapling 接入

JobsDB 命令保留 Java 启动入口，实际浏览器、DOM 操作和账本由一个 Python CLI 拥有。不是新 HTTP 服务，也不是原管理页面中的第五个平台按钮。Boss、猎聘等平台的 Java 逻辑、Gradle bootRun 和 profile 均不改。

## 安装（仅首次）

要求 Java 21、uv（可安装 Python 3.12）、正式 Google Chrome。以下命令在独立 JobsDB clone 根目录运行；不要在正在投递 Boss 的原目录运行。

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
cd /Users/liuqingyuan/work/get_jobs_jobsdb
bash gradlew --no-daemon jobsdbSetup --console=plain
```

安装只写本目录 `.jobsdb/venv`，依赖固定在 `scripts/jobsdb/requirements.txt`。旧 `jobsdbInstallBrowser` 已移除；不再为 JobsDB 安装或启动 Java Patchright driver/Chromium。

## 登录一次，保存独立会话

```bash
bash gradlew --no-daemon jobsdb --console=plain --args='login'
```

浏览器自动处理 CF。看到 `CF_PASSED` 后，在网页点击 **Sign in**，自行输入账号及邮箱验证码。确认网页已登录后，回终端按 Enter 保存并关闭。日志 `SESSION_SAVED` 只表示保留浏览器 profile，**不冒充服务器已验证账号登录**。EOF 或 `cancel` 取消，退出码 2。

这是一次性登录设置；会话过期后才需要重新登录。登录窗口持有独占锁，其他 JobsDB 命令会报错退出，不争抢 profile。

## 原命令

```bash
bash gradlew --no-daemon jobsdb --console=plain --args='search "software engineer" 1'
bash gradlew --no-daemon jobsdb --console=plain --args='prepare https://hk.jobsdb.com/job/12345678'
bash gradlew --no-daemon jobsdb --console=plain --args='apply https://hk.jobsdb.com/job/12345678'
bash gradlew --no-daemon jobsdb --console=plain --args='history'
```

上面的职位 ID 仅为格式示例，真实 ID 从 search 输出选择。搜索支持 1–5 页，写 `.jobsdb/search.json`。prepare 不提交；apply 仍保留现有 `SUBMIT 职位ID` 确认合同。缺少简历或问题答案时暂停，不编造内容。仅处理英文 Quick Apply；外部 ATS 跳过。

首版只完成浏览器接入；后续新增独立 `batch` 命令实现配置驱动批次，见下文。原逐岗审核入口保持不变。

## 数据与错误合同

所有状态固定在当前 clone `.jobsdb/`：
- `scrapling-profile/`：新入口专用持久化 Chrome 会话。不读取、不复制旧 `browser-profile/`，也不接入其他 Chrome/Boss 会话。
- `applications.db`：复用旧 Java 实现的表结构及记录，不迁移、不清空。
- `run.lock`：跨进程 POSIX 文件锁。
- `screenshots/`：当前页面及提交结果截图（可能含个人信息，仅本地保存）。

提交前原子 claim 为 UNKNOWN；确认成功才更新 SUBMITTED。UNKNOWN 禁止自动重试。Scrapling `retries=1` 避免重放整个 page_action；回调异常显式传播，不接受“日志有错但退出成功”。CF/导航上限 90 秒；等待用户输入不设此超时。提交后不重新处理 CF 或重按提交。

Java 入口完整转发参数、stdin/stdout/stderr 和退出状态；失败的 Python 子进程使 Gradle 失败。Ctrl+C 会清理自己的子进程。命令运行需要源码 checkout，不是可独立部署的胖 JAR。

## 浏览器页面清理

持续投递时只保留当前处理所需的少量页面，避免每个岗位都留下一个标签页或窗口：

1. 每份申请先核实明确成功回执，保存职位、表单审核和结果证据，更新 `applications.db` 及报告，然后立即关闭本项目该岗位的标签页。先保存证据，再关闭页面。
2. 已排除的职位页、重复职位页、用完的搜索页同样及时关闭；下一岗位复用同一个 Scrapling Chrome 会话与 `.jobsdb/scrapling-profile`，不为清理页面新建登录目录。
3. 等待用户验证码、登录、补充必填答案，或尚未保存的未完成表单先保留，记录岗位、URL、阶段及阻塞；确认草稿已保存或当前已无未保存表单后再关闭。UNKNOWN 先保存现场，继续保持禁止自动重投。
4. 关闭前只定位本项目拥有的页面。关闭标签页不等于退出整个 Chrome；其他用户窗口、其他 profile，以及另一项目的浏览器均保持不动。结束本项目会话且已无需要保留的表单时，才正常退出本项目的浏览器，释放独占锁；不以强杀整个 Chrome 代替清理。
5. 清理后核实目标页面已关闭、所需待办页面仍在、证据文件可重新读取且账本状态未因关闭页面改变。后续 agent 同样执行此规则。

## 测试

```bash
bash gradlew --no-daemon jobsdbTest --console=plain
```

使用 Python 标准库 unittest、真实 Chrome 和全路由 HTML fixtures，不发送真实申请。覆盖原申请状态机、SQLite 兼容、未知结果去重、锁、输入取消、跨会话 cookie/localStorage 保存、Scrapling 回调异常传播。独立测试 profile 在临时目录，不使用账号 profile。

真实账号是否已登录、雇主动态表单和真实投递结果，须由后续账号测试证明；fixture 通过不代表投递成功。

## 工作台只读候选（#16 增量，单岗外发尚未接通）

默认候选页可一次提交 `https://hk.jobsdb.com/jobs?...` 或 `https://hk.jobsdb.com/<keywords>-jobs`，范围须明确 `JOBSDB`、`hk.jobsdb.com`、HK 及 DISCOVER/MATCH/PREPARE。新增 CLI `read-candidates taskFile --data-dir <当前副本目录>` 仅由可信 Runner 调用；不调用原 `search` 的文件写入、`apply` 或 `batch`，不加载全局 answers/联系方式，不创建或修改申请账本。

默认只在本副本所配 JobsDB 目录已有 `venv/bin/python` 且源码脚本存在时配置；`WORKBENCH_JOBSDB_COMMAND` JSON 参数数组可覆盖，`[]` 禁用。Runner 首次追加 `read-candidates taskFile --data-dir ...`，需要用户操作时再以服务器生成且绑定原任务的 `readAttemptId` 追加 `resume-read taskFile --data-dir ...`，都不是 shell。只复用此目录已显式存在的 `scrapling-profile/` 与原 `run.lock`；缺会话时明确停止，不创建新 profile、不复制 Cookie。与其他工作台操作串行，原 CLI 忙碌时也由同一锁拒绝。

每页最多 40 个详情、当前加载列表最多 200 个链接；超过列表上限明确报错，不静默截断。续读仅遍历已加载列表并绑定 ID 指纹，列表变化要求重启只读轮次，不推测下一页。普通读取在导航前给整个 BrowserContext（含 popup 首次请求）安装网络守卫，只放行支持的列表/详情及窄范围静态 GET，拒绝其他网络请求且不运行 CF solver；只读 Browser 阻止新 Service Worker 注册。现有 profile 包含可核验的真实 Service Worker 目录，或严格读取观察到登录/验证页时，不删除、不迁移、不弱化守卫，而是返回不含页面正文或凭证的固定 `userActionRequired` 控制结果；profile 子项或 Worker 为链接、悬空链接、普通文件或元数据不可读时，在打开浏览器前失败关闭；Java 在初读及消费缓存续读结果前检查一次，Python resume 在锁内再次检查。

`resume-read` 是独立的可见用户交接：仍使用同一 profile 和锁，调用 Scrapling 交互浏览器时用 pinned private solver 自动完成 CF/CAPTCHA，无需用户确认；登录凭证不读取、不填写、不复制，会话失效时保留断点。CLI 监护进程不进入 Playwright greenlet，而是在独立会话中启动实际读取 worker；收到 SIGTERM/Ctrl+C 后先通知该 worker，宽限期届满即回收其整个进程组并以 130 退出，防止关闭窗口或 Java 硬截止后遗留 Chrome/Node 后代。交互网络守卫允许 JobsDB、必要验证和静态资源的正常流量；只对白名单中的明确 HTTPS 认证/挑战端点以及挑战完成后回到同一获准列表/详情的 POST 允许必要写请求，通用或嵌套 `/graphql` 与账号设置路径不属于认证白名单，其他写请求、未知主机及 apply/contact/upload/message 路径一律拒绝。交互阶段不截图，不点击 Quick Apply、不上传、不提交、不修改账号设置。严格 Reader 将验证发生的精确目标（获准列表或本轮数字岗位详情）、`readAttemptId` 与剩余严格读取秒数持久化；可见交接直接呈现该目标；验证清除后移除交互 route，在同一持久 BrowserContext 上安装严格只读 route，并从原列表/游标继续读取。不删除现有 Service Worker 存储，而是组合 `service_workers=block`、停止当前 worker、CDP `Network.setBypassServiceWorker` 与原只读 route，已用持久 worker 对抗 fixture 证明页面请求仍经过守卫。Java 在启动可见交接前把私有续读目录身份绑定到断点，Python 在真正开始严格尝试时写入开始时间；当前进程异常或 Java 重启都先据此扣减剩余秒数；Java 同时从该开始时间实施硬截止，终止超时子进程并拒绝迟到成功结果；最终 jobs 输出还必须原子落盘并携带绑定 interaction、目标、开始／完成时间和本次预算的 `readAttemptCompleted`；结果名完成 `fsync` 后才创建并 `fsync` 一枚绑定结果 SHA256 的发布标记；Java 核验该标记的哈希和创建时间位于同一截止前，因此 Java 重启后也不会把声明早完成、实际迟到发布的文件当作成功。每次交接后只做一次严格尝试并重新核验 task/readAttempt/source/limits/scope 身份；若列表或详情再次验证，返回同一 attempt、精确目标及扣减后的剩余秒数，由 Java 写回持久断点后再次自动求解，不在子进程内无限循环，也不把原时限重新装满。最终只写岗位结果；严格秒数耗尽则结束该笔读取且不自动重试，自动求解时间不计入严格读取时限。自动求解失败、登录失效、窗口关闭、身份不符或未知 schema 均保留工作台断点；短/缺失 `jobAdDetails` 保留描述不足。真实列表、DOM 覆盖、跨重开和推荐质量仍须实站验收，普通页面打开或 HTTP 200 不代表通过。

完整候选可统一匹配及另存可核验 DOCX；需在一次范围中选定核对的适用港区联系方式。**材料策略核准不是渠道可执行能力**：原线上 Profile、附件实际字节和材料绑定回执尚未核实，工作台 APPLY/CONTACT/SEND_CV 明确拒绝，不 claim、不回退官网或调用旧批次。#16 仍 open / needs-triage，受控单岗申请是剩余项；只读接入不是实投交付。

## 全自动批次

新增命令（一次配置，批次内不读取 stdin）：

```bash
bash gradlew --no-daemon jobsdb --console=plain --args='batch --config .jobsdb/batch-config.json'
```

配置包含 `auto_submit_enabled: true`、`resume_path`、`resume_sha256`、`searches`（1–10 个关键词）、`pages`（1–5）、`max_candidates`（1–200）、`max_submissions`（1–50）以及 `answers`。`answers` 将真实表单的 `questionnaire.*` 名称映射到用户确认的精确选项文案；未知题、缺少选项、非支持控件都记录 NEEDS_INPUT 并继续其他岗位，不猜测。

原 `apply` 仍逐岗确认；`batch` 是明确授权自动提交的独立入口。当前本地配置使用确认的月薪、IANG、学历、工作年限和英文简历，属于私有数据，不提交到 Git。新增问题的答案只能来自用户事实；补充后重跑会自动跳过已提交或 UNKNOWN 的岗位。

批次依次搜索去重、读取 `jobAdDetails`（排除推荐职位污染）、按 AI/量化与早期职业规则筛选、选择内容哈希命名的简历、填写已配置问题、核对 review 附件、原子 claim、提交一次。筛选是透明规则，不是 LLM 适配器：未知粤语条件跳过；明确可选粤语保留。拒绝明显资深、超过一年必需经验、内地地点和在读学生要求。规则不能保证雇主没有隐含要求，投递报告保留原 JD 供核对。

配置中的提交上限按尝试数计数（含 UNKNOWN），不是成功数。失效登录停止该批，外部 ATS/未知题/单岗错误记录并继续。结果即时保存在 `.jobsdb/batches/<UTC时间>/report.json`，附筛选依据、审核与结果截图/文本。中断后重跑不会重新提交已 claim 的岗位。批次不是常驻定时器：执行一次完整有界批次后退出，下一批再次运行同一命令。

网站 profile 步骤会在事件处理器尚未可用时显示 Continue；仅该非提交步骤允许一次有界第二次点击。最终 Submit 永不重试。网站已发送文案含雇主名称时亦识别成功；若未看到积极证据则保留 UNKNOWN。

雇主多选题的 `answers` 值使用精确标签数组，例如 `"questionnaire.<observed-id>": ["Python"]`；单选题仍使用字符串。题目 ID 和标签必须来自实际页面，答案必须来自已确认信息或简历。多选题会清除非配置选项；未知标签、重复标签、空数组和类型不匹配会停止该岗位，不提交。语言熟练度等未确认事实仍需补充。
