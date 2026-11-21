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
3. **求职范围**：在新页签一次选定精确来源、渠道、地区/语言/方向、岗位选择词和排除词、已确认事实、独立核准简历、变换、动作、累计预算和有效期。默认只读发现/匹配/准备，无外发和项目执行许可。先保存再用同一代表性岗位预检，查看当前范围版本、策略来源和所有拒绝原因。可暂停、恢复、变更、撤销及回读历史；这些操作均产生新版本，旧未执行授权不复活。新版本补充额度应提高累计上限，不清零已耗量。保存范围本身不启动执行器；默认候选主屏可另行显式启动只读发现，不将 Boss/JobsDB 原批次显示成已受控接通。
4. **候选与发现（默认主屏）**：一次填写获准官网入口和 OfferToday 港区列表，设置本次页面、每页及匹配/备料/执行资源上限，点击「一次启动本轮求职」。自动衔接发现、匹配、备料及范围明确允许的逐岗执行，不要求二次启动、逐阶段推进或逐岗审批；仅准备权限停留可见材料，不创建发送任务。整轮进展展示单岗材料版本、阶段、例外与回执；处理必要例外或补充范围累计预算后恢复**同一轮**，只继续已知未外发断点，已完成和 UNKNOWN 不重放。独立阶段按钮收纳为高级入口，不是主流程。在同一独占槽读取两种来源及必要详情；完整候选显示规范链接、平台身份、全文 JD、取得时间。描述不足/失效/范围拒绝不会进入匹配。达到页面或累计资源上限、连接/版本例外时保留进度；可按当前范围继续，不清零计量。官网必须配置支持 `CANDIDATE_READ` 的可信通用执行器；OfferToday 复用已授权连接，不创建登录态，不点 Chat/Apply、不上传/发送。技术通过不代表真实覆盖或匹配质量已验收。
5. **手工任务与历史（次要入口）**：选地区、语言、方向、链接及已批准的匹配简历；读 JD 后决定是否批准。批准冻结当前材料，不自动同步其他版本。未选简历的任务仅可作为待办；需另建绑定正确版本的任务后执行。去重以详情路径和可识别的岗位编号参数为准，不用推广/来源参数区分岗位；同路径仅靠非标准参数区分职位时先人工核对规范详情链接，宁可暂停也不自动重投。
6. **官网/简历 AI**：必须另外配置执行器。旧人工任务仍保留批准后显式启动；新整轮由当前范围与可证实材料策略核准，不伪造人审，启动后自动逐岗执行。未配置时按钮禁用，不显示虚构进展。模型生成的新简历由 `DRAFT` 事件另存为未批准版本，不能直接用于申请。
7. **项目补强**：先登记差距、计划、建议目录，不自动执行代码，也不把计划当作已完成经历。
8. **招聘状态**：官网渠道已投递或结果未知的申请可显式点击“检查最新进度”，启动只读 AI 查询。查询不上传材料、不修改官网资料、不允许申请；真实观察及证据回写原申请的招聘状态和检查时间，不改写执行结果。OfferToday 本版只支持在平台人工核对后登记证据，不自动借用官网 AI 查询。

JobsDB 默认候选页已接入有界只读列表/完整详情、统一匹配与独立备料；平台页仍只读显示最近 100 条原账本并保留 CLI 交接。**工作台不通过 HTTP 直接启动 Boss/JobsDB 的交互式 apply 或批次**；一个岗位的批准不是整个批次的授权。OfferToday 是另行受控的单岗脚本路径，使用原审核/回执合同，不触及 Boss/JobsDB 批次。原平台配置、在线简历和 Cookie 不会因工作台资料更新自动改写。

## OfferToday HK

**OfferToday 已实现基于实站控件和已验证原生客户端的单岗脚本；真实发送验收仍未授权或进行。** 审核、启动、文件字节校验与分阶段回执通过隔离测试；未知页面或站点版本会阻塞，不保证每种实站变体可执行。验证边界见 issue #6 和下文设计记录。

在平台入口或任务渠道中选择 OfferToday。先在普通 Chrome 登录，并通过官方 Playwright 扩展只共享所需标签页，不必重新登录或复制登录态。平台页保留的旧「读取当前职位」仅取已加载卡片，选择后填入待审表单；默认候选页的新只读发现按当前范围和预算读取列表及必要详情，不会创建或发送申请。两者都不猜测未加载的远端分页。

