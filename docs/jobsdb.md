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

**本次完成浏览器接入，不等于批量无人值守投递已实现。**用户最终要求是一次配置后自动批量运行；批量调度、简历/答案配置和匹配规则是后续工作，本次不通过删除确认步骤来伪装已完成。

## 数据与错误合同

所有状态固定在当前 clone `.jobsdb/`：
- `scrapling-profile/`：新入口专用持久化 Chrome 会话。不读取、不复制旧 `browser-profile/`，也不接入其他 Chrome/Boss 会话。
- `applications.db`：复用旧 Java 实现的表结构及记录，不迁移、不清空。
- `run.lock`：跨进程 POSIX 文件锁。
- `screenshots/`：当前页面及提交结果截图（可能含个人信息，仅本地保存）。

提交前原子 claim 为 UNKNOWN；确认成功才更新 SUBMITTED。UNKNOWN 禁止自动重试。Scrapling `retries=1` 避免重放整个 page_action；回调异常显式传播，不接受“日志有错但退出成功”。CF/导航上限 90 秒；等待用户输入不设此超时。提交后不重新处理 CF 或重按提交。

Java 入口完整转发参数、stdin/stdout/stderr 和退出状态；失败的 Python 子进程使 Gradle 失败。Ctrl+C 会清理自己的子进程。命令运行需要源码 checkout，不是可独立部署的胖 JAR。

## 测试

```bash
bash gradlew --no-daemon jobsdbTest --console=plain
```

使用 Python 标准库 unittest、真实 Chrome 和全路由 HTML fixtures，不发送真实申请。覆盖原申请状态机、SQLite 兼容、未知结果去重、锁、输入取消、跨会话 cookie/localStorage 保存、Scrapling 回调异常传播。独立测试 profile 在临时目录，不使用账号 profile。

真实账号是否已登录、雇主动态表单和真实投递结果，须由后续账号测试证明；fixture 通过不代表投递成功。
