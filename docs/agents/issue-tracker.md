# issue tracker：GitHub

此仓库使用 GitHub Issues 跟踪请求、spec 和 Ticket。使用 `gh` CLI 进行所有操作。

## 惯例

- **创建问题**：`gh issue create --repo liu-qingyuan/get_jobs --title "..." --body "..."`。对多行体使用定界符。
- **阅读问题**：`gh issue view <number> --repo liu-qingyuan/get_jobs --json number,title,body,labels,comments`，读取完整标题、正文、标签和评论。
- **列出问题**：`gh issue list --repo liu-qingyuan/get_jobs --state open --json number,title,body,labels,comments --jq '[.[] | {number, title, body, labels: [.labels[].name], comments: [.comments[].body]}]'`，带有适当的 `--label` 和 `--state` 过滤器。
- **对问题发表评论**：`gh issue comment <number> --repo liu-qingyuan/get_jobs --body "..."`
- **应用/删除标签**：`gh issue edit <number> --repo liu-qingyuan/get_jobs --add-label "..."` / `gh issue edit <number> --repo liu-qingyuan/get_jobs --remove-label "..."`
- **缺失标签**：仅用户显式运行 setup 时补齐配置缺项；不删除、重命名或覆盖已有标签，不顺手标记 issues 或启动 Ralph。
- **关闭**：`gh issue close <number> --repo liu-qingyuan/get_jobs --comment "..."`
- **Pull requests**：不进入 issue triage 状态机，不应用 triage 标签，也不进入 Ralph backlog；继续使用正常 review 流程。

所有 GitHub 操作显式指定 `liu-qingyuan/get_jobs`；`gh api` 使用 `repos/liu-qingyuan/get_jobs/...`。`upstream` 仅为源码来源，不依赖多 remote 下的自动仓库选择。

## 语言约定

issue 标题、正文、评论和完成摘要默认使用中文。labels、命令、路径、代码标识符、配置键和错误原文保留原 token。

## Ralph-ready issue 契约

进入 Ralph backlog 的普通 issue 必须是 open + `ready-for-agent`，并在正文末尾包含：

```markdown
## Blocked by

- #<issue-number>，或 `None — can start immediately`

## Git

- Branch: `<target-branch>`
- Base branch: `<base-ref>`
- Base commit: `<full-40-character-sha>`
```

- 多 Ticket 工作由 `to-spec-lqy` 建立父 spec 契约，再由 `to-tickets-lqy` 原样复制；普通 issue 由 `triage-lqy` 直接建立契约。
- 未指定 `Branch` 时，使用 producer 的 `--repo` 所在 worktree 当前 attached branch；已有 remote upstream 时作为默认 Base branch，缺少 upstream 或 detached 才回退 remote default。resolver fetch 后固定完整 Base commit；不自动生成或选择其它 feature branch。
- `ready-for-agent` 必须最后应用：先验证正文、agent brief、Git 契约和 worktree，再发布 ready 状态。
- `to-spec-lqy`、`to-tickets-lqy` 和 `triage-lqy` 使用已安装 `ralph-plan-lqy` 的共享 resolver、validator 和 provisioner；缺少依赖时停止。
- Ralph publication 和 eligibility 完全忽略 assignees；不读取、不修改，也不用 assignee claim。下方 Wayfinder 的独立 assignee 约定不受影响。
- Ralph 只消费当前 attached branch 的 Tickets，并在 branch 内按 issue number 升序选择第一个通过 eligibility gate 的任务。
- 当前 branch 没有可领取 Ticket 时只结束该 branch worker；其他 branch 的 backlog 不阻止完成。
- 缺失、重复或 malformed Git 契约是持久契约错误，worker 必须停止并报告，不能静默跳过。

## Branch worktree