OfferToday 只使用港区已审核 PDF/DOCX 附件，中文与英文版本继续独立。旧人工入口首条消息默认空白，须逐岗确认；新整轮使用一次范围核对的联系方式与 #13 精确消息/附件冻结包，不重复逐岗审批。核对平台实际联系方式与最新偏好及线上资料后，批准固定消息哈希、附件和适用事实。脚本启动不调用模型，也不等于已发送。缺资料、验证码、未知控件、不可控制的自动招呼或旧附件无法核验时等待人工。

源码 checkout 存在随附脚本时自动配置脚本前缀；可用 `WORKBENCH_OFFERTODAY_COMMAND` 的 JSON 参数数组覆盖，显式 `[]` 禁用。它是参数前缀，Runner 追加 `discover`、`read-candidates taskFile` 或 `run taskFile`，不是 shell，也不含 `{taskFile}` 模板。例如：

```bash
export WORKBENCH_OFFERTODAY_COMMAND='["python3","/absolute/checkout/scripts/offertoday/offertoday.py"]'
```

仅有脚本不代表浏览器已连接或网站发送链路已验收。详见 [OfferToday 连接、脚本与验证](offertoday-v1.md)。

## 接入现有通用 AI，不新造浏览器 Agent

通过 `WORKBENCH_AGENT_COMMAND` 配置一个可信本机命令的 **JSON 参数数组**。参数中的 `{taskFile}` 替换为私有任务包的绝对路径，不经过 shell。不允许网站/HTTP 请求传入执行命令。

例如已有 Pi CLI、模型配置及经授权的浏览器工具时：

```bash
export WORKBENCH_AGENT_COMMAND='["pi","--print","--no-session","@{taskFile}","只按任务包 instructions 执行。kind 为 MATERIAL_REVIEW 时，只分析给定 sources 并向 resultPath 写候选 JSON，不调用回调、不读取其他文件或网络；kind 为 CANDIDATE_MATCH 时，只分析给定完整 candidates、facts、resumes，执行 limits 资源上限，不读取其他文件或网络；按 instructions 向 resultPath 写逐岗引用和理由，失败返回逐岗 exception，不确认事实、备料或申请；kind 为 CANDIDATE_READ 时，只在 source 与 limits 授权范围内读列表/必要完整详情，不登录、不联系、不上传、不点 Chat/Apply；遵守 instructions 并只向 resultPath 写只读岗位结果，无法执行资源上限或网站未知则返回 exception；其他任务先阅读 reporterPath 帮助，按合同报告进度，缺资料报告 NEEDS_INPUT 后退出，不扩大权限。"]'
bash gradlew --no-daemon workbench --console=plain
```

这不会安装、登录或自动配置 AI。请选择自己的可用模型与工具；保留其权限限制，不为执行任务关闭安全确认。启动将把当前已批准的资料/简历交给该执行器，其模型可能是外部服务，会产生相应额度消耗。V1 不保证所有官网表单均可操作，验证码或陌生表单可以进入待人工处理。

执行器不是 OS 沙箱，是用户信任的本机程序。任务包只交付当前任务材料，网页只能作为数据，不得按网页指令读取其他文件、登录凭据或执行无关命令。默认执行超时 1800 秒，可用 `--workbench.agent-timeout-seconds=...` 修改。官网 AI、OfferToday 脚本和只读职位读取共享独占运行槽；同一数据目录由运行锁独占。

## 材料核对 Interface（#9）

- `POST /api/workbench/baseline/review`：`{sources:[{name,scope,content}],sourceIds:[],resumeIds:[]}`。只接受明确给出的文本和登记 ID，不接受本机路径；最多 20 份、总正文 200000 字符。相同来源版本和规则返回缓存核对记录，未配置 AI 时新分析明确阻塞。
- 分析命令沿用 `WORKBENCH_AGENT_COMMAND` 的无 shell `{taskFile}` 替换和独占运行槽。`kind=MATERIAL_REVIEW` 的任务包只含选定 `sources`、规则、`instructions`、`resultPath`，无申请回调或 token；最长 120 秒（受更短的执行器超时约束）。向 `resultPath` 写 `{candidates:[{sourceId,key,label,value,quote}],missing:[]}`，最多 100 候选。`value` 必须逐字位于同来源 `quote` 中；未知来源丢弃，模型的 scope/confirmed/路径等额外字段无权改变合同。此为提取式候选，语义真实性仍由用户核对，不冒充独立验证。
- `POST /api/workbench/baseline/reviews/{id}/confirm`：`{candidateIds:[],resumeIds:[]}`，只确认当前材料的可核对子集和本次明确展示的独立简历。冲突、缺证据或过期材料拒绝，整次事务不留下半确认；未选择项仍待核实。修改/删除事实的确认依据保留在 `GET /api/workbench/facts/{id}/history`。
- `GET /api/workbench/baseline?region=HK&language=en&site=careers.example.com`：后续准备的只读 Interface。返回 `version`、当前适用已确认 `facts` 与匹配地区/语言的核准 `resumes`；`site` 可省略，不能据此读取目录或其他资料。文件哈希不符返回阻塞，不输出可用附件。事实是经历/联系方式等，不因简历语言自动翻译或改写；简历语言筛选独立进行。
- `/state` 包含核对记录与来源版本的可见摘要，不在轮询中重新整理材料。界面可读旧核对记录和事实确认历史。所有材料、日志和来源哈希只存当前隔离工作台，不覆盖原件；开发必须另用临时目录及 fake 命令。可识别凭据/指令行只保留隔离提示，不作为事实；外部命令是用户信任的本机程序，并非安全沙箱。

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

