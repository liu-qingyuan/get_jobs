# 求职上下文
- 国内平台：原有 Spring Boot 管理页面和投递任务，本次不改。
- JobsDB：Java JobsDbMain 转发到 scripts/jobsdb/jobsdb.py；Scrapling + Chrome 自动 CF，独立持久会话，不启动 Spring/前端。
- 数据：沿用 .jobsdb/applications.db；UNKNOWN 提交前原子 claim，未知结果不重试。
- 本次接入保留 prepare/apply 审核合同；最终产品目标是一次配置后自动批量投递，批量调度尚未实现。
- 安装、登录、测试见 docs/jobsdb.md；接缝决策见 ADR 0002。
