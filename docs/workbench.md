# 统一求职工作台 V1

入口：`http://127.0.0.1:9527/workbench`。主副本是 `get_jobs_jobsdb/`，上游同步的隔离工作树是 `get_jobs_upstream_sync/`（见 [同步记录](upstream-sync-36bf5f9.md)）。目录名不代表版本；运行前确认当前分支，隔离验证不切换主副本。原 `get_jobs/` 不修改，私有账号、Cookie 和数据库不自动搬迁。

## 启动

要求 Java 21、Node.js、pnpm（或 `npx pnpm`）。JobsDB CLI 仍使用原 `.jobsdb/venv`，工作台本身不依赖其浏览器。

1. 国内模块依照原 README 准备 `db/getjobs.db`：官方 Release 的 `getjobs.db.template` 是干净空库，重命名后放入 `db/`。**不要覆盖任何已有数据库，也不要把原目录带账号的数据库当作空库复制。** 原字典和设置表由这个空库提供，工作台的新表不替代它。
2. 安装前端并启动：

```bash
# 先进入实际要运行的 checkout 根目录；隔离分支用 get_jobs_upstream_sync，不复用原账号数据。
cd /Users/liuqingyuan/work/get_jobs_upstream_sync
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
cd front
npx --yes pnpm@10.17.1 install --frozen-lockfile --ignore-scripts
cd ..
bash gradlew --no-daemon workbench --console=plain
```

`workbench` 会构建管理页、准备原平台 driver，但不会自动打开招聘浏览器、调用模型或发送申请。打开 `/workbench` 使用；Boss 的显式登录/原审核入口和 JobsDB CLI 各自保留独立会话。仅本机访问，额外静态端口也绑定 loopback。跨源仅放行本机后端端口和前端 6866，不信任其他 localhost 端口。

开发验证可覆盖 `--spring.datasource.url`、`--workbench.data-dir`、`--workbench.jobsdb-dir` 指向临时目录，且跳过 driver 安装：

```bash
bash gradlew --no-daemon workbench -x installPatchrightDriver \
  --args='--spring.datasource.url=jdbc:sqlite:/tmp/my-test/legacy.db --workbench.data-dir=/tmp/my-test/state --workbench.jobsdb-dir=/tmp/my-test/empty-jobsdb'
```

先创建 `/tmp/my-test`。不要将测试目录指向真实账号、数据库或浏览器 profile。

## 使用顺序

1. **个人资料 → 已有材料一次核对**：粘贴或选择有权提供的 TXT/MD 材料，明确通用/香港/内地作用域，并可勾选已登记来源及四类独立简历/岗位版。点击整理后，展示来源原文、哈希、重复、矛盾及缺失证据；集中勾选可核实事实和已经亲自审核的简历，一次确认可用子集。其余项仍待核实。原材料变化时另存来源版本，失去旧依据的事实暂停使用；旧确认与任务快照不改写。未变事实直接复用，同版本分析缓存命中不调用模型，页面轮询也不调用模型。PDF/DOCX 无正文时明确待补文本，不假装已提取或核准。仍可预览 JobsDB 已知资料（需显式点击）及逐条手动澄清；不自动读取旧资料、目录或私有基线。未确认项不进入授权快照，确认基线不授权外发。
2. **简历版本**：分别上传港区/国内的中文/英文版本；PDF、DOCX 或文本，最大 10MB。每次保存是新版本，审核批准只影响该版本。文本草稿不会自动变成可投的 PDF，需人工完成格式检查并上传最终附件。
3. **求职范围**：在新页签一次选定精确来源、渠道、地区/语言/方向、岗位选择词和排除词、已确认事实、独立核准简历、变换、动作、累计预算和有效期。默认只读发现/匹配/准备，无外发和项目执行许可。先保存再用同一代表性岗位预检，查看当前范围版本、策略来源和所有拒绝原因。可暂停、恢复、变更、撤销及回读历史；这些操作均产生新版本，旧未执行授权不复活。新版本补充额度应提高累计上限，不清零已耗量。范围本身尚不启动候选队列或执行器（由后续 Tickets 接入）；不将 Boss/JobsDB 原批次显示成已受控接通。
4. **创建岗位任务**：选地区、语言、方向、链接及已批准的匹配简历；读 JD 后决定是否批准。批准冻结当前材料，不自动同步其他版本。未选简历的任务仅可作为待办；需另建绑定正确版本的任务后执行。去重以详情路径和可识别的岗位编号参数为准，不用推广/来源参数区分岗位；同路径仅靠非标准参数区分职位时先人工核对规范详情链接，宁可暂停也不自动重投。
5. **官网/简历 AI**：必须另外配置执行器，批准不等于开始执行。未配置时按钮禁用，不显示虚构进展。模型生成的新简历由 `DRAFT` 事件另存为未批准版本，不能直接用于申请。
6. **项目补强**：先登记差距、计划、建议目录，不自动执行代码，也不把计划当作已完成经历。
7. **招聘状态**：官网渠道已投递或结果未知的申请可显式点击“检查最新进度”，启动只读 AI 查询。查询不上传材料、不修改官网资料、不允许申请；真实观察及证据回写原申请的招聘状态和检查时间，不改写执行结果。OfferToday 本版只支持在平台人工核对后登记证据，不自动借用官网 AI 查询。

