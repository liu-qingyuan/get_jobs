# 当前容器与运行结构

这张 C4 Level 2 图展开 [系统上下文](/architecture/c4-context) 中的本机程序、独立脚本、可信命令与存储。**Container 是应用 / 进程或独立数据存储，不是 Docker 容器，也不是新增微服务。** 平台资格仍以 [能力总览](/platforms) 为准。

图用 Mermaid 排版 C4 元素，可横向滚动，点击可打开全尺寸 SVG。蓝色表示项目程序与本机存储，灰色表示外部系统、另配程序或独立账本；外框是软件边界，不是 OS 权限隔离。公开文档站在上下文图单列，不在本图的业务数据链路中。

<div class="architecture-diagram">

[![当前容器与运行结构：管理界面连接 Spring 本机主程序，后者连接渠道脚本、分别配置的可信命令、SQLite、独立账本和私有文件](/architecture/c4-containers.svg)](/architecture/c4-containers.svg)

</div>

## 程序、会话与存储

- **管理界面**是 Next.js / React 页面，开发时可单独运行，打包静态页面可由 Spring 提供。页面通过 HTTP / JSON 管理和轮询工作台状态；这不是公开 Pages 上的在线服务。
- **Spring 主程序**复用一个串行 Runner 和受控子进程资源槽；下面的职责同属本机程序，不是各自运行的服务。Boss 只读使用 Java / Playwright 的拥有线程和本副本已有、显式登录且空闲的浏览器；不会接管旧任务。
- **OfferToday** 用 Python 启动 Node.js Adapter。公开找岗有独立临时浏览器路径；账号单岗脚本只使用明确授权的扩展标签页。公开读取验收不会给账号脚本发送资格，新旧版本自动发送仍关闭。
- **JobsDB** 用独立 Python / Scrapling CLI、会话目录和锁；只读发现不读取原批次答案、不新建登录 profile、不调用旧 apply / batch。验证墙、Service Worker 或不完整详情会显式阻断，不迁移或删除旧会话。
- **三个可信命令配置槽位分开**：通用命令用于官网、资料整理、匹配和备料；项目执行命令使用选定的独立目录与本地资源；验收命令是另配的独立程序，不由执行器自证。均不是 OS 沙箱；配置命令本身不授予任何本次求职动作权限。
- **SQLite 与文件分开**：工作台数据库保存来源 / 版本、预算、候选、任务、项目及回执；私有文件保存独立附件、限定任务包、日志及留存证据。JobsDB 旧账本仍由其人工 CLI 管理，工作台只读展示与防重，不合并账号、会话或账本。

## 主程序内的职责

| 既有职责 | 拥有的判断 / 数据 | 不越过的边界 |
| --- | --- | --- |
| 资料与独立简历 | 来源版本、适用事实、四类基线与岗位材料 | AI 整理不是事实确认；不覆盖旧快照 |
| 范围策略与预算 | 选定渠道 / 材料 / 动作、有效期、累计计量及阶段授权 | 预检不授权，不替代平台能力或 claim |
| 候选、匹配与备料 | 有界读取、完整 JD、依据引用、相关缓存与冻结包 | 不隐藏不足，不用分数确认事实，不核准任意改写 |
| 受控执行与回执 | 串行资源、阶段重核与占位、UNKNOWN / 送达证据 | 不因退出码成功、已联系或来源变化重投未知目标 |
| 项目与独立验证 | 固定目标、独立资源、尝试、验证与有限成果回流 | 执行、验收、回流分别授权；本地成果不冒充商业经验 |
| 渠道 Adapter | 平台身份、详情 schema、只读能力和有限动作知识 | 不复制材料授权规则，不回退其他渠道扩大资格 |

## 图源与事实依据

<details>
<summary>查看本图 Mermaid 图源</summary>

```mermaid
<!--@include: ./graphs/c4-containers.mmd-->
```

</details>

生成静态图的方法见 [图源维护](/architecture/c4-context#图源与维护)。运行组合依据仓库中的 [WorkbenchConfiguration](https://github.com/liu-qingyuan/get_jobs/blob/main/src/main/java/com/getjobs/application/workbench/WorkbenchConfiguration.java)、[静态页面配置](https://github.com/liu-qingyuan/get_jobs/blob/main/src/main/java/com/getjobs/application/config/StaticServerConfiguration.java) 与 [ADR 0003](/adr/0003-unified-workbench)；使用细节继续参照 [工作台](/workbench)、[OfferToday](/offertoday-v1) 和 [JobsDB](/jobsdb)，不在这里重定义合同。
