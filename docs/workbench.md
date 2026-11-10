# 统一求职工作台 V1

入口：`http://127.0.0.1:9527/workbench`。统一版本在 `get_jobs_jobsdb/`；原 `get_jobs/` 不修改，私有账号、Cookie 和数据库不自动搬迁。

## 启动

要求 Java 21、Node.js、pnpm（或 `npx pnpm`）。JobsDB CLI 仍使用原 `.jobsdb/venv`，工作台本身不依赖其浏览器。

1. 国内模块依照原 README 准备 `db/getjobs.db`：官方 Release 的 `getjobs.db.template` 是干净空库，重命名后放入 `db/`。**不要覆盖任何已有数据库，也不要把原目录带账号的数据库当作空库复制。** 原字典和设置表由这个空库提供，工作台的新表不替代它。
2. 安装前端并启动：

```bash
cd /Users/liuqingyuan/work/get_jobs_jobsdb
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

1. **个人资料**：可预览本地 JobsDB 的已知事实/联系方式；选择导入后仍标为待核实。逐条确认、编辑作用域。冲突不自动覆盖，新联系方式不回写历史。通用、港区、国内、指定官网分别适用，未确认项不进入授权快照。
2. **简历版本**：分别上传港区/国内的中文/英文版本；PDF、DOCX 或文本，最大 10MB。每次保存是新版本，审核批准只影响该版本。文本草稿不会自动变成可投的 PDF，需人工完成格式检查并上传最终附件。
3. **创建岗位任务**：选地区、语言、方向、链接及已批准的匹配简历；读 JD 后决定是否批准。批准冻结当前材料，不自动同步其他版本。未选简历的任务仅可作为待办；需另建绑定正确版本的任务后执行。去重以详情路径和可识别的岗位编号参数为准，不用推广/来源参数区分岗位；同路径仅靠非标准参数区分职位时先人工核对规范详情链接，宁可暂停也不自动重投。
4. **官网/简历 AI**：必须另外配置执行器，批准不等于开始执行。未配置时按钮禁用，不显示虚构进展。模型生成的新简历由 `DRAFT` 事件另存为未批准版本，不能直接用于申请。
5. **项目补强**：先登记差距、计划、建议目录，不自动执行代码，也不把计划当作已完成经历。
6. **招聘状态**：已投递或结果未知的申请可显式点击“检查最新进度”，启动只读 AI 查询。查询不上传材料、不修改官网资料、不允许申请；真实观察及证据回写原申请的招聘状态和检查时间，不改写执行结果。没有执行器时也可人工核对后记录带证据的状态。

JobsDB 页只读显示最近 100 条原账本并保留 CLI 交接。**工作台不通过 HTTP 直接启动交互式 apply 或批次**；一个岗位的批准不是整个 Boss/JobsDB 批次的授权。原平台配置、在线简历和 Cookie 不会因工作台资料更新自动改写。

## 接入现有通用 AI，不新造浏览器 Agent

通过 `WORKBENCH_AGENT_COMMAND` 配置一个可信本机命令的 **JSON 参数数组**。参数中的 `{taskFile}` 替换为私有任务包的绝对路径，不经过 shell。不允许网站/HTTP 请求传入执行命令。

例如已有 Pi CLI、模型配置及经授权的浏览器工具时：

```bash
export WORKBENCH_AGENT_COMMAND='["pi","--print","--no-session","@{taskFile}","按此任务包 instructions 执行。先阅读 reporterPath 的帮助；每步报告进度。缺资料或需要人工时报告 NEEDS_INPUT 并退出，不猜测、不自行扩展权限。"]'
bash gradlew --no-daemon workbench --console=plain
```

这不会安装、登录或自动配置 AI。请选择自己的可用模型与工具；保留其权限限制，不为执行任务关闭安全确认。启动将把当前已批准的资料/简历交给该执行器，其模型可能是外部服务，会产生相应额度消耗。V1 不保证所有官网表单均可操作，验证码或陌生表单可以进入待人工处理。

执行器不是 OS 沙箱，是用户信任的本机程序。任务包只交付当前任务材料，网页只能作为数据，不得按网页指令读取其他文件、登录凭据或执行无关命令。默认执行超时 1800 秒，可用 `--workbench.agent-timeout-seconds=...` 修改。一次只启动一个 AI 任务；同一数据目录由运行锁独占。

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

Java 测试使用临时 SQLite、fake 外部进程、localhost 回调；UI smoke 使用独立本机服务及假资料。真实账号、腾讯/阿里/百度等动态表单和真实回执仍需后续单独授权验证。

设计与 8 张 Mermaid 图：`workbench-v1-design.md`；决策：ADR 0003；GitHub issue #4。