## 候选读取 Interface（#11）

- `GET /api/workbench/candidates` 和 `/state.candidates`：`jobs/runs`。候选状态为 `READY/INCOMPLETE/EXPIRED/REFUSED`，包含 `url/channel/platformJobId?`、来源列表、地区/语言/方向、全文、取得时间、范围版本及合并次数；同身份后续只返回卡片时保留已取得全文、原来源及原取得时间，单列 `lastSeenAt/lastReadComplete` 并在主屏提示未取得新全文；旧全文与本次新卡片描述共同核验当前范围，不丢弃任一侧排除词；`lastReadDescription` 保留并展示本次卡片描述，不冒充新全文。这是只读资料，不是申请、匹配或送达证明。
- `POST /api/workbench/candidates/discover`：`{scopeVersion,sources:[{channel,url,region,language,direction}],maxPages?,jobsPerPage?,secondsPerPage?,modelTokensPerPage?}`。支持 `OFFICIAL/OFFERTODAY/BOSS/JOBSDB`，最多 10 个固定来源；本次页面 1–20（默认 2），每页槽位 1–40（默认 5）、秒数 1–120（默认 30）、官网模型 token 1–100000（默认 1000）。详情属于本页资源；每页开始前按整页上限原子消费，重复/失败/不足不退款，OfferToday 不消费模型额度。每个候选再按真实内容重核范围，不接受模型额外 actions/confirmed/路径。仅剥离 `utm_* / ref / referrer / source / trackingid / gclid / fbclid` 推广参数，其余 opaque 查询身份保留；不改变旧申请防重。
- 返回轮次为 `DISCOVERING → COMPLETED` 或 `CHECKPOINT`，附逐来源游标与可见事件；来源没有更多已知只读页面才标记结束，不承诺全站覆盖。`{scopeVersion,runId,...本次资源上限}` 继续旧断点；来源不变、当前范围重新核验、累计预算不重置。进程中断后保留已存完整页、恢复 CHECKPOINT，不自动重试或启动其他渠道。
- 官网命令收到 `kind=CANDIDATE_READ`、`source`（本页 URL 和 cursor）、`limits`（四个计量）、`instructions/resultPath`；没有材料、申请任务、回调 token 或 claim。可信命令必须能限制实际模型消耗/读取来源，否则返回 `{exception:原因}`；本机程序不是沙箱。只写 `{jobs:[{url,title,company,description,descriptionComplete,available?}],nextUrl?,cursor?,note?}`，每页不超过已授候选槽，结果最大 2MB。完整 JD 需正向 `descriptionComplete` 且至少 120 字符，超过 30000 字符拒绝；仅标题/卡片不冒充完整描述。下一页仍按当前精确来源/渠道重核，未授权不启动进程。
- OfferToday 使用现有已附着会话，固定已知原生客户端版本，独立 `candidate_page` 只调用公开 `get_getJobDetail`，不打开聊天/附件选择器。`jobDesc` 非已支持全文 schema 时保留 INCOMPLETE，不猜测字段或使用投递按钮探查；只遍历当前已加载卡片的有界游标，尚未加载的远端分页不猜测。没会话、版本变化、身份不符或当前有未保存草稿/弹窗均为可见例外。真实只读采样另需授权。

## 候选匹配 Interface（#12）

