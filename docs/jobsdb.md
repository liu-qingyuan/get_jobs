# JobsDB 香港：隔离运行首版

在 get_jobs 内增加一个小型 Java CLI，不启动 Spring Boot、Next.js 或国内平台浏览器。首版支持英文 JobsDB Quick Apply 的搜索、准备、逐岗确认提交和投递记录。当前是独立入口，不是原管理页面里的第五个平台按钮。

## 安装与运行

需要 JDK 21。以下命令在 **JobsDB 独立副本根目录**执行，不在正在投 Boss 的目录执行：

```bash
# 本机示例；其他电脑设置为自己的 JDK 21 路径
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"

bash gradlew --no-daemon jobsdbInstallBrowser --console=plain
bash gradlew --no-daemon jobsdb --console=plain --args='help'
bash gradlew --no-daemon jobsdb --console=plain --args='login'
bash gradlew --no-daemon jobsdb --console=plain --args='search "software engineer" 2'
```

`login` 打开新的独立 Chromium 窗口。手动登录，切换英文站点，再回终端按 Enter；仅保存会话，不宣称自动验证登录成功。

搜索默认一页、最多五页，按岗位 ID 去重，输出到 `.jobsdb/search.json`，并标出本地有过提交尝试的岗位。匹配关键词由 JobsDB 搜索完成；本增量尚未接 AI 打分。搜索遇站点验证或未检测到岗位时保留窗口，手动处理后输入 `retry`；其他输入或 EOF 取消。

```bash
# 替换为搜索结果中的真实岗位 URL
bash gradlew --no-daemon jobsdb --console=plain --args='prepare https://hk.jobsdb.com/job/12345678'
bash gradlew --no-daemon jobsdb --console=plain --args='apply https://hk.jobsdb.com/job/12345678'
bash gradlew --no-daemon jobsdb --console=plain --args='history'
```

- `prepare`：只推进 Quick Apply 的 Continue / Next；遇到未填字段或未知界面停下。你在浏览器选择/上传简历、选择求职信、如实填写问题后，终端输入 `continue`。到审核页保存截图，按 Enter 关闭；代码不点击提交。
- `apply`：相同准备流程，最终必须在终端输入 **`SUBMIT 岗位ID`** 才提交。其他输入、空输入或 EOF 都取消。使用已有上传简历或在网页手动上传；没有默认文件选择。
- 不要在浏览器手动点击 Submit；否则本地 ledger 不会记录该次手动提交。
- 遇到登录跳转，先在浏览器完成登录并进入该岗位的 Quick Apply，再输入 `continue`。
- 普通 Apply 或外部 ATS 链接跳过；不点击通用的 Apply / Confirm / Review and submit。
- `UNKNOWN`：提交前已写入记录，但没有收到明确成功证据（也包括提交前最后一步失败）。该 ID 后续被阻止重试；先人工到 JobsDB 核对，不自动删除记录。

## 隔离与数据

全部运行数据固定放在副本 `.jobsdb/`（gitignored）：

- `browser-profile/`：独立登录；不读取 Boss cookies，不连接 CDP。
- `browsers/`：独立浏览器安装缓存，禁用自动清理其他版本。
- `applications.db`：新 SQLite ledger，不读原 `db/getjobs.db`。
- `run.lock`：单进程锁，浏览器命令及 history 互斥。
- `screenshots/`：审核、结果和故障截图，可能含个人信息，仅保留本地。

不监听端口、不启动后台任务、不改全局配置。一个副本只用于一个求职者账号，避免账号之间混用去重记录。关闭 JobsDB 的窗口不会关闭 Boss 的窗口。

## 测试

```bash
bash gradlew --no-daemon jobsdbTest --console=plain
```

测试使用真实 headless Chromium 和完全拦截的网页 fixtures，无真实账号、真实职位提交或外部表单请求。验证 URL 校验、分页去重、必填问题暂停、审核不提交、精确提交按钮、站点和岗位身份、未知状态防重、SQLite 跨连接唯一 claim。

原仓库没有测试套件；本次不引入测试框架或新生产依赖。`jobsdbTest` 是单独的显式验证任务，常规 `test` 不包含它。线上 DOM、账号验证及真实提交仍需用户登录后逐岗验证，fixture 通过不等于线上已验证。

## 复用与后续

复用已有 Java 21、Playwright、SQLite、JSON 依赖。JobsDB DOM 知识集中在 `JobsDbFlow`，存储集中在 `JobsDbStore`，入口和人工确认由 `JobsDbMain` 负责。没有为一个平台创建插件系统、抽象工厂、后台队列或另一套 Python 服务。

下一步先真实账号运行 prepare，再考虑接原 `JobPlatformService`、管理页面及 AI 匹配；此版本不修改正在运行的 Boss 工作流。

## 当日线上检查

2026-09-24 使用独立、未登录 profile 访问公开搜索，遇到 Cloudflare 的 `Performing security verification` 页面，未取得职位结果。已根据该页面补充人工暂停/恢复处理及回归测试。未进行真实账号申请或声称线上投递成功。下一步需要在 `login` 窗口手动完成验证，再测试 `search` / `prepare`。