JobsDB 页只读显示最近 100 条原账本并保留 CLI 交接。**工作台不通过 HTTP 直接启动 Boss/JobsDB 的交互式 apply 或批次**；一个岗位的批准不是整个批次的授权。OfferToday 是另行受控的单岗脚本路径，使用原审核/回执合同，不触及 Boss/JobsDB 批次。原平台配置、在线简历和 Cookie 不会因工作台资料更新自动改写。

## OfferToday HK

**OfferToday 已实现基于实站控件和已验证原生客户端的单岗脚本；真实发送验收仍未授权或进行。** 审核、启动、文件字节校验与分阶段回执通过隔离测试；未知页面或站点版本会阻塞，不保证每种实站变体可执行。验证边界见 issue #6 和下文设计记录。

在平台入口或任务渠道中选择 OfferToday。先在普通 Chrome 登录，并通过官方 Playwright 扩展只共享所需标签页，不必重新登录或复制登录态。工作台的职位读取必须手动点击，仅取当前已加载的最多 40 个不同岗位，不自动翻页；选择后只是填入待审表单，不会创建或发送申请。

OfferToday 只使用港区已审核 PDF/DOCX 附件，中文与英文版本继续独立。首条消息默认空白，须逐岗确认；核对平台实际联系方式与最新偏好及线上资料后，批准固定消息哈希、附件和适用事实。脚本启动不调用模型，也不等于已发送。缺资料、验证码、未知控件、不可控制的自动招呼或旧附件无法核验时等待人工。

源码 checkout 存在随附脚本时自动配置脚本前缀；可用 `WORKBENCH_OFFERTODAY_COMMAND` 的 JSON 参数数组覆盖，显式 `[]` 禁用。它是参数前缀，Runner 追加 `discover` 或 `run taskFile`，不是 shell，也不含 `{taskFile}` 模板。例如：

```bash
export WORKBENCH_OFFERTODAY_COMMAND='["python3","/absolute/checkout/scripts/offertoday/offertoday.py"]'
```

仅有脚本不代表浏览器已连接或网站发送链路已验收。详见 [OfferToday 连接、脚本与验证](offertoday-v1.md)。

## 接入现有通用 AI，不新造浏览器 Agent

通过 `WORKBENCH_AGENT_COMMAND` 配置一个可信本机命令的 **JSON 参数数组**。参数中的 `{taskFile}` 替换为私有任务包的绝对路径，不经过 shell。不允许网站/HTTP 请求传入执行命令。

例如已有 Pi CLI、模型配置及经授权的浏览器工具时：

```bash
export WORKBENCH_AGENT_COMMAND='["pi","--print","--no-session","@{taskFile}","只按任务包 instructions 执行。kind 为 MATERIAL_REVIEW 时，只分析给定 sources 并向 resultPath 写候选 JSON，不调用回调、不读取其他文件或网络；其他任务先阅读 reporterPath 帮助，按合同报告进度，缺资料报告 NEEDS_INPUT 后退出，不扩大权限。"]'
bash gradlew --no-daemon workbench --console=plain
```