- 默认候选主屏勾选 1–20 岗，设置批次秒数/每岗 token 后点击批量分析。展示直接/可迁移部分支持、必要核实、真实缺口、硬性条件不足和地区/语言不适用，以及 JD 原文、事实/材料引用、版本、缓存与例外。没有 AI 时明确不可分析；没有获准真实代表样本，不声明推荐质量已验收。
- `POST /api/workbench/candidates/match`：`{scopeVersion,candidateIds?,secondsPerBatch?,modelTokensPerCandidate?}`；省略 ID 时选择全部（总数仍须 1–20）。秒数 1–120（默认30），每岗 token 1–100000（默认1000）。每个未命中岗位独立核对来源/地区/语言/方向/选择词/有效期和 MATCH 权限，并预扣该岗全部秒数/token，上述批次硬截止受更短执行器超时约束；失败不退款。缓存命中不消耗预算。
- 显式启动后保存此批候选及资源上限。`GET /candidates`、`/state` 和 `/candidates/gaps` 对此批相关输入变化自动作有界重评；相同输入（含失败）在重跑、轮询和重启后不调用模型。未启动过的批次不会仅因读状态调用模型。共享槽忙时先返回旧结果并标记 `stale`，空闲后的读取再重评；暂停/撤销/到期不启动新模型。重新 POST 选择下一批，不形成后台无限队列。范围/规则版本变更使该批重新核验；仅无关事实/材料变化不影响缓存。中断或失败的相同输入不自动重试，须先解决输入/范围问题。
- 原范围所选 factIds 的当前已确认值可用于**只读重评**；未确认、冲突或不可用项只显示必要核实 ID/标签，不将其值交给模型，不自动加入其他 ID。适用的核准简历按地区、语言及岗位专属链接过滤，不出服务器附件路径。这个读取不续签 #10 通用冻结授权，不解除材料变化对准备/外发的阻塞。
- AI 任务包 `kind=CANDIDATE_MATCH`：`rulesVersion`、`limits`、`candidates`、`instructions/resultPath`。每岗含 `candidateId/jdSha256`、完整 JD 与岗位条件、适用已选 `facts/resumes` 和不含未确认值的 `questions`。没有申请回调、token、附件路径、账号或全库。可信命令不是沙箱，须执行总 token/时间上限，否则返回例外。
- 写出 `{results:[{candidateId,jdSha256,findings:[{kind,requirementQuote,reason,evidence:[{type:"FACT"|"RESUME",id,quote}],suggestion?:{goal,acceptance}}]}]}`；每岗最多30理由、每理由最多20引用，整份输出最多2MB。`kind` 为 `SUPPORTED/PARTIAL/NEEDS_FACTS/EXPERIENCE_GAP/HARD_REQUIREMENT/NOT_APPLICABLE`。必须回显本次完整 JD 哈希，岗位原文必须来自全文，引用必须逐字来自该岗的事实值/核准简历正文；支持、部分支持、硬性不足及真实缺口必须有引用，缺证据不能推断经验不足。任何额外 score/actions/facts/路径等字段均为越权例外。每岗也可返回 `{candidateId,exception}`；该岗伪造引用、遗漏 JD 或失败不阻断其他有效结果；整个进程失败则逐岗保存例外。
- `jobs[].analysis` 包含 `id/status/stale/scopeVersion/rulesVersion/jdSha256/questions/findings/exception`。状态为 `NOT_ANALYZED/ANALYSIS_READY/NEEDS_FACTS/EXPERIENCE_GAP/ANALYSIS_EXCEPTION/INELIGIBLE`；当前输入可复用旧分析，历史引用不改写。`GET /api/workbench/candidates/gaps` 返回 `{suggestions:[{candidateId,analysisId,scopeVersion,requirementQuote,suggestion}]}`，只提供当前有效缺口建议供项目切片读取；不创建任务、不执行项目、不生成/批准可外发材料，也不产生新事实。

## 自动备料与单岗核准 Interface（#13）

