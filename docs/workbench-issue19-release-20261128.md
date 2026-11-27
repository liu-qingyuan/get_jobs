# Issue #19 工作台发布验收（2026-11-28）

## 9532 实际切换

2026-11-28，依赖 #29 和 #30 关闭并记录真实结果后，经用户授权将 9532 切换到 `0d8c9a3` 的候选包。旧进程经 SIGTERM 退出后，候选 JAR 安装到原运行路径。新进程沿用原工作目录、数据路径及其余参数，仅将 `--workbench.agent-command` 设为 `[]`。状态 API 返回 `agentConfigured=false`，新一轮 AI 匹配与备料等待配置真实执行器。

- Chrome 在 `http://127.0.0.1:9532/workbench` 加载默认候选主屏及全部六个工作台标签。44 个请求均为 9532 同源请求，两次 `GET /api/workbench/state` 均返回 200，9527 和 8888 的请求均为 0。19 个 JS/CSS 的响应与 JAR 内容逐字节匹配。首页、工作台、分析页及嵌套回退路径均返回 200，页面内容与 JAR 一致。
- 只读检查核对了每条既有记录的标识。8 项任务、7 个范围版本、4 份简历、14 条资料和 10 个候选均保留。旧 SQLite 库与磁盘静态导出的校验和一致。候选包检查未发现测试夹具，切换与验收期间真实平台外发为 0。
- 首次切换因浏览器检查将 Next.js 的无障碍路由播报节点计为页面报错而触发回滚。恢复旧 JAR 与启动命令后，原页面哈希、状态 API 和既有记录均通过核对。隔离复现确认播报节点来源，修正检查后重试通过。时间戳备份、启动命令、回滚记录、前后截图与校验结果保存在本机忽略目录 `.workbench/live-evidence/issue19-release-20261128/live-switch/`。

