---
layout: home

hero:
  name: "Get Jobs"
  text: "本地求职工作台"
  tagline: "国内与港区资料共享 · 简历独立维护<br>已审核材料与明确授权 · 未知结果不重投"
  actions:
    - theme: brand
      text: 本地审核版启动
      link: /guide/quick-start
    - theme: alt
      text: ⭐ GitHub 点赞支持
      link: https://github.com/loks666/get_jobs

features:
  - icon: 🌐
    title: 国内与港区入口
    details: 工作台整合 Boss 与 JobsDB 入口，保留其他官方平台源码；模拟验证不等于真实网站已验收。
  - icon: 🤖
    title: AI 智能打招呼
    details: 默认关闭 AI，不预填个人经历。明确配置并授权后才调用模型，失败不降级发送旧招呼语。
  - icon: 🛡️
    title: 黑名单与精准过滤
    details: 智能跳过不活跃 HR、猎头岗位、外包公司，自动将沟通不顺企业加入黑名单，拒绝无效沟通。
  - icon: 📱
    title: 页面内状态提醒
    details: 页面运行期间展示进度，用户许可后尽力提供桌面通知；不承诺全天候、手机或主动招聘状态推送。
  - icon: 💾
    title: 本地数据持久化
    details: 基于轻量级 SQLite 存储配置与投递日志，配置与投递记录保留在本机，方便追踪投递进度。
  - icon: ⚡
    title: 配置不等于发送
    details: 新库默认仅扫描，图片和重复发送锁定；上传或保存不会代替单岗审核，也不会直接开始批次。
---

<div style="text-align: center; margin-top: 2rem;">

### 📊 社区实时动态

<div class="readme-badges">

<!--@include: ./snippets/badges.md-->

</div>

</div>

---

## 🖥️ 看得见的求职工作台

从配置到岗位分析，再到投递日志，在网页端完成日常操作。

![Get Jobs 运行日志](/images/运行日志.png)

[浏览全部界面截图 →](/guide/screenshots) · [了解完整功能 →](/guide/introduction)

::: warning 使用前先了解
本页是本地审核版，不是全平台海投承诺。官方更新已纳入源码集成，真实登录、表单及送达仍待用户单独授权验收；图片与重复发送不能通过开关启用。非商业使用遵循原项目协议，AI 费用由用户选择的服务商收取。
:::

## 启动管理页

先按 [审核版启动指南](/guide/quick-start) 核对交付分支、Java 21、前端依赖和当前 checkout 的数据库；不要克隆官方默认分支后误以为包含本地扩展，也不要覆盖旧账号数据。

```bash
# 在已确认版本且已准备数据库/前端依赖的 checkout 根目录
bash gradlew --no-daemon workbench --console=plain
```

::: tip 管理启动不申请
访问 `http://127.0.0.1:9527/workbench`。启动管理页不打开招聘浏览器、不调用 AI、不解除审核门禁；真实网站操作须另行明确授权。
:::

---

## 📈 Star 增长趋势

感谢广大开发者与打工人们的支持与喜爱，黑暗无论多么长，光明迟早总是会来的：

<p align="center" style="margin-top: 1.5rem;">
  <a href="https://star-history.com/#loks666/get_jobs&Date" target="_blank">
    <img src="https://api.star-history.com/svg?repos=loks666/get_jobs&type=Date" alt="Get Jobs Star History" style="max-width: 100%; border-radius: 8px;" />
  </a>
</p>

<div style="text-align: center; margin-top: 2rem; color: var(--vp-c-text-2);">
  <em>"每一个伟大，都有一个平凡的开始。黎明破晓之时，苦难都将化作勋章。"</em>
</div>

## 💬 与社区一起前进

欢迎交流求职经验、技术问题与使用心得。

- [加入 QQ 交流群](https://qm.qq.com/q/qJwmIrqPU)
- [社区、贡献流程与赞赏支持](/guide/community)
- [作者的其他开源项目](/guide/resources)
- [查看更新日志](/guide/changelog)

<a href="https://trendshift.io/repositories/9608"><img src="https://trendshift.io/api/badge/trendshift/repositories/9608/daily?language=Java" alt="#1 Java Repository Of The Day" height="55" /></a>