- 默认候选主屏沿用本批勾选，一次准备 1–20 岗；展示策略核准、仅草稿、材料例外及当前失效状态，独立文件/父版本/正文/实际附件和正文 SHA256、匹配 ID、适用事实及其版本、精确消息。可下载实际独立文件，不覆盖四类基线。没有创建申请、启动执行器外发或送达断言；已有人工任务/人审事件不改写。
- `POST /api/workbench/candidates/prepare`：`{scopeVersion,candidateIds?,secondsPerBatch?,modelTokensPerCandidate?}`。省略 IDs 选择全部（仍限 1–20）；秒数 1–120（默认30），每岗 token 1–100000（默认1000）。仅当前完整 JD、有依据的有效匹配且无必要核实/经验缺口/硬性不足/不适用的候选进入准备。预扣每岗全部秒数/token，批次硬截止受更短执行器超时约束，失败不退款；相同范围/岗位/匹配/事实/材料/规则输入跨重启复用（含失败），不会因为状态读取重跑模型或自动续签。范围已变化时须重新确认。
- 准备命令复用已配可信通用 AI 和独占槽，`kind=MATERIAL_PREPARE` 包含 `rulesVersion/limits/instructions/resultPath/candidates`；每岗只提供 `candidateId/target/facts/resumes/findings`，事实/简历均适用且已选，不含附件路径、申请回调、token 或资料全库。可信命令不是 OS/模型沙箱，须执行总 token/时间上限，否则报例外。
- 输出 `{results:[{candidateId,resumeId,transformation,content?,factIds?,initialMessage?}]}`，每岗也可 `{candidateId,exception}`。`ORIGINAL` 复用所选核准基线的原字节，若有 content 必须精确等于基线正文；自动正文核验只支持 UTF-8 TXT/MD 和本 Module 的规范 DOCX，不以侧车文本证明任意 PDF/Office 正文。`REORDER` 的 `factIds` 必须唯一且属于该岗适用已确认集合，正文只能是所选 `fact.value` 的完整原值按顺序以两个换行连接，服务器确定性另存 DOCX。`REPHRASE`、新增主张、不精确相等的正文或消息仅存 `DRAFT_ONLY`；未知字段（含 verifier/approved）、不适用材料/事实、文件核验失败为 `MATERIAL_EXCEPTION`。有效 ID 或 AI 自称通过不等于语义支持。
- OfferToday 还须在 `/scope` 一次提交 `contactFactIds:[范围 factIds 内适用已确认联系方式]`、`contactVerified:true`，代表核对实际联系方式、最新偏好和线上资料；字段仅支持 `email/contact_email`、`phone/contact_phone/mainland_phone` 且值须为完整邮箱/电话格式，普通经历事实或格式不符会拒绝范围确认，缺少时先澄清资料。字段/格式不是平台实际资料的独立证明；界面提供明确选材与核对，不继承旧逐岗人审。最终附件须可核验 PDF/DOCX（当前窄路径生成 DOCX），文本稿不得核准。消息固定为 `您好，我希望申请此职位，随附简历供参考。谢谢。` 或 `Hello, I would like to apply for this position. Please find my CV attached. Thank you.`，按岗位语言冻结精确原文和 SHA256；模型只能原样建议，不可改写经历。
- `GET /api/workbench/preparations` 与 `/state.preparations` 返回 `{packages:[...]}`。每包包含 `id/candidateId/analysisId/scopeVersion/source=SCOPE_POLICY/rulesVersion/target/status/reason/proposal?/snapshot?`；`snapshot` 包含独立 `resume/facts/initialMessage/initialMessageSha256` 及 OfferToday 的 `contactVerified/contactFacts`。`target.resumeId` 是范围所选基线 ID；`snapshot.resume.id` 是实际冻结文件的版本 ID，派生时不同。后续授权按范围基线核验，实际执行只能使用该包冻结文件，不偷偷换回原文件。
- `POST /api/workbench/preparations/{id}/check`：`{action:PREPARE|APPLY|CONTACT|SEND_CV}`，返回 `allowed/source/packageId/reasons`。每个不可逆阶段必须重核范围版本/有效期/暂停撤销、动作与变换许可、动作预算、当前匹配/事实/文件、原申请和策略已消费外发目标的防重；文件或事实变化不能重新授权 UNKNOWN。此入口只读、不创建授权、不扣量、不 claim/启动/发送，也不代表渠道执行能力可用。后续消费者须另行消费单阶段授权、确认 claim 并收集相应真实回执；不能复用核验响应作重试或下一阶段许可。`INVALIDATED` 是当前读取视图，历史核准依据不覆盖。局部八图以 `liu-qingyuan/get_jobs#13` 发布合同为准。

## 连续求职周期 Interface（#14）

