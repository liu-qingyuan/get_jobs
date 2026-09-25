# JobsDB 最小纵向切片

## 决策
先新增独立 CLI 模块，复用 Java 21 / Playwright / SQLite，不新增生产依赖，不启动 Spring、Next.js 或共享 PlaywrightManager。数据库及浏览器 profile 固定在当前副本 .jobsdb/，进程锁防止重复启动。无需后台服务、插件框架、跨语言子进程。

首版提供 login、search、prepare、apply、history；只走英文 Quick Apply。已有简历由用户在表单选择；雇主问题人工补充，不虚构。自动推进明确 Continue / Next，最终 Submit application 必须经逐岗确认。去重按职位 ID；提交前原子记录 UNKNOWN，成功证据后转 SUBMITTED；未知状态保持防重。

## 本次暂不包含
接入原管理页面、批量无人值守、AI 岗位打分、外部 ATS、中文页面自动化。选择当前范围是为了先验证投递路线、降低运行干扰，而非另造平台框架。

## 后续
真实账号验证通过后再实现 JobPlatformService 并接入管理页面；保留现有 JobsDB 模块对外合同。