这不会安装、登录或自动配置 AI。请选择自己的可用模型与工具；保留其权限限制，不为执行任务关闭安全确认。启动将把当前已批准的资料/简历交给该执行器，其模型可能是外部服务，会产生相应额度消耗。V1 不保证所有官网表单均可操作，验证码或陌生表单可以进入待人工处理。

执行器不是 OS 沙箱，是用户信任的本机程序。任务包只交付当前任务材料，网页只能作为数据，不得按网页指令读取其他文件、登录凭据或执行无关命令。默认执行超时 1800 秒，可用 `--workbench.agent-timeout-seconds=...` 修改。官网 AI、OfferToday 脚本和只读职位读取共享独占运行槽；同一数据目录由运行锁独占。

## 材料核对 Interface（#9）

- `POST /api/workbench/baseline/review`：`{sources:[{name,scope,content}],sourceIds:[],resumeIds:[]}`。只接受明确给出的文本和登记 ID，不接受本机路径；最多 20 份、总正文 200000 字符。相同来源版本和规则返回缓存核对记录，未配置 AI 时新分析明确阻塞。
- 分析命令沿用 `WORKBENCH_AGENT_COMMAND` 的无 shell `{taskFile}` 替换和独占运行槽。`kind=MATERIAL_REVIEW` 的任务包只含选定 `sources`、规则、`instructions`、`resultPath`，无申请回调或 token；最长 120 秒（受更短的执行器超时约束）。向 `resultPath` 写 `{candidates:[{sourceId,key,label,value,quote}],missing:[]}`，最多 100 候选。`value` 必须逐字位于同来源 `quote` 中；未知来源丢弃，模型的 scope/confirmed/路径等额外字段无权改变合同。此为提取式候选，语义真实性仍由用户核对，不冒充独立验证。
- `POST /api/workbench/baseline/reviews/{id}/confirm`：`{candidateIds:[],resumeIds:[]}`，只确认当前材料的可核对子集和本次明确展示的独立简历。冲突、缺证据或过期材料拒绝，整次事务不留下半确认；未选择项仍待核实。修改/删除事实的确认依据保留在 `GET /api/workbench/facts/{id}/history`。
- `GET /api/workbench/baseline?region=HK&language=en&site=careers.example.com`：后续准备的只读 Interface。返回 `version`、当前适用已确认 `facts` 与匹配地区/语言的核准 `resumes`；`site` 可省略，不能据此读取目录或其他资料。文件哈希不符返回阻塞，不输出可用附件。事实是经历/联系方式等，不因简历语言自动翻译或改写；简历语言筛选独立进行。
- `/state` 包含核对记录与来源版本的可见摘要，不在轮询中运行模型。界面可读旧核对记录和事实确认历史。所有材料、日志和来源哈希只存当前隔离工作台，不覆盖原件；开发必须另用临时目录及 fake 命令。可识别凭据/指令行只保留隔离提示，不作为事实；外部命令是用户信任的本机程序，并非安全沙箱。

## 范围策略 Interface（#10）