- `POST /api/workbench/cycles`：一次启动，返回持久轮次 `id/status=RUNNING`，后台独占同一运行槽自动推进。请求沿用候选发现的 `scopeVersion/sources/maxPages/jobsPerPage/secondsPerPage/modelTokensPerPage`，以及匹配/准备的 `secondsPerBatch/modelTokensPerCandidate`；新增 `secondsPerJob` 1–120（默认30）、`modelTokensPerJob` 1–100000（默认1000）。按最多20岗批次处理，不截断丢弃超额候选。读取页数上限到达后保留 `CHECKPOINT`，不是无限调度。来源/AI/单岗脚本仍为已配置可信 Adapter，不接受网页/请求指定命令。
- `/state.workflow.cycles` 展示 `stage`（DISCOVER/MATCH/PREPARE/DISPATCH）、`entries/events`，每候选可见材料包、taskId、当前 execution 事件与回执。整轮状态是 `RUNNING/CHECKPOINT/PAUSED/EXCEPTION/PREPARED/COMPLETED`；COMPLETED 表示本轮有界处理结束，不保证全站覆盖、推荐质量或雇主已读。权限只有准备时为 PREPARED，绝不启动外发进程。
- `POST /api/workbench/cycles/{id}/resume`：`{scopeVersion:当前版本}`。必要资料/登录/未知页面解决或累计预算上限补充后恢复同一轮；保留来源游标、候选、缓存与安全任务 ID。相同输入复用匹配/准备（含失败）；事实/范围变化才重新核验，不续签旧冻结快照。已有 RUNNING/UNKNOWN/SUBMITTED 持续去重，UNKNOWN 仅收集/人工核对证据，不重投或继续未开始 CV；其他合格岗位不因此阻塞。暂停/撤销按钮在执行期间可用；已开始动作只允许收集结果，不开始下一阶段。
- 策略任务写 `approvalSource=SCOPE_POLICY` 和单岗 `POLICY_APPROVED` 事件，绑定 preparation 的精确快照，禁止经旧 `/approve` 转成人审。启动前预扣完整秒数和官网模型 token 上限，失败/无 claim 不退款；OfferToday 执行零模型。任务包携带 `limits`，可信官网 CLI 必须执行 token 上限，否则报告 NEEDS_INPUT；不是 OS/模型沙箱。Runner 对进程实施秒数硬截止。
- 每个不可逆阶段由当前执行 token 提交 `SUBMITTING` 并明确 `action=APPLY/CONTACT/SEND_CV`；在同一存储事务中重核目标、范围、事实/匹配、冻结文件，消费该阶段外发预算并取得 claim，HTTP 200 才能动作。执行时间已在启动预扣，不在阶段重复扣；外发每阶段各扣一次。官网一次 APPLY；OfferToday CONTACT 之后记录对应 CONTACTED，再独立 SEND_CV claim，最后新 CV 消息必须同岗/attempt/会话且绑定冻结实际附件哈希。旧 claim、文件选中、退出0或只联系都不是成功。
- 重启不自动外发。原执行子进程有精确 PID/启动时间的私有租约；内置 Python 标准库监护脚本在 POSIX 独立进程组内运行命令，保留组长直到后代全部结束，主命令退出不代表释放。仅有私有 release proof 才清除租约，资源不明时不强杀监护进程并假设成功。重启先确认释放**自己创建的**整组后代，再开放共享槽；PID 复用/身份不明、监护进程异常消失或创建窗口中断时停止，不能杀其他进程或猜测资源已释放；无可靠 POSIX 进程组/Python 环境则明确不可执行。所有旧 callback token 失效；策略任务未登记 claim 的中断保持已知未外发，可恢复；任何已 claim/已联系者保持 UNKNOWN。历史人工任务缺回执仍按旧合同保守 UNKNOWN。

## Boss 候选与渠道能力（#15：外发仍待核实）

候选主屏增加 Boss 只读列表（`https://www.zhipin.com/web/geek/jobs?...`），选择内地地区并在范围中明确允许 `BOSS`、`www.zhipin.com` 及 DISCOVER/MATCH/PREPARE。本副本原 Boss 页面须已由用户显式登录；只读 Adapter 不自动打开浏览器或登录，不读取原国内目录，不拷贝会话。原 Boss 任务忙碌时拒绝；所有只读页面在浏览器拥有线程串行。缺会话也不调用官网 AI。

每次最多读取所授候选槽，列表只取当前已加载卡片，必要详情使用正常导航中实际观察到的同岗只读 GET 响应。保留 opaque 岗位 ID，去除列表跟踪参数；续读游标绑定已加载列表指纹，列表变化时显式重启只读轮次，不自动猜测翻页。未知 DOM/schema、错岗、响应缺失、访问限制及预算耗尽均留例外；完整 JD 才能进入统一匹配/备料。真实只读来源覆盖与推荐质量尚未验收。

| 动作 | 工作台当前能力 / 回执 |
| --- | --- |
| 只读列表与已观察的详情 | 技术链路已覆盖；不是联系/申请证据 |
| 匹配与独立材料准备 | 复用统一记录和策略材料核验；核准材料不等于渠道可执行 |
| 精确首条文本 CONTACT | **待核实，不执行**；旧「立即沟通」可能先发未知自动招呼，DOM 联系提示不绑定核准正文 |
| APPLY / SEND_CV / 在线资料 / 图片 / 重复联系 | **不支持，不执行**；没有可核验送达合同，范围或未知开关不能开启 |

Boss 的外发预检/核准明确返回 `CAPABILITY_UNVERIFIED`，不创建发送任务或 claim，不占外发量、不调用原批次，也不回退官网任务/查询。Boss 草稿消息不声称已随附 CV。原 Boss 单岗审核、配置和历史数据仍保留；只证明联系时不能记 CV 送达或申请完成，退出码或模型判断也不能替代证明。`runtime.bossReadConfigured` 只表示只读 Adapter 已安装，连接在读取时检查；`bossOutboundSupported=false` 与 `bossCapabilityReason` 明示限制。

