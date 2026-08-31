# P2.3b 活动工作区与退出冻结

基线：`62997db5f4278e472061927fd3f708e87c4c84be`；已有 P2.3a 共用 writer 桥。此增量必须接入实际 FX 标签与 AppShell 退出，不以新增纯状态类替代用户路径。恢复入口仍属 P2.4。

## 选择

采用独立的活动工作区状态 owner、轻量 FX 适配器，以及受管标签关闭事务内部的最终化 gate。拒绝在 `closeAllManagedTabsMandatory()` 已完成后再用外层 future 返回取消：那时 registry 已 CLOSED，应用无法再打开受管标签。也不在批量关闭前发布最终清单，因为新草稿的最后保存尚未完成。

活动 owner 复用 SqlDraftCoordinator，不创建第二 store、线程或定时器。FX 适配器从真实 TabPane 顺序和已安装 binding 捕获，现有 250 ms timer 驱动确认与防抖。仅比较条目/选中项，时间戳变化不算布局变更。防抖空闲 1000 ms、持续活动最长 10000 ms；最多一个已提交写和一个最新内存候选。BUSY 留候选等下次 pulse；普通失败停自动重试，用户明确重试后再写。

## 活动与隐私

- 未打开/编辑/明确恢复 SQL 的启动会话不生成布局；直接退出不写空文件。
- 成功安装 SQL 标签、用户编辑/移动光标/选择及真实标签移除启动或更新活动。保存确认使新草稿变为可记录，必须在无额外用户动作时补入清单。永远空白且未保存过的标签不记录；已保存后清空 SQL 的标签仍记录。
- 非 SQL 标签不进入条目；选中非 SQL 标签时 selected UUID 为 null。捕获保留 UTF-16 anchor/caret 方向，最多 100 项；超过契约上限返回固定失败反馈，不偷偷截断。
- 运行时暴露只读 UI-owner `workspaceGeneration()`。候选附带捕获代次；P1 清空/删除/启停及 P2 清空/启停一经接受，旧候选和已排队写都失效。仅轮询状态不能把旧候选换一个新代次重新提交；必须有新明确用户活动。
- 独立记录开关通过活动 owner 修改；关闭意图立即暂停自动捕获，即使写偏好失败仍维持本次暂停，显示“关闭设置未保存，下次启动可能恢复”，不相信旧的磁盘 true 值自动恢复。明确重新开启并成功持久化才能恢复。关闭不删除旧清单，也不关闭 P1 草稿保护。
- P2 清空只清布局引用；P1 管理失效不改变原有草稿保存、文件格式、事务关闭契约。损坏布局/独立偏好只使工作区不可用；共享目录结构性故障沿用 a 的停用行为。

## 退出协议

1. ContentTabPane 在同一次关闭尝试中阻止新受管内容准入，并在现有 reservation 结算后、开始关闭各标签前通知 FX 适配器冻结完整顺序/选中/位置（须处理正在构造中的标签）。所有操作回到 FX owner；不能后台读取节点。
2. 冻结期间忽略由关闭导致的移除/自动选择及 timer 候选，不生成逐步缩短或空清单。冻结包含尚未保存的新 SQL 身份，最终仅筛选真实成功保存的身份。
3. 原有 mandatory guards 完成最终草稿保存与清理。等待所有已获准 reservation、标签 settlement 和 mandatory-abort settlement。CANCELLED/FAILED_PARTIAL 不升级为 COMPLETED，保留冻结恢复点，不进行 destructive teardown。
4. 仅在所有清理成功后运行最终清单 gate；此时 registry 仍处于关闭尝试，而非 CLOSED。确认最终保存身份时不可将 installedContent 当作磁盘确认。可使用 UI 已确认 checkpoint 或经过同一 writer 的已验证草稿快照；不依赖 CompletableFuture 回调注册顺序。
5. 最终清单写失败在 FX 显示固定消息，提供“重试”“取消退出”“忽略本次工作区更新并退出”。默认取消。重试写同一个冻结布局；忽略不删除/清空旧布局。对话框不展示原始异常、路径、SQL。
6. 成功/明确忽略才封口 registry CLOSED 并允许 AsyncShutdownCoordinator 关闭 writer。取消 gate 则在 ContentTabPane 的 ownershipLock 内完成 abort tracker 轮换及 registry OPEN，之后结算 CANCELLED；无需重建已关闭标签，用户能继续打开新标签。真正 FAILED_PARTIAL 不重置为 OPEN。
7. 取消/部分失败保留冻结布局和最近磁盘恢复点；在下一次明确用户编辑/开关标签前不自动覆盖。重复退出请求共用尝试，调用者取消 future 不取消内部清理/写盘。

## 文件职责

- `SqlWorkspaceActivity`：UI-owner 的候选、代次、防抖、背压、明确暂停/重试和冻结状态；不依赖 JavaFX。
- `SqlWorkspaceUi`：节点/binding 到不可变快照的转换、真实活动监听、固定反馈与退出决定；不执行 I/O。
- `SqlDraftUi` / `SqlDraftEditorBinding`：提供安装、保存确认、编辑位置的窄桥，沿用唯一 timer/runtime；保留 P1 关闭守卫行为。
- `AsyncManagedTabRegistry` / `ContentTabPane`：尝试级 gate 和 abort 结算所有权，原无 gate API 行为保持。
- `AppShell`：惰性绑定 activity owner，实际退出走 gate；未初始化时不创建草稿/布局 owner。

## 验收

使用手动 executor/时钟验证布局确切内容与写入次数、初始无写、首次保存、持续防抖、背压、旧代次、失败停止和隐私暂停。FX 集成使用合成 TempDir 与零连接探针，验证真实节点顺序/反向选择/取消单标签/批量冻结、退出 gate 取消后再次打开 managed tab、retry/ignore、部分失败与正在安装 reservation。不读取真实配置，不连接数据库。

沿用 P2 总设计全部 Global Constraints；不推送/tag/发布，不提前合并 main。P2.4 恢复入口、P2.5 桌面/打包/全分支审查尚未验收。