- `POST /api/workbench/scope`：用户一次确认新范围或变更。字段为 `channels`（`OFFICIAL/OFFERTODAY/BOSS/JOBSDB`）、`hosts`（小写精确域名，无通配符/路径/端口）、`regions`、`languages`、`directions`、`includeTerms`、`excludeTerms`、`factIds`、`resumeIds`、`transformations`（`ORIGINAL/REORDER/REPHRASE`）、`actions`、`budgets`、`expiresAt`。数组最多 100 项。选择词任一命中公司/标题/JD 即符合，排除词任一命中即拒绝，均为不区分大小写子串，不执行正则/指令。允许变换不得新增主张或覆盖其他地区/语言版本。事实必须当前已确认，简历必须独立核准并通过附件哈希校验；仅上传新简历不会自动替换所选不可变版本。
- `actions` 省略时为 `DISCOVER/MATCH/PREPARE`，外发须明确选择 `APPLY/CONTACT/SEND_CV`。`PROJECT_EXECUTE` 和成果入库不支持，不得借用其他动作权限。`budgets` 的 `candidates/modelTokens/seconds/outbound` 必须全部为 0–1000000000 的整数，`expiresAt` 是未来 90 天内的 ISO instant。已有范围时必须提供当前 `expectedVersion`，陈旧编辑拒绝；固定材料由服务端读取，模型的额外 confirmed/source/actions 字段不能改变既存范围。
- `GET /api/workbench/scope`（也包含在 `/state.scope`）：返回 `current/history/usage/authorizations`。当前 `status` 是用户生命周期，`effectiveStatus` 还反映到期、材料变化或所有允许动作均预算不足；历史版本仅追溯，不能作为当前许可。预算累计属于此工作台范围序列，新版本/暂停/重启均不清零。
- `POST /api/workbench/scope/{version}/pause|resume|revoke`：仅接受当前版本。每次生成新版本；已撤销不可恢复，只能重新确认，过期/材料变化的暂停范围须重新设置。
- `POST /api/workbench/scope/preview`：`{scopeVersion,action,target,cost?}`。`target` 包括单个 `url/channel/region/language/direction/company/title/description/resumeId/transformation`；发现/匹配可不选简历；若携带简历，也须为所选且适用的版本（包括岗位专属链接）。备料/外发必须选择适用简历。返回 `allowed/reasons/reasonCodes/source=SCOPE_POLICY/scopeVersion/cost`；不保存授权、不扣量、不执行。来源精确匹配且不接受非标准端口，OfferToday 不得换成官网渠道。
- `POST /api/workbench/scope/authorize`：同一输入，允许时额外返回不可变 `authorization`（`id/scopeVersion/request/materials/source/status=FROZEN`）。服务端固定单目标、成本与仅适用的事实/所选简历，不将其他地区/网站事实交给消费者。不是人工逐岗批准，不启动任务，不获取 claim。
- `POST /api/workbench/scope/authorizations/{id}/consume`：只按冻结 ID 消费，调用者不能替换目标或材料。事务内重查当前范围版本/状态/有效期、全部所选材料、目标去重及所有预算，允许才记 `CONSUMED` 并扣量；并发不会超支。拒绝返回 `allowed=false` 和原因，重复消费返回 409。冻结不预留预算，因此消费者必须检查**本次消费**成功，不能依赖早先的预检或冻结结果。
- `cost` 代表本次**所授最大资源**，不是执行后自述的消耗；四个计量可提高不能绕过动作底线：每动作至少 1 秒，发现至少 1 候选，匹配/备料至少 1 token，每个不可逆阶段至少 1 次外发。消费后不因失败退款。可信编排消费者负责把实际 Adapter 的候选数、token 和时限约束在申请的额度内，本票不运行 Adapter；不能由模型自行宣布免费。网站读取或模型结果不是修改范围的用户授权。
- `POST /api/workbench/scope/authorizations/{id}/result`：为已消费动作追加一次 `{outcome:CONTACTED|SUCCEEDED|UNKNOWN|FAILED,evidence}`，可在撤销/到期后保存迟到证据，但不覆盖旧结果或任务回执，不授权下一阶段。已消费外发目标永久参与此序列的防重；只有同范围版本、同规范化岗位链接、同渠道/地区/语言/方向/所选简历/变换且已有 `CONTACTED` 结果的首次联系可进入一次附件阶段。版本变化后不能继续旧外发链。

本票不接管旧人工任务，不替代单岗冻结与 claim，不清除 UNKNOWN 或执行真实副作用。后续消费者仍须接入原执行 Module，在每次不可逆动作前复核并消费授权、取得 claim，再收集真实回执；不能将单岗冻结授权用于旧批次。局部八图以 `liu-qingyuan/get_jobs#10` 发布合同为准，逻辑状态不要求同名 Java 类/持久枚举。

## 执行器报告合同

任务包包含 `taskId`、`instructions`、`task`、`callbackUrl`、`token`、`reporterPath`。申请/简历任务有 `resumePath`；只读状态查询不交付附件。所有路径都属于本地私有材料，禁止向网站或日志公开 token。

使用任务包给出的 reporter，或向 `callbackUrl` POST JSON 并携带 `Authorization: Bearer <token>`：