本票尚欠精确单岗外发验收，保持待能力核实；后续须另获授权的只读核对或脱敏回放证明动作前的精确消息控制与真实回执。开发授权不包含真实联系、上传或试投，也不切换正在运行的工作台。原因及局部目标图见 #15，取舍见 ADR 0003。

## JobsDB 候选与未核实的单岗外发（#16 增量）

默认候选页增加 JobsDB 只读列表；当前范围必须包含 `JOBSDB`、`hk.jobsdb.com`、HK 和允许的语言/方向。来源为 `https://hk.jobsdb.com/jobs?...` 或 `<keywords>-jobs`；不能将 JobsDB 填入官网来源规避渠道检查。默认仅在所配 `workbench.jobsdb-dir` 存在 `venv/bin/python` 且本 checkout 有脚本时安装只读命令；也可用 `WORKBENCH_JOBSDB_COMMAND`（JSON 参数前缀）或 `workbench.jobsdb-command` 配置，`[]` 禁用。Runner 追加 `read-candidates taskFile --data-dir <服务端所配目录>`，请求和网页不能选择执行器或私有目录。

只读任务包无材料/申请 token，仅含固定来源与有界资源。原 CLI 独立会话必须已显式存在，缺会话不新建 profile；同一 `run.lock` 拒绝与原 CLI 并发。导航前整个 BrowserContext（含 popup 首次请求）的守卫仅放行受支持列表/数字详情及窄静态 GET，拒绝所有其他方法、XHR/fetch 和外部请求；只读 Browser 阻止新 Service Worker 注册；如持久 profile 存在 Service Worker 存储目录则在 Chrome 启动前保守拒绝（包括可能没有活跃 worker 的目录），不删除或迁移旧状态；不在此路径解 CF、登录或猜测翻页。每页槽位/时限和累计预算沿用统一候选 Interface，零模型；当前加载列表超过 200 链接报例外，不静默截断。游标绑定列表身份指纹，仅续读已加载项；列表变化时显式重新启动只读轮次。标题/公司/身份 schema 缺失是读取例外，缺/短全文仍 INCOMPLETE，不冒充可匹配。完整候选统一进入匹配与独立 DOCX 准备，沿用一次范围核对的适用港区联系方式与原账本/工作台 UNKNOWN 防重。

`runtime.jobsDbConfigured` 仅表示只读命令配置，读取时才核验会话；`jobsDbProductionVerified=false`、`jobsDbOutboundSupported=false` 与 `jobsDbCapabilityReason` 展示真实限制。JobsDB 原 Profile 会随申请发送，目前无法证明没有任务外资料，review 文件名不证明冻结附件字节，积极回执文案也未绑定本次材料。因此 APPLY/CONTACT/SEND_CV 返回 `CAPABILITY_UNVERIFIED`，不创建发送任务或 claim、不占外发量、不运行旧 apply/batch，也不回退官网 AI 或自动进度查询。

#16 保持 open / needs-triage；受控单岗申请尚未完成，需另获授权的只读核对或脱敏回放来确认精确 Profile/附件/回执合同。开发假站/HTTP 测试不是生产能力或推荐质量验收，没有上传真实 CV、实投或切换可见工作台。局部八图以 #16 本增量评论为准。

## 执行器报告合同

任务包包含 `taskId`、`instructions`、`task`、`callbackUrl`、`token`、`reporterPath`。申请/简历任务有 `resumePath`；只读状态查询不交付附件。所有路径都属于本地私有材料，禁止向网站或日志公开 token。

使用任务包给出的 reporter，或向 `callbackUrl` POST JSON 并携带 `Authorization: Bearer <token>`：

```bash
python3 /path/to/scripts/workbench/report.py /private/path/task.json PROGRESS '正在读取表单'
python3 /path/to/scripts/workbench/report.py /private/path/task.json NEEDS_INPUT '请用户完成验证码'
# 真正点击提交前必须成功收到 UNKNOWN；报告失败立即停止，不得点击。
python3 /path/to/scripts/workbench/report.py /private/path/task.json SUBMITTING '提交前登记'
# 新策略任务必须明确阶段；OfferToday 附件前需独立 --action SEND_CV
python3 /path/to/scripts/workbench/report.py /private/path/task.json SUBMITTING '策略任务提交前登记' --action APPLY
# 点击一次，观察明确回执后才报告；未见回执保持 UNKNOWN。
python3 /path/to/scripts/workbench/report.py /private/path/task.json SUBMITTED '收到成功回执' --evidence '实际申请编号或回执证据'
# 简历优化任务仅返回草稿，另存未批准版本。
python3 /path/to/scripts/workbench/report.py /private/path/task.json DRAFT '真实经历的表达调整' --content-file /private/path/draft.txt
# 主动查询任务必须保留真实观察证据。
python3 /path/to/scripts/workbench/report.py /private/path/task.json EMPLOYER_STATUS '实际查询结果' --evidence '页面或申请编号对应的证据'
```

