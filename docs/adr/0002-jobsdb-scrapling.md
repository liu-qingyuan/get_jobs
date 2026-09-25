# ADR 0002 — JobsDB 专用 Scrapling 子进程

## 背景
ADR 0001 首版 Java 入口无法稳定通过 JobsDB CF。2026-09-24 独立实验使用 Scrapling 0.4.15 + Patchright 1.63.0 + 正式 Chrome，启用 solve_cloudflare 后连续两次自动进入列表及详情。禁用 solver 的基线与回滚测试仍返回 403。

## 决策
保留 Java JobsDbMain 和 Gradle jobsdb 的命令 Interface；Implementation 迁移为单个 Python CLI Module。Browser 隐藏 session/CF 操作；Flow 隐藏 DOM；Store 独占旧表结构与去重语义。Java 仅转发参数与退出状态，不设计双向 RPC、不在两种语言保留 DOM/数据库规则。

使用 `.jobsdb/scrapling-profile`，不接管旧浏览器。账本复用 `.jobsdb/applications.db`，列和原子 UNKNOWN claim 不变。其他平台保持原实现。

Scrapling 的公开 fetch/page_action 负责初始导航与 CF。表单推进后遇到 CF 时，为避免重发请求，Browser 内仅一处使用 pinned `_cloudflare_solver(page)`；其调用和升级风险由固定依赖及测试约束。提交后绝不调用 solver 或重放申请。

## 影响
增加仅 JobsDB 使用的 Python 3.12 环境和两个直接依赖；删除旧 Java Flow/Store 及其旧测试，迁移覆盖。Java 启动需要源码 checkout；不是后台服务。ADR 0001 的“无跨语言进程/无新生产依赖”被本决策取代；隔离、去重、确认合同仍保留。

本次不实现批量投递调度。未来批量流程应一次配置规则和答案、仅汇总异常，不能把原逐岗确认当作最终产品。

## 后续：配置驱动批次（issue #3）

新增 Python `batch` Module 拥有搜索编排、筛选规则、配置验证和逐岗报告，复用 Browser/Flow/Store；不引入新服务或模型依赖。Flow 新增自动准备 Interface，在已知答案和内容哈希命名附件条件下到达 review，不自行 claim 或提交。原 apply/prepare 保持交互合同，batch 才启用无 stdin 的串行批次。