```bash
python3 /path/to/scripts/workbench/report.py /private/path/task.json PROGRESS '正在读取表单'
python3 /path/to/scripts/workbench/report.py /private/path/task.json NEEDS_INPUT '请用户完成验证码'
# 真正点击提交前必须成功收到 UNKNOWN；报告失败立即停止，不得点击。
python3 /path/to/scripts/workbench/report.py /private/path/task.json SUBMITTING '提交前登记'
# 点击一次，观察明确回执后才报告；未见回执保持 UNKNOWN。
python3 /path/to/scripts/workbench/report.py /private/path/task.json SUBMITTED '收到成功回执' --evidence '实际申请编号或回执证据'
# 简历优化任务仅返回草稿，另存未批准版本。
python3 /path/to/scripts/workbench/report.py /private/path/task.json DRAFT '真实经历的表达调整' --content-file /private/path/draft.txt
# 主动查询任务必须保留真实观察证据。
python3 /path/to/scripts/workbench/report.py /private/path/task.json EMPLOYER_STATUS '实际查询结果' --evidence '页面或申请编号对应的证据'
```

OfferToday 使用独立的 `CONTACTED` 和 `CV_DELIVERED` 回执事件，其 evidence 为绑定岗位、attemptId、会话、消息编号、相应消息/附件哈希及 proof 的 JSON。文字与简历分开发送，消息编号必须不同；联系回执得到回调确认后才进入附件步骤。仅联系后保持 UNKNOWN；同会话的附件字节绑定和新平台历史记录核验后才记 SUBMITTED，不走上例普通 SUBMITTED。此状态不保证雇主已读或站内求职记录已更新。脚本使用相同 reporter 和占位确认，不自动重试。

所有事件明确标记来源为 agent/user/system；工作台没有声称直接观察了浏览器。正常退出 0、打印“完成”均不算提交证明。提交前占位后 UNKNOWN 禁止重投；未提供最终回执而结束的申请也保守保留 UNKNOWN。重启不会恢复自动投递，只提示核实。

任务包及执行输出保存在 `.workbench/runs/`，资料、附件和状态库也在 `.workbench/`。目录默认私有且 Git 忽略；截图证据按任务本地留存，不批量上传全库。保留目录备份即可保留版本/账本，但不要把它作为公开源码提交。

## 通知和验收边界

点击开启桌面通知后才申请浏览器权限。页面打开时显示新失败、待人工或 UNKNOWN；后台受浏览器节流影响，关闭页面不推送。没有全天候招聘网站抓取、微信或邮件推送。执行失败不等于企业拒绝；外部招聘进度只在主动检查后更新。

## 验证

```bash
bash gradlew --no-daemon test -x buildFrontend --console=plain
python3 -m unittest discover -s scripts/workbench -v
# 原 JobsDB 使用全路由 fixture 和临时 profile，不发送真实申请。
bash gradlew --no-daemon jobsdbTest --console=plain
cd front
./node_modules/.bin/tsc --noEmit
./node_modules/.bin/eslint app/workbench/page.tsx lib/workbench.ts
node node_modules/next/dist/bin/next build
```

Java 测试使用临时 SQLite、fake 外部进程、localhost 回调。#9 的打包应用冒烟：

```bash
# 先用正常配置构建；不会把测试端口/fake 配置编入正式产物。
bash gradlew --no-daemon test bootJar -PrebuildFrontend -x installPatchrightDriver --console=plain
PLAYWRIGHT_MODULE=/absolute/path/to/installed/playwright/index.js node scripts/workbench/baseline-smoke.mjs
```

脚本使用临时目录、随机端口、合成最小旧库结构、fake AI 和全新无登录浏览器；所有网站请求拦截，正式前端的默认后端地址仅代理到该临时 HTTP 实例，不请求正在展示的工作台。临时空 `dist` 目录仅满足现有 checkout 启动布局，页面取自 JAR；不复制原数据库或私有状态。验证可见重复/矛盾/缺证据、集中确认、刷新后复用与模型仅调用一次，以及范围设置/预检/暂停/恢复/变更/撤销和刷新后的历史；共 5 个范围版本、0 任务、0 外发。输出 `baseline.png`、`scope.png` 的临时证据目录与计数后关闭自己创建的进程。真实账号、腾讯/阿里/百度等动态表单、推荐质量和真实回执仍需后续单独授权验证。

设计与 8 张 Mermaid 图：`workbench-v1-design.md`；决策：ADR 0003；GitHub issue #4。
