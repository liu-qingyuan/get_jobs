---
layout: home

hero:
  name: "Get Jobs"
  text: "本地求职工作台"
  tagline: "国内与港区资料共享 · 简历独立维护<br>已审核材料与明确授权 · 未知结果不重投"
  actions:
    - theme: brand
      text: 多平台能力总览
      link: /platforms
    - theme: alt
      text: 本地审核版启动
      link: /guide/quick-start
    - theme: alt
      text: 查看当前项目源码
      link: https://github.com/liu-qingyuan/get_jobs

features:
  - icon: 🌐
    title: 四渠道，能力分层
    details: 官网、OfferToday、Boss、JobsDB 接入统一工作台；代码接入、实站读取与外发资格分别说明，不承诺全平台自动投递。
  - icon: 📄
    title: 事实与独立简历
    details: 保留资料来源与版本，地区、语言及岗位简历独立维护；AI 整理不是事实确认，上传或保存不是发送授权。
  - icon: 🛡️
    title: 范围与累计预算
    details: 明确来源、选定材料、动作和到期时间，默认仅准备；范围变化不清零预算，也不复活旧授权。
  - icon: 🔎
    title: 候选、匹配与备料
    details: 只读候选保留完整 JD 和来源；匹配引用适用材料，可证实变换才可核准，信息不足与能力例外保持可见。
  - icon: 🧪
    title: 项目与独立验收
    details: 项目执行另需资源授权和独立可信验收，有限本地成果另行回流；不把 demo 当作任职、商业经验或年限。
  - icon: 💾
    title: 本地留存，未知不重投
    details: SQLite 与私有文件保留在本机。每个不可逆阶段独立核验；退出码、已联系或正文送达不等于正式申请确认。
---

<div style="text-align: center; margin-top: 2rem;">

### 📊 社区实时动态

<div class="readme-badges">

<!--@include: ./snippets/badges.md-->

</div>

</div>

---

## 🖥️ 看得见的求职工作台

本机工作台管理资料、候选、范围、材料与回执；实际读取和外发须满足各渠道能力与授权。先看 [多平台能力总览](/platforms)，再按需启动本机程序。

了解当前结构：[系统上下文](/architecture/c4-context) · [容器与运行结构](/architecture/c4-containers)。

下图保留原国内管理页的界面语境，不代表四渠道都能投递。

![Get Jobs 运行日志](/images/运行日志.png)

[浏览全部界面截图 →](/guide/screenshots) · [了解完整功能 →](/guide/introduction)

::: warning 使用前先了解
这里是免费 GitHub Pages 静态说明站，不是在线工作台，不连接账号或本机资料库。官网依赖可信执行器，OfferToday 自动发送未验收且关闭，Boss / JobsDB 受控外发尚未完整实现。文档发布不代表业务验收；图片与重复发送不能靠开关启用。非商业使用遵循原协议，用户自行选择的模型服务可能另行计费。
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
