# 当前系统上下文

这张 C4 Level 1 图描述**当前仓库的系统边界**，不是未来设计或平台可执行性承诺。完整资格见 [多平台能力总览](/platforms)，本机程序内部结构见 [容器与运行结构](/architecture/c4-containers)。

图用 Mermaid 排版 C4 元素，名称下明确标注类型；可横向滚动，点击可打开全尺寸 SVG。蓝色是本项目工作台，灰色是边界外的使用者、平台或独立程序，箭头标明请求的发起方。

<div class="architecture-diagram">

[![当前系统上下文：使用者分别访问本机工作台和静态文档，工作台经渠道 Adapter 或可信本机命令访问招聘来源](/architecture/c4-context.svg)](/architecture/c4-context.svg)

</div>

## 如何理解边界

- **本机工作台**保存资料、简历、范围、候选、项目和回执。默认仅准备；管理页启动不是招聘浏览器启动或发送授权。
- **招聘来源相互独立**：图中汇总官网、OfferToday、Boss、JobsDB，不表示共享账号、接口或发送资格。OfferToday 公开找岗已实站通过但自动发送关闭，Boss / JobsDB 受控外发尚未完整实现；官网依赖可信命令且逐站验收。
- **可信命令仍在本机**：通用命令处理官网 / 材料任务，项目执行和独立验收各自配置。它们不是新云服务或 OS 沙箱；任务限制不能被理解成对任意恶意程序的隔离保证。
- **公开文档站不在业务数据链路上**：免费 GitHub Pages 只提供说明，没有通往本机 HTTP、账号、SQLite 或私有文件的连接。
- **回执有限**：工作台校验并保存可信执行器 / 人工报告的证据，不独立观察雇主决定。缺证据保持 UNKNOWN，不因退出码 0、已联系或文档部署成功而记为正式申请成功。

## 图源与维护

网页直接展示已生成的 SVG，不在访客浏览器执行 Mermaid。图源保存在仓库，并在下方从同一文件引入，不另维护一份图代码。

<details>
<summary>查看本图 Mermaid 图源</summary>

```mermaid
<!--@include: ./graphs/c4-context.mmd-->
```

</details>

修改 `.mmd` 时同时重新生成两张静态图并检查页面可读性。以下命令在仓库根目录运行，使用临时安装的固定版本工具；不增加站点运行时依赖。若本机没有可用的 Puppeteer 浏览器，先将 `PUPPETEER_EXECUTABLE_PATH` 指向本机 Chrome 可执行文件；不要指定现有用户 profile。

```bash
# 仅生成静态图；不会连接招聘网站或登录会话。
for name in c4-context c4-containers; do
  npm exec --yes --package @mermaid-js/mermaid-cli@11.12.0 --package mermaid@11.12.0 -- \
    mmdc -c docs/architecture/graphs/mermaid.json \
    -i "docs/architecture/graphs/$name.mmd" \
    -o "docs/public/architecture/$name.svg" -b white
done
```

当前领域边界以 [CONTEXT.md](https://github.com/liu-qingyuan/get_jobs/blob/main/CONTEXT.md) 和 [ADR 0003](/adr/0003-unified-workbench) 为准；[旧 V1 设计](/workbench-v1-design) 的“当前 / 目标”保留历史语境，不作为本图现状。
