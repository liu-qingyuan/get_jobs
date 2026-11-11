# 快速开始：本地统一审核版

本指南启动本地管理页，不承诺首次自动投递。源码同步、模拟网页测试与真实账号验收分别进行；本次未登录或实投。版本与限制见 [同步记录](/upstream-sync-36bf5f9)。

## 环境

需要 Java 21、Node.js、Chrome/Chromium 和 Gradle Wrapper。浏览器驱动由项目脚本准备；不要用旧版 chromedriver 安装说明替代当前 Playwright/Patchright 流程。

网络可达性以平台与服务实际要求为准，本指南不要求自动更改系统代理或权限。

## 获取本分支

本轮使用隔离工作树 `get_jobs_upstream_sync/`，不切换原运行目录。用户已授权文档复审通过后发布到 `origin/integrate/upstream-36bf5f9`。以下新安装命令须等远程分支可用后执行，并核对交付 commit；中性目录名不要求重命名旧目录：

```bash
git clone --branch integrate/upstream-36bf5f9 https://github.com/liu-qingyuan/get_jobs.git get_jobs_unified
cd get_jobs_unified
```

原官方仓库为 [loks666/get_jobs](https://github.com/loks666/get_jobs)；直接克隆官方分支不会包含本地 JobsDB/工作台扩展。已有 checkout 请先核对分支与 commit，不要只按文件夹名称判断版本。

## 准备数据库和页面

1. 从官方 [Releases](https://github.com/loks666/get_jobs/releases) 下载 `getjobs.db.template`，以 `db/getjobs.db` 放在当前 checkout。
2. 模板提供结构、字典和项目设置，不是已确认个人资料；核对自己的凭据和配置，不使用作者示例经历。**已有数据库不得覆盖或直接复制到公开仓库。**
3. 本分支只在六张配置表全空时加载安全默认值；已有配置保留。新库默认只扫描，AI/图片/重复发送关闭，自我介绍、招呼语和黑名单为空。
4. 安装并构建前端：

```bash
cd front
npx --yes pnpm@10.17.1 install --frozen-lockfile --ignore-scripts
cd ..
# 确保 JAVA_HOME 指向本机 Java 21
bash gradlew --no-daemon workbench --console=plain
```

## 管理页和登录

访问 `http://127.0.0.1:9527/workbench`，可管理已确认资料、港区/国内中英文独立简历、岗位及待补强卡片。

管理启动不会打开招聘浏览器。Boss 页须点击“打开 Boss 登录浏览器”并确认，由用户自己完成账号/验证码。登录、保存配置或按下运行按钮，都不等于发送授权。新库只扫描；非调试申请仍需要精确、有效、单次的岗位审核，未知结果不自动重投。

JobsDB 继续走独立 CLI、会话、账本和原审核合同；工作台不直接通过 HTTP 启动 apply 或批次。

## 图片与重复联系的限制

图片可在 Boss 页上传供运行时存储、替换和预览，目录是 `data/boss/resume/`，不属于 classpath/JAR。**不要把个人简历放进 `src/main/resources/`**，旧资源简历已排除打包。

本审核版不自动发送图片，也不允许用重复沟通开关授权再次联系。旧开启值会显示警告，可明确关闭后保存，不删除消息或图片。上传、预览、保存都不是附件或重复发送的审核。

## 官网 AI 与通知

官网/简历任务要另外配置可信的通用 AI 执行器，默认未配置不可执行，不会伪装为已投递。工作台状态以归属明确的事件和回执为依据，企业招聘状态只在主动查询后更新。

提醒仅在页面运行且用户授予通知权限时尽力提供，不是全天候或手机微信推送。详细配置与验证命令见 [工作台使用](/workbench)。

真实网站的登录、表单和回执仍须用户另行授权验收；本指南没有要求自动开始申请。