- 日常工作优先 `main`；仅明确需要隔离时使用其他分支。已有父子 issue 的 Git 契约须由对应规划流程显式确认并保持一致，不能因源码合入 `main` 或切换 worktree 自动改写。
- 所有目标 branch（含 `main`）优先通过 exact branch worktree registry 复用已登记的 worktree，不强制迁移到 primary，也不切换或清理其他 worktree。未登记的非主 branch 才按约定创建独立 worktree；未登记的主分支先停止并确认，不擅自切换 primary。
- provision 只允许普通创建和 fast-forward push，不执行 reset、rebase、force-push、覆盖、随机改路径或自动清理。
- 每个 worktree 使用独立 `.ralph/` 状态和 OS file lock；完成后保留 branch、worktree 和本地状态，等待维护者显式合并或清理。

## 可选子 agent 实施

用户授权并行实施后，可由主流程派发 `implement-spec-subagent-lqy`。父子 Git/blocker 合同仍指向最终集成目标；工作区按项目约定选择，临时工作分支不改合同。共享 worker 不操作 Git/index，独立 worktree 可本地 commit；最终 review、验证、push/关闭由单一整合者负责。共享写入结束或协调暂停后才收口，不能提前释放依赖或与 Ralph 消费同一批 Tickets。

## 当技能说“发布到 issue tracker”时

创建 GitHub 问题。若它将进入 Ralph backlog，先满足上述契约并使用对应 skill 的 publication gate。

## 当技能说“获取相关 ticket”时

运行 `gh issue view <number> --repo liu-qingyuan/get_jobs --json number,title,body,labels,comments`；先确认是 issue，而不是 PR。

## Wayfinding operations

由 `wayfinder-lqy` 使用。map 和 Decision tickets 只用 wayfinder 标签，不带 `ready-for-agent` 或其它 triage 状态，不进入 Ralph。取得真实 ids 后再交叉引用；路线清晰后另用 to-spec / to-tickets 发布实施契约。

- **Map**：一个带 `wayfinder:map` 标签的 issue，正文保存 Notes / Decisions-so-far / Fog。使用 `gh issue create --repo liu-qingyuan/get_jobs --label wayfinder:map`。
- **Child Ticket**：map 已取得 id 后，使用支持的 `gh issue create --repo liu-qingyuan/get_jobs --parent <map-id>`。不可用时用真实 child ids 的 task list，并在 child body 引用真实 map id。Labels：`wayfinder:<type>`（`research`/`prototype`/`grilling`/`task`），不写占位链接。被 claim 后，把 Ticket assign 给驱动它的 dev。
- **Blocking**：GitHub 原生 issue dependencies，是 canonical 且 UI 可见的表示。使用 `gh api --method POST repos/liu-qingyuan/get_jobs/issues/<child>/dependencies/blocked_by -F issue_id=<blocker-db-id>` 添加边，其中 `<blocker-db-id>` 是 blocker 的 numeric **database id**（`gh api repos/liu-qingyuan/get_jobs/issues/<n> --jq .id`，不是 `#number` 或 `node_id`）。GitHub 报告 `issue_dependencies_summary.blocked_by`（仅 open blockers，这是 live gate）。如果 dependencies 不可用，回退到 child body 顶部的 `Blocked by: #<n>, #<n>` 行。每个 blocker 都关闭后，Ticket 才 unblocked。
- **Frontier query**：列出 map 的 open children（`gh issue list --repo liu-qingyuan/get_jobs --state open`，scope 到 map 的 sub-issues / task list），丢掉有 open blocker 的项（`issue_dependencies_summary.blocked_by > 0`，或 `Blocked by` 行里有 open issue）或已有 assignee 的项；map 顺序里的第一个胜出。
- **Claim**：`gh issue edit <n> --repo liu-qingyuan/get_jobs --add-assignee @me`，这是该会话的第一个 write。
- **Resolve**：`gh issue comment <n> --repo liu-qingyuan/get_jobs --body "<answer>"`，然后 `gh issue close <n> --repo liu-qingyuan/get_jobs`，再向 map 的 Decisions-so-far 追加 context pointer（gist + link）。