OfferToday 使用独立的 `CONTACTED` 和 `CV_DELIVERED` 回执事件，其 evidence 为绑定岗位、attemptId、会话、消息编号、相应消息/附件哈希及 proof 的 JSON。文字与简历分开发送，消息编号必须不同；联系回执得到回调确认后才进入附件步骤。仅联系后保持 UNKNOWN；同会话的附件字节绑定和新平台历史记录核验后才记 SUBMITTED，不走上例普通 SUBMITTED。此状态不保证雇主已读或站内求职记录已更新。脚本使用相同 reporter 和占位确认，不自动重试。

所有事件明确标记来源为 agent/user/system；工作台没有声称直接观察了浏览器。正常退出 0、打印“完成”均不算提交证明。提交前占位后 UNKNOWN 禁止重投；未提供最终回执而结束的旧人工申请也保守保留 UNKNOWN；新策略任务无外发 claim 则保留已知未外发断点。重启不会恢复自动投递，只提示核实。

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

Java 测试使用临时 SQLite、fake 外部进程、localhost 回调。#9–#13 的打包应用冒烟：

```bash
# 先用正常配置构建；不会把测试端口/fake 配置编入正式产物。
bash gradlew --no-daemon test bootJar -PrebuildFrontend -x installPatchrightDriver --console=plain
PLAYWRIGHT_MODULE=/absolute/path/to/installed/playwright/index.js node scripts/workbench/baseline-smoke.mjs
PLAYWRIGHT_MODULE=/absolute/path/to/installed/playwright/index.js node scripts/workbench/workflow-smoke.mjs
```

脚本使用临时目录、随机端口、合成最小旧库结构、fake AI 和全新无登录浏览器；所有网站请求拦截，正式前端的默认后端地址仅代理到该临时 HTTP 实例，不请求正在展示的工作台。临时空 `dist` 目录仅满足现有 checkout 启动布局，页面取自 JAR；不复制原数据库或私有状态。验证可见重复/矛盾/缺证据、集中确认、刷新后复用与模型仅调用一次，以及范围设置/预检/暂停/恢复/变更/撤销和刷新后的历史、默认主屏一次取得官网/OfferToday 两来源的完整候选及描述不足例外；随后验证批量匹配、引用、真实缺口建议、必要核实后的局部自动重评及缓存复用；然后通过界面准备独立岗位 DOCX，查看正文/依据、下载文件、只读动作拒绝与跨刷新复用，并在岗位条件变化后展示失效；共 8 个范围版本、3 候选（2 完整 JD）、材料整理 1 次/匹配 3 次/备料 1 次、3 包（核准1/例外2，核准包后来失效）、0 任务、0 外发。输出 `baseline.png`、`scope.png`、`candidates.png`、`matching.png`、`matching-needs-facts.png`、`preparation.png` 与 `prepared-job.docx` 的临时证据目录和实际计数后关闭自己创建的进程。真实账号、腾讯/阿里/百度等动态表单、推荐质量和真实回执仍需后续单独授权验证。

#14 的 `workflow-smoke.mjs` 使用同样隔离约束，从默认页面一次启动官网与 OfferToday，自动连续推进两岗并保留第三岗的登录例外；处理必要例外后在同一轮恢复，不重放已完成阶段/岗位。另在联系后强制终止自己启动的临时 Java 进程，重启确认旧子进程已释放、旧 token 拒绝，保留联系/UNKNOWN 且不发 CV，并可推进其他岗。#15 另在同一临时实例经默认候选页面提交 Boss 只读来源（不提供任何 Boss 会话），验证明确读取例外、可见能力限制、无官网回退和新增外发。#16 再通过默认页面读取合成 JobsDB 候选，验证统一匹配、独立材料与 APPLY 拒绝、0 新任务/外发；JobsDB 假命令只读取临时任务包，不提供账号或原账本。共输出五张合成页面截图及实际计数；不会切换当前工作台，也不是实站发送验收。

设计与 8 张 Mermaid 图：`workbench-v1-design.md`；决策：ADR 0003；GitHub issue #4。
