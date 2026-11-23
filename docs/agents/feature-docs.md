# Feature 记录

- 已启用时先运行 `./.feature-docs/run list`，只展开相关页面；中断恢复用 `check` 查过期记录，不自动盖章。
- `docs/features/<feature>.md` 只保存当前事实：改原页，不按补丁新建或追加日志。Git 保存历史，Issues 保存任务进度。
- 标题用 Markdown H1，正文描述用途和限制，保留非空 `## 当前行为`、`## 限制与剩余`。JSON 仅 `paths`（关联源码与测试的真实相对 globs）、`fingerprint`（工具维护的已确认版本指纹，不表示代码审查）。旧格式仍兼容，不自动改写。

## 同 worktree 并行（独立小任务）

- 派发前指定单一整合者，以及各 worker 不相交的实际写入文件和记录页；`paths` 是引用，不是写锁。同页、公共合同/样式/配置或其他共享文件由一人负责，不能按“模块名字不同”推定独立。
- worker 只改自己的代码、测试和页面，运行无共享产物的聚焦测试，再 `review <feature> --confirm`。其他页面未完成不阻断此复核；全局 list/check 仍可能失败，不替别人改页或盖章。
- 只有整合者改共享 index、安装/范围配置，执行 commit/push/issue 收口及完整套件、共享 build/服务器测试。worker 不 add/reset/stash/checkout，不用 `review --staged`；需要共享文件时交回整合者串行处理，不另造分支或 worktree。
- 整合者只暂存获准交付的完整文件，核对 `git diff --cached --name-only` 和暂存 diff 内容后按下方流程交付；他人未暂存修改不混入提交。记录门禁不是写入隔离或调度器。

## 交付（单任务或已指定的整合者）

运行项目原有测试并核对事实，然后：

```bash
./.feature-docs/run review <feature> --confirm
# 一并暂存代码、测试、页面；需要绑定 index 时 review 可加 --staged。
./.feature-docs/run check --staged
# commit 后：
./.feature-docs/run check --ref HEAD
```

review 不自动暂存、不代替测试。pre-commit 查 index，pre-push 查实际 tip；失败先修复，不报告交付完成。

## 显式 setup

工具/模板相对 setup skill 目录，项目数据相对任务 worktree。先选一个可确认能力，不补全历史；无真实能力/测试入口或 hooks/资产冲突时报告未启用，不捏造事实或覆盖定制。

```bash
python3 <setup-skill>/scripts/feature_docs.py --repo <project> install --scope '<code-glob>' --scope '<test-glob>'
```

`install --scope` 只追加范围；从新版 setup 工具执行 `install` 升级，保留范围与业务页。记录不放 `.agents/skills/`；门禁资产随项目提交，不忽略。clone 后 `./.feature-docs/run install` 恢复 hooks。

Git linked worktree 共享仓库级 `core.hooksPath`，但 hooks 使用当前 worktree 内的门禁资产。其他 worktree 缺少这些资产时不能视为已纳管；仅在获准同步对应版本后安装并核实，不自动迁移其他 worktree。

只保证声明范围的引用、归属和版本绑定，不判断散文真假、执行业务测试或硬限制 issue 关闭；hooks 可绕过。受管链接拒绝，无关链接不展开，Git 忽略的未跟踪文件不受管。退出码 `0/1/2`：一致/缺失过期冲突/调用环境错误；check 只读。
