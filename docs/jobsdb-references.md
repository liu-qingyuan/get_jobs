# 开源参考核查（2026-09-24）

星标从 GitHub API 当日读取，表示社区关注度，不代表 JobsDB 兼容性或运行可靠性。以下仅参考公开行为与流程，自行实现 Java 代码，没有直接移植源码。

| 项目 | Stars | 核查结论 / 采用内容 |
|---|---:|---|
| [bowenbaoshiqi/jobsdb-auto-apply](https://github.com/bowenbaoshiqi/jobsdb-auto-apply) | 0 | 专门面向 JobsDB HK。参考 Quick Apply 和最终 Submit application 区分、人工登录和本地记录。不采用随机/默认选答案、仅 URL 改变就算提交成功的策略。 |
| [GodsScion/Auto_job_applier_linkedIn](https://github.com/GodsScion/Auto_job_applier_linkedIn) | 2,863 | 面向 LinkedIn，参考审核停止及本地配置思路，不当作 JobsDB 适配器。 |
| [career-ops-hq/career-ops](https://github.com/career-ops-hq/career-ops) | 72,576 | 以评估岗位、准备材料、人审为主，非自动提交工具；参考材料不虚构原则，不引入其完整工作流。 |
| [AbhishekMandapmalvi/AutoApply](https://github.com/AbhishekMandapmalvi/AutoApply) | 15 | 多招聘系统自动申请，本地记录/审核模式可参考；当前支持列表未列 JobsDB，不复制整套桌面应用。 |

核查时 commits：
- jobsdb-auto-apply: `79bb016c0c864c7e42ff47c524560916fcc1691d`
- GodsScion: `e0b2401a7a7b333eab0b518e80be5285c3ee85c5`
- career-ops: `0b4d04e532c7c5450b76f5a29247d6fef3b38adb`
- AutoApply: `053071ba1bba5733b522d78c3d645002d817e55a`

另：旧 AIHawk URL 当前重定向到 feder-cr/invisible_playwright_mcp（31,647 stars），已是通用浏览器代理，不能用早期自动投递宣传代表当前功能；本增量不引入它。
