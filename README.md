<h1 align="center">🍀 Get Jobs【工作无忧】</h1>
<div align="center">

[![Stars](https://img.shields.io/github/stars/loks666/get_jobs?style=flat&label=🌟stars&labelColor=ff4f4f&color=ff8383)](https://github.com/loks666/get_jobs)
[![QQ交流群](https://img.shields.io/badge/🐧QQ交流群-get_jobs-0FB5EB?labelColor=235389&logoColor=white&style=flat)][qq-link]
[![License](https://img.shields.io/badge/📑license-PolyForm-34D058?labelColor=22863A&style=flat)](https://github.com/loks666/get_jobs/blob/main/LICENSE)
![🤏🏻closed](https://img.shields.io/github/issues-search?query=repo%3Aloks666/get_jobs+is%3Aclosed&label=🤏🏻%20closed&labelColor=008B8B&color=00CCCC)
[![Forks](https://img.shields.io/github/forks/loks666/get_jobs?style=flat&label=🏅%20forks&labelColor=800080&color=912CEE)](https://github.com/loks666/get_jobs/forks)
[![Release](https://img.shields.io/github/v/release/loks666/get_jobs?style=flat&label=📦release&labelColor=B8860B&color=DAA520)](https://github.com/loks666/get_jobs/releases)
[![updated](https://img.shields.io/github/last-commit/loks666/get_jobs/main?style=flat&label=🕒%20updated&labelColor=4B0082&color=7B68EE)](https://github.com/loks666/get_jobs/commits/main)

[![JDK](https://img.shields.io/badge/☕JDK-21-FF8C00?labelColor=C04000&style=flat)](https://adoptium.net/temurin/releases/?version=21)
[![Spring Boot](https://img.shields.io/badge/🍃Spring%20Boot-3.5-6DB33F?labelColor=2E7D32&style=flat)](https://spring.io/projects/spring-boot)
[![Gradle](https://img.shields.io/badge/🐘Gradle-9.7-26A69A?labelColor=00695C&style=flat)](https://gradle.org/)
[![Playwright](https://img.shields.io/badge/🎭Patchright-1.62-E2574C?labelColor=A93226&style=flat)](https://github.com/Kaliiiiiiiiii-Vinyzu/patchright)
[![SQLite](https://img.shields.io/badge/🗃️SQLite-3-4FC3F7?labelColor=0277BD&style=flat)](https://www.sqlite.org/)
[![Next.js](https://img.shields.io/badge/⚡Next.js-16-B39DDB?labelColor=5E35B1&style=flat)](https://nextjs.org/)
[![React](https://img.shields.io/badge/⚛️React-19-61DAFB?labelColor=1E88E5&style=flat)](https://react.dev/)

</div>
<div align="center">
<h2 align="center">黑暗无论多么长，光明迟早总是会来的</h2>
<p><strong>我知道你心中有煎熬，有焦虑，像一柄长剑悬在头顶，随时可能落下。</strong><br>
<strong>黎明破晓之时，苦难都将化作勋章</strong></p>
<strong>🪅GUI盛大登场</strong>

<p align="center">
  <a href="https://trendshift.io/repositories/9608">
    <img src="https://trendshift.io/api/badge/trendshift/repositories/9608/daily?language=Java" alt="#1 Java Repository Of The Day" height="55">
  </a>
</p>

</div>

## 本地统一审核版

本分支同步官方 `36bf5f9`（2026-11-03），保留 JobsDB、统一工作台和严格单岗审核。**源码同步/模拟测试不等于真实网站已验收**；以下官方功能说明与本地限制的差异见 [同步记录](docs/upstream-sync-36bf5f9.md)。本版默认不启动招聘浏览器，新库不预填个人经历或开启 AI，图片/重复发送仍需独立授权，上传或保存配置不代表可发送。工作台使用见 [使用文档](docs/workbench.md)。

- 🔔 *重要更新*
    - Boss、猎聘的官方更新已进入本集成分支；编译与模拟测试通过不代表真实站点可用，真实登录、表单及送达仍需另行授权验收。
    - boss仍然建议手动在boss端投递，使用本项目在PC兜底使用。
    - 本项目采用`PolyForm Noncommercial 1.0.0`协议，允许免费非商业使用，禁止任何商业使用,授权请联系作者
    - 前端编译版本发布在release页面，如不想启动前端，可将dist文件夹放到resources文件夹下直接启动后端使用
    - [【重要】跳转到文末更新日志](#-更新日志)

### 🌴源码地址

- Github (国外访问)：https://github.com/loks666/get_jobs
- Gitee·码云 (中国大陆)：https://gitee.com/lok666/get_jobs

### 📢 广告位招租

- 本项目月均访问量可观，Star 9.1k+，QQ 交流群活跃，欢迎与求职、AI、开发工具相关的产品洽谈合作
- 合作邮箱：`super_xiang@126.com`，或通过 QQ 群联系

## JobsDB 香港（独立入口）

新增轻量、审核优先的 JobsDB Quick Apply CLI，不启动国内平台服务。安装、隔离说明及当前支持范围见 [JobsDB 使用文档](docs/jobsdb.md)。

## 🌟 特色功能

- **🖥️ 图形化界面**：直观的网页管理界面，方便配置与运行，降低上手成本。
- **💥 AI 智能匹配**：AI检测岗位匹配度，并根据JD自动撰写个性化的打招呼语（仅限 Boss 直聘）。
- **📷️ 图片简历管理**：保留运行时上传与预览；本审核版不自动发送图片，上传不构成单岗附件授权。
- **🔎 智能过滤**：自动过滤 **不活跃 HR**、 **猎头岗位**、 **目标薪资**，让你的简历投递更精准。
- **📢 状态提醒**：工作台提供页面内及用户许可的桌面提醒；旧平台可选 Webhook 与工作台任务不是同一通知合同，不承诺全天候推送。
- **🚫 黑名单功能**：自动更新黑名单企业，避免重复投递不合适的公司，提高投递效率。
- **💬 重复沟通配置保留**：消息和旧开关可保存，但本版不允许用开关授权重复联系；可明确关闭旧启用值而不删除消息。
- **🛠️ 易于配置**：集中化配置，只需修改配置文件即可自定义筛选条件，轻松上手。
- **🔄 持久登录**：支持超长 Cookie 登录，大部分平台每周仅需扫码一次，减少重复操作。

### 🔞️ 注意事项

- ❌必须要关闭墙外代理，由于主要针对的国内平台，墙外代理会导致页面加载缓慢
- 💪🏻如你有“折腾精神”希望自己配置，QQ群内提供免费答疑，如你不想麻烦，可进入群聊查看群公告
- 📰由于不同系统的页面不一样，导致可能不兼容，文末会给出文档，尽可能让大家能自定义修改
- 🚩如您不方便访问github，可使用码云镜像 (中国大陆)版本：[gitee/getjobs](https://gitee.com/lok666/get_jobs)

> 已经有人在交流群里 **发广告** 等与本项目无关的信息
> 如果带着不同目的或者没想清楚就进群的
> 一经发现群主会对您的家人及朋友进行亲切 (**没有素质**)的问候
> 并将您请出群聊，请珍惜交流的机会，谢谢！

## 🚀 如何使用？

### 1️⃣ 使用git拉取代码

本轮在隔离工作树 `get_jobs_upstream_sync/`，不切换原运行目录。用户已授权文档复审通过后发布到 `origin/integrate/upstream-36bf5f9`；下列新安装命令须等远程分支可用后执行，并核对分支与 commit，不按目录名称判断版本。

```bash
git clone --branch integrate/upstream-36bf5f9 https://github.com/liu-qingyuan/get_jobs.git get_jobs_unified
cd get_jobs_unified
```

### 2️⃣ 环境配置:Gradle、JDK21

- 需要 Java 21、Node.js 和已安装的 Chrome/Chromium；Gradle 准备 Playwright/Patchright 驱动。本版管理启动不会自动打开招聘浏览器，登录由用户明确触发并完成。

更多环境配置详情请点击：📚 [环境配置](https://github.com/loks666/get_jobs/wiki/环境配置)

### 3️⃣ 下载数据库文件（新用户必做）

本项目不会自动建表， **没有数据库文件无法启动**。为避免把开发者本机的配置和登录态一起发布，仓库中不包含数据库文件，请从
release 页面下载空数据库：

1. 到 [最新 release](https://github.com/loks666/get_jobs/releases/latest) 下载 `getjobs.db.template`
2. **把它重命名为 `getjobs.db`**
3. 放到项目根目录的 `db/` 文件夹下，最终路径为 `db/getjobs.db`

模板提供平台表结构、公共字典及部分项目设置，不是已确认的个人资料。首次启动时，如果四个平台配置、AI 内容和 Boss 黑名单均为空，才从 `src/main/resources/defaults/page-config.json` 加载本分支安全默认值。

本版新库默认仅扫描（`debugger=1`），AI、图片发送和重复沟通均关闭；自我介绍、打招呼语和黑名单为空，不预填作者经历、资深要求或个人偏好。求职方向仅预置大模型、机器学习、具身智能关键词。请核对自己的真实资料和凭据；配置、上传及保存都不是发送授权。实际申请仍需有效的精确单岗审核，图片/重复发送在本版继续禁止。

已有任何页面配置的数据库会保持原样；初始化只执行一次，后续启动不会覆盖用户修改或补回用户删除的黑名单。维护默认值时编辑上述 JSON 文件，变更仅影响后续全新安装。

> ⚠️ 老用户升级时不要覆盖自己已有的 `db/getjobs.db`，否则配置和登录态会全部丢失。

### 4️⃣ 核对真实资料、地区及岗位，再明确配置审核范围

- 🤖 AI配置

    - `.env`配制如下：
      ```
      HOOK_URL=https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=your_key_here
      BASE_URL=https://api.openai.com
      API_KEY=sk-xxx
      MODEL=gpt-5-nano
      ```
    - `HOOK_URL`：企业微信机器人推送的链接
    - `BASE_URL`：直连或中转链接地址
    - `API_KEY`：调用的API KEY
    - `MODEL`：需要使用的模型名称

  > 本版默认关闭 AI；启用后费用取决于实际模型、服务商与用量。上游历史费用案例不是当前账单承诺，常规脚本无需逐次调用模型。
  > 💥`BASE_URL` 填任意兼容 OpenAI 接口格式的地址即可（官方接口或你自己的中转服务都可以），本项目不绑定、不推荐任何第三方代理商

    - AI生成的打招呼语示例  
      <img src="src/main/resources/images/AiSayHi.png" alt="AI生成的打招呼语示例">

- Boss 直聘 ([Boss.java](src/main/java/com/getjobs/worker/boss/Boss.java))，实际频控以平台提示为准。

  > 管理页保留重复沟通消息和开关的存储，但本审核版锁定发送开关，不允许通过开启它联系旧会话。旧启用值会如实显示，用户可明确关闭后保存，不删除跟进消息。
  > 单岗正文审核不授权图片或重复联系；未覆盖的动作会在触碰浏览器或模型前被拒绝。

  > 注意：设置配置文件的sayHi为你的打招呼语，否则会投递失败
  > 投递结束后会自动更新黑名单企业，发送过不合适等消息的HR的公司会加入黑名单，不会在投递该公司
  > 现在找工作是很难，但也别做舔狗，打工人不是牛马！

- 图片简历管理（不自动发送）

  > 可在 Boss 页上传 PNG/JPEG/GIF 供本地存储和预览，文件位于运行时 `data/boss/resume/`。不要把个人简历放进 `src/main/resources/`；旧资源简历已从打包中排除。
  > 本版 `sendImgResume=true` 会被发送门禁拒绝，不是开启功能的办法；上传图片也不等于已审核附件。

- 猎聘 ([Liepin.java](src/main/java/com/getjobs/worker/liepin/Liepin.java))【改版源码已同步，真实平台待授权验收】

  > 登录方式、招呼语设置与频控以当前平台和账号提示为准，由用户手动完成所需登录步骤。
  > 不保证次数、可用性或送达；不能按历史“无限制”描述自动联系。设置招呼语、登录成功或编译通过都不构成申请授权或发送证明。

- 51job ([Job.java](src/main/java/job51/Job51.java))【投递有上限，且限制搜索到的岗位数量，没什么活人】

  > 51job现在已经烂掉了，不建议使用
  > 现在投递一段时间后会出现投递上限
  > 目前的解决方式是投一页暂停10秒，先这么着吧

- 智联招聘 ([ZhiLian.java](src/main/java/zhilian/ZhiLian.java))【投递上限100左右，烂掉了，不要用】

  > 智联招聘需要指定默认投递简历 (在线简历 or 附件简历)，否则会投递失败
  > 只可微信扫码，请绑定微信账号

- 本项目目前维护的平台为boss，猎聘，由于51、智联、拉钩使用人太少，已停止维护，如有人修复了这三项平台问题，可经沟通后提交pr.

### 5️⃣ 运行代码

- 先按 [本版快速开始](docs/guide/quick-start.md) 准备 Java 21、前端依赖和当前 checkout 的数据库，再使用管理入口：

  ```bash
  bash gradlew --no-daemon workbench --console=plain
  ```

  此任务显式设置 `browser.auto-start=false`，访问 `http://127.0.0.1:9527/workbench`。直接运行主类或普通 `bootRun` 不等于此维护入口，不能假定它们禁用了自动浏览器；不要用它们替代本指南。

---

### 运行截图

![运行日志.png](doc/imgs/%E8%BF%90%E8%A1%8C%E6%97%A5%E5%BF%97.png)
![环境变量配置.png](doc/imgs/%E7%8E%AF%E5%A2%83%E5%8F%98%E9%87%8F%E9%85%8D%E7%BD%AE.png)
![AI配置.png](doc/imgs/AI%E9%85%8D%E7%BD%AE.png)
![boss配置.png](doc/imgs/boss%E9%85%8D%E7%BD%AE.png)
![boss岗位分析.png](doc/imgs/boss%E5%B2%97%E4%BD%8D%E5%88%86%E6%9E%90.png)
![猎聘配置.png](doc/imgs/%E7%8C%8E%E8%81%98%E9%85%8D%E7%BD%AE.png)
![猎聘岗位分析.png](doc/imgs/%E7%8C%8E%E8%81%98%E5%B2%97%E4%BD%8D%E5%88%86%E6%9E%90.png)
![51job配置.png](doc/imgs/51job%E9%85%8D%E7%BD%AE.png)
![51岗位分析.png](doc/imgs/51%E5%B2%97%E4%BD%8D%E5%88%86%E6%9E%90.png)
![智联配置.png](doc/imgs/%E6%99%BA%E8%81%94%E9%85%8D%E7%BD%AE.png)
![智联岗位分析.png](doc/imgs/%E6%99%BA%E8%81%94%E5%B2%97%E4%BD%8D%E5%88%86%E6%9E%90.png)
---

### 💬 加入 get_jobs 交流群
欢迎交流求职经验、技术问题与项目使用心得。<br>
- [![get_jobs交流①群](https://img.shields.io/badge/🐧get__jobs交流①群-(2000人群·开放申请)-0FB5EB?labelColor=235389&logoColor=white&style=flat)][qq-link]<br>

[//]: # (- [![get_jobs交流②群]&#40;https://img.shields.io/badge/🐧get__jobs交流②群-&#40;500人群·开放申请&#41;-0FB5EB?labelColor=235389&logoColor=white&style=flat&#41;][qq-link-2])

## ☕ 赞赏支持

<div align="center">
<table>
<tr>
<td align="center"><img src="src/main/resources/images/aliPay.jpg" alt="支付宝" width="220"><br/>支付宝</td>
<td align="center"><img src="src/main/resources/images/wechatpay.jpg" alt="微信支付" width="220"><br/>微信支付</td>
</tr>
</table>
</div>

> 本项目免费开源，我从未想到过有人会打赏，直到我某天真的收到了来自某人的赞赏支持，深受触动，所以重新开启了打赏码，不论打赏与否，本项目都将感谢您对本项目的支持与使用！

## 🚩 环境部署问题

> 本项目文档已相对完善，如有运行仍有问题，请添加QQ群联系群主或在群内沟通

- 请注意：
    1. 本项目不支持服务器部署，无须尝试，如招聘网站发现访问者为服务器IP，不会返回任何网站数据。
    2. 在开发与部署过程有任何问题都可在群内沟通，但群内的同学没有义务必须要解决您的问题，请保持礼貌提问的态度。

> 注：本项目为免费开源项目，非Saas类出售商品，不会考虑任何兼容的设备以及他人的需求，如多位同学有相同的需求可以提出issue，具有一定需求性会考虑开发，其他的问题有能力就自己修改，否则请联系群主，非诚勿扰。

---

## 📑 更新日志

[更新日志.md](doc/%E6%9B%B4%E6%96%B0%E6%97%A5%E5%BF%97.md)

## 🤝 参与贡献

- ⚠️ **本项目禁止一切未经联系与沟通的 PR，会直接拒绝掉，请勿直接提交！**
- 项目所有需要开发或者改进的需求，都会统一发到 [Ideas 讨论区](https://github.com/loks666/get_jobs/discussions/categories/ideas)，欢迎前往认领相关需求并参与开发。

> 感谢每一位为本项目提交过代码的朋友 🙏

<a href="https://github.com/loks666/get_jobs/graphs/contributors">
  <img src="https://contrib.rocks/image?repo=loks666/get_jobs" alt="贡献者" />
</a>

---

## 🚀 PR 提交流程（非常重要！）

> ⚠️ **所有未经联系沟通的 PR 会直接拒绝掉，不要直接提交！**

1. **认领需求**：有需要开发或者改进的需求，会发到 [Ideas 讨论区](https://github.com/loks666/get_jobs/discussions/categories/ideas)，相关人员可以在对应讨论中认领相关需求进行开发。
2. **确认细节**：**开发前请务必确认是否有其他人已经在开发，并与相关人员商定开发细节**，确认没问题后再开始开发。
3. **拉取开发分支**：Fork 本项目，从 **`dev`** 分支新建你的个人开发分支。
4. **提交 PR 到 dev 分支**：开发完成后，提交 Pull Request 到 **loks666/get_jobs 的 `dev` 分支**<br>
   （❗ **注意：必须提交到 dev 分支，不要提交到 main！**）
5. **规范 Commit 与 PR 标题**：提交 Commit 时，第一行必须符合格式：`<emoji> <小写英文>.`，即以一个 **Emoji 表情**
   开头（[emoji网站](https://www.emojiall.com/zh-hans/all-emojis)），空格后接 **全小写英文**，并以 **`.`** 结尾。
   例如：`🐛 fix cookie table creation on startup.`
    - PR 标题也需符合同样格式（合并时会作为提交信息）。
    - CI 会自动检查，不符合会无法合并。
    - 本地可运行 `sh .githooks/setup.sh` 启用提交前检查和提交模板。
6. **等待审核**：等待管理员审核，验证无误后，代码将合并到 `dev` 分支。
7. **合并至 main 分支**：`dev` 分支会定期合并到 `main` 分支（合并时间不固定，但会定期统筹合并发布）。

> 📑 本项目已启用[贡献者许可协议（CLA）](CLA.md)，提交 PR 时需在模板中勾选同意。
> 你的版权仍归你自己，CLA 只是让维护者能在保持项目非商业免费开放的同时对外授予商业许可。

---

# ✨ 相信自己！

> **"每一个伟大，都有一个平凡的开始"**


---

### 📰 开源协议

[LICENSE](LICENSE)

---

### 🙅🏻‍♂️ 谨防受骗

- 市面上充斥着很多使用本项目招摇撞骗的项目，请擦亮眼睛，谨防上当受骗。
- 目前已收到多人举报，会有别有用心的人，潜伏在群里，通过搜索群内用户的QQ号添加好友，通过后就推广自己的收费项目，已有多人受骗，比如像下面这个项目，如你发现，请积极联系我，并问候下他的亲朋好友，谢谢
- 另外注明，这个所谓一键直达的项目，由于自己推广不力，不断的在本项目群里骚扰用户，想碰瓷本项目，不胜其烦，几乎和狗皮膏药一样，如你碰到了，请帮我淬两口，并吐一口痰，感谢你好心人。
- ![img.png](src/main/resources/img.png)

---

### 🧰 其他开源项目

> 除了求职工具，以下为 **机器学习 · 深度学习 · 强化学习 · 神经网络** 方向的项目，以及一些实用工具，希望可以帮到你，欢迎 Star
> 👇
> 可能你觉得是推广，不否认这样的说法，但也真心希望你能在我这学到点东西

#### 🧠 AI 与算法

| 方向                | 项目                                                                                | 简介                                                                                   |    推荐    |
|:--------------------|:------------------------------------------------------------------------------------|:---------------------------------------------------------------------------------------|:----------:|
| 🕹️强化学习·神经网络 | [**RL-MiniGames**](https://github.com/loks666/RL-MiniGames)                         | 神经网络 + 强化学习小游戏，训练 NeuralBot 自主决策                                     | ⭐⭐⭐⭐⭐ |
| 👻搜索·启发式规划   | [**pacman-engine**](https://github.com/loks666/pacman-engine)                       | 吃豆人 AI 智能体，用搜索与启发式规划让它自动玩游戏                                     | ⭐⭐⭐⭐⭐ |
| 🚦深度学习          | [**yolov5-5.0**](https://github.com/loks666/yolov5-5.0)                             | 基于 YOLOv5 识别交通标志                                                               | ⭐⭐⭐⭐⭐ |
| 📈机器学习·金融     | [**FinancialMachineLearning**](https://github.com/loks666/FinancialMachineLearning) | CAPM + 均值方差优化，用二次规划构建最优投资组合                                        |  ⭐⭐⭐⭐  |
| 🏥大模型·医疗       | [**medicine_llm**](https://github.com/loks666/medicine_llm)                         | 医疗大模型对话测试平台（FastAPI + Vue3），多模型切换、SSE 流式对话、会话管理与消息评价 |  ⭐⭐⭐⭐  |
| 🖼️深度学习          | [**gan_fixed_img**](https://github.com/loks666/gan_fixed_img)                       | GAN 对抗网络修复破损图片（效果未到终极目标，仅供学习）                                 |   ⭐⭐⭐   |
| 🩺机器学习·医疗     | [**disease-prediction**](https://github.com/loks666/disease-prediction)             | 疾病预测分析系统：数据处理、模型训练评估、结果展示                                     |   ⭐⭐⭐   |

#### 🛠️ 应用与工具

| 项目                                                                     | 简介                                          |    推荐    |
|:-------------------------------------------------------------------------|:----------------------------------------------|:----------:|
| 🗄️ [**WeChatVault**](https://github.com/loks666/WeChatVault)             | PC 端微信数据库工具，聊天记录一键导出（Rust） | ⭐⭐⭐⭐⭐ |
| 💬 [**FlyAI**](https://github.com/loks666/FlyAI)                         | 微信群聊统计助手，AI 统计群聊数据并推送（Go） | ⭐⭐⭐⭐⭐ |
| 📥 [**idm_crack**](https://github.com/loks666/idm_crack)                 | IDM 破解文件备份仓库                          | ⭐⭐⭐⭐⭐ |
| 👤 [**loks666**](https://github.com/loks666/loks666)                     | GitHub 个人主页，自动统计数据并更新描述文件   | ⭐⭐⭐⭐⭐ |
| 🤖 [**anyrouter-autolog**](https://github.com/loks666/anyrouter-autolog) | AnyRouter 多账号自动签到（Fork）              |  ⭐⭐⭐⭐  |

---

### ☕️ Github Star历史

<a href="https://star-history.com/#loks666/get_jobs&Date">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="https://api.star-history.com/svg?repos=loks666/get_jobs&type=Date&theme=dark" />
    <source media="(prefers-color-scheme: light)" srcset="https://api.star-history.com/svg?repos=loks666/get_jobs&type=Date" />
    <img alt="Star History Chart" src="https://api.star-history.com/svg?repos=loks666/get_jobs&type=Date" />
  </picture>
</a>

<!-- LINK GROUP -->

<!-- [![][fossa-license-shield]][fossa-license-link] -->

[qq-link]: https://qm.qq.com/q/qJwmIrqPU

[qq-link-2]: https://qun.qq.com/universal-share/share?ac=1&authKey=qo6bGkogtLrVusb7GI%2Bv08KaTWEofET0bo%2Bdk5hqEk1JEY0II3Lu%2FGXPCoROWZUe&busi_data=eyJncm91cENvZGUiOiI1Nzg3ODU1MDUiLCJ0b2tlbiI6IjRXQklndWdFRkloTlpRUE82UXpUREVSa0FDN3Y2UDI1UE42VHBiV3NmYjNLYkdGczRMKzdId0NHeGdQVXMrRjEiLCJ1aW4iOiIyODQxOTAwNTYifQ%3D%3D&data=DR0uxpwdTVM1YFw3evPIbj_L-79Bp1AU91gfeieGqh8D4uEXGuxAl-lmZ2SEW6UNUuKNBtKzGflIYe6vPa9SqA&svctype=4&tempid=h5_group_info

[qq-shield-badge]: https://img.shields.io/badge/QQ交流群-get_jobs-0FB5EB?labelColor=235389&logo=tencent-qq&logoColor=white&style=flat

[pr-welcome-link]: https://github.com/loks666/get_jobs/pulls

[pr-welcome-shield]: https://img.shields.io/badge/🤯_pr_welcome-%E2%86%92-ffcb47?labelColor=black&style=for-the-badge

[fossa-license-shield]: https://app.fossa.com/api/projects/git/Bgithub.com/Floks666/Fget_jobs.svg?type=shield

[fossa-license-link]: https://app.fossa.com/projects/git/Bgithub.com/Floks666/Fget_jobs?ref=badge_shield