本票保持开启，等待人工验收推荐质量、各渠道当前可用范围及所需的实站回执。[Issue #19](https://github.com/liu-qingyuan/get_jobs/issues/19)

## 切换前的打包修复与复核

候选包 `build/libs/get_jobs-2.0.1.issue19-packaging.jar` 通过隔离打包验收。9532 继续运行旧版。此次调整限于静态资源解析和前端 API 基址，沿用现有 HTTP 接口和业务流程。

- 在临时源码目录运行 `npm run build:prod`，以 Java 21 运行 `sh gradlew --no-daemon bootJar test --console=plain`。Java 默认全量套件共 324 项，305 通过、19 项 opt-in 测试跳过、0 失败。候选包 SHA256 为 `2a8571014e764b815f7e2d797b94754630979845e189a05e98cb5a80378d092e`。包内 97 个 UI 文件逐一匹配静态导出，未发现指向本机 9527 或 8888 的固定 API 地址。
- 旧 JAR 在无磁盘 `dist` 的临时目录复现页面 404。新 JAR 在同样的布局下，`/`、`/workbench`、`/workbench/`、`/boss/analysis` 和嵌套 SPA 回退路径均返回 200。未知 API 和缺失的 JS 资源仍返回 404。
- Playwright 在全新 Chromium 上直接打开临时实例的 `/workbench`，显示候选岗位队列，`GET /api/workbench/state` 返回 200。43 个浏览器请求全部发往该实例同一源。19 个 JS/CSS 的响应字节逐一匹配 JAR。独立项目验证的合成冒烟通过。既有 baseline 和 workflow 冒烟分别在材料核准和首轮逐岗状态处重现超时，见下方初次验收记录。
- 使用同一临时端口、启动参数和合成库演练旧 JAR、新 JAR、恢复旧 JAR。磁盘保留旧导出时，新包仍返回自己的 `/workbench` 和资源，磁盘独有的测试资源也可读取。恢复后页面哈希回到旧版，合成事实记录保留，每次启动的状态 API 均返回 200。

9532 的 PID、命令、数据目录、旧 JAR 和磁盘导出已记录或备份。前后核对监听 PID、静态页面哈希、JAR 及磁盘导出一致。按相同参数重启会打开实际工作台库、获取运行锁并执行中断恢复。页面轮询的 `GET /api/workbench/state` 也可能写入匹配结果和占用范围额度。本轮的合成数据限制涵盖这些操作，因此 9532 的启动与状态验收仍待明确授权。没有执行实际切换或回滚。

后续切换需在允许现有启动恢复和状态检查访问实际运行库的维护窗口完成。正常停止旧进程，将候选 JAR 放到原路径，以记录中的命令、数据路径和参数启动，再核对状态 API、页面、资源哈希和浏览器同源请求。若检查失败，停止候选进程，将备份 JAR 恢复到原路径，执行保存的旧启动命令并复核服务。推荐质量、渠道连接范围和真实回执仍待人工验收。

本轮证据位于忽略目录 `.workbench/live-evidence/issue19-release-20261128/packaging-follow-up/`。其中 `package-proof.json` 记录 HTTP、浏览器和演练结果，`previous.jar`、`previous-dist/` 和 `previous-launch.sh` 保留回滚所需的旧产物与启动记录。

## 初次发布结论

暂停切换。以干净的 `main` `109f8acbf5366144c82a6220e735da019c2f49e8` 导出源码，在临时目录重建 Next.js 静态资源并执行 `test bootJar`。同一 JAR 的直接浏览器访问未能从该实例加载候选主屏。合成回归中通过测试端转送验证了部分业务链路。[Issue #19](https://github.com/liu-qingyuan/get_jobs/issues/19)

- Java 21 全量默认测试 **324 项，305 通过，19 跳过，0 失败，0 错误**。跳过的是环境变量控制的 18 项 Boss DOM 与 1 项 session-cookie 测试。构建成功。
- 待发布 JAR SHA256 `b4b76f1f63e963a486aa3358194fd62c10fd4ea24e1b2ca4e3ff391e19a020ff`。保存在忽略的 `build/libs/get_jobs-2.0.1.issue19-109f8ac.jar`。
- 包内有 97 个 UI 资源。检查 JAR 路径和应用自有资源内容，未发现验收夹具地址、合成材料、假执行器、验收标记或私有目录。界面保留了既有的 `careers.example.com` 输入框示例。

## 直接访问的阻塞

| 场景 | HTTP 和 Chrome 结果 |
| --- | --- |
| 临时工作目录只有 JAR 和临时运行数据 | `GET /api/workbench/state` 为 200。首页和 `/workbench` 为 404。静态资源注册依赖磁盘上存在 `dist` 或 `static` 目录。 |
| 临时工作目录补一个**空** `src/main/resources/dist` | 页面和 API 均为 200。页面与 19 个浏览器加载的静态资源逐一匹配 JAR SHA256。浏览器请求由前端编译配置指向 `http://localhost:9527/api/workbench/state`，候选主屏未出现。测试在请求离开 Chrome 前拦截。9527 当时已有独立实例。 |
| 9532 使用现有工作目录 | `/workbench` 的 200 响应与现有磁盘 `dist` 及旧 JAR 哈希一致，与新 JAR 不同。静态资源解析优先使用磁盘。仅替换 JAR 无法证明该 URL 提供新版 UI。 |

隔离场景中所有真实浏览器访问均限制在本次创建的回环实例。正向路径用 Playwright 转送写死的 9527 API 请求到实际临时 HTTP 后端。该转送仅用于取证。定位涉及 `StaticResourceConfiguration.java`、`front/lib/api.ts` 和 `front/server.config.js`。

## 合成技术验收

- 在候选界面一次确认仅准备范围，以一次 `POST /cycles` 读取官网、OfferToday 和 JobsDB 夹具。5 个候选均有匹配证据与独立 `SCOPE_POLICY` 材料版本。5 份下载字节均匹配冻结 SHA256。共用同一合成事实，确定性渲染字节相同，每岗的目标、包和版本 ID 独立。0 申请任务，0 外发计量。Chrome 中暂停、撤销后 HTTP 状态一致。
- 官网和 OfferToday 回执回归只运行本地替身。已核对官网申请回执，以及 OfferToday 独立的 CONTACT 和 CV 回执。联系后暂停的场景保留 CONTACT 证据及 `UNKNOWN`，无 CV 回执，界面显示「未核实 CV，不计完成」。替身计量分别为 6 和 3，实际外发为 0。
- Boss 合成界面显示 `PROFILE_MISSING`，受控 CONTACT 依赖已登录会话，`bossCvSupported=false`。JobsDB 保留只读、受控单岗 APPLY 和批次/联系/附件限制。平台入口仍写着「工作台外发保持禁用」，与材料区受控单岗入口及 `jobsDbSingleJobSupported=true` 并存。OfferToday 平台入口的一般提示未区分既有受控人工业务记录与当前工作台配置的脚本能力。9532 启动参数明确传入空 OfferToday 命令，未启动其脚本。
- 原 `baseline-smoke.mjs` 和 `workflow-smoke.mjs` 分别在等候材料核准和首轮逐岗任务证据时超时。两份旧替身仅返回部分 JD 引用，现行完整条款覆盖门槛给出 `UNDETERMINED` 和材料例外。原脚本结果保留。上述正向场景单独复用现有 Java 回归的完整 JD 夹具与动作上下文，在临时数据上运行。
- 原 baseline 冒烟已到达待核实事实、真实经验 GAP、缓存复用和安全预检。原 verification 冒烟通过，界面先显示待独立验证，独立验证后回流 1 条带来源的本地事实，原商业年限 GAP 保留，0 任务、0 外发。
- Java 回归补足四渠道共用已确认事实的隔离、其他岗位独立推进、范围暂停/撤销、原子预算、共享运行槽和缓存、跨入口与重启防重、UNKNOWN、回执错绑、历史账本、原附件及隐私。对应 `WorkbenchSharedMaterialsHttpTest`、`WorkbenchWorkflowHttpTest`、`WorkbenchCycleContractHttpTest`、`WorkbenchScopeHttpTest`、`WorkbenchPreparationHttpTest`、`WorkbenchMatchingHttpTest`、`WorkbenchVerificationHttpTest`、`WorkbenchBaselineHttpTest` 和渠道 HTTP 测试均通过。

## 实例与回滚

9532 仍由 PID `36306` 提供服务，9533 仍由 PID `91832` 提供服务。旧包位于原路径 `build/libs/get_jobs-2.0.1.jar`，与 9532 已打开的文件 inode 一致。磁盘 SHA256 `1b106b63cfa0867776f75883bd40286fdb3c052ce138579fae1e683228245e2b`。

本次无需回滚。证据目录中的 `runtime-before.txt`、`runtime-after.txt`、`main-launch-record.sh` 保存旧 Java、cwd、逐项参数、库路径、配置与重启命令。未停止 9532、替换旧包或备份用户数据。9532 上只读取了静态页面，通过临时实例检查了 API。实际状态备份及同参数新旧包兼容性比对仍待发布门槛通过后进行。

## 证据与业务验收

本机忽略目录 `.workbench/live-evidence/issue19-release-20261128/` 保存 `test-build.log`、`java-tests.json`、`candidate.sha256`、`package-audit.log`、直接访问的截图与 HTTP JSON，以及 fixture 浏览器截图、HTTP 记录和隔离状态。Git 只收录本摘要。

本次没有新增获准只读的真实岗位样本。Issue #19 中已有公开 JD 只读样本记录，可供人工复核。本轮没有接触平台账号、Cookie、真实简历或原账本，0 真实外发。待人工验收的项目包括适用岗位的推荐质量、各渠道当前连接实际可用范围，以及独立授权的工作台实站回执。既有受控外发记录继续按各自渠道和入口的证据解释。
