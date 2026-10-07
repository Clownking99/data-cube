# SQL 收藏紧凑窗口 worker 证据

本轮基线：main f5b2b7dff4fd2000a234a1b12fbee863f84efd34；工作分支 codex/favorites-compact-20261007。只修改 SqlFavoritesDialog.java、SqlFavoritesDialogTest.java 及本 worker 证据/说明。root 独立负责计划、审核、main 交付。未提交、合并、推送或访问网络、真实数据库、剪贴板/用户数据；.testagent 未读取、枚举或修改。

## 真实红灯与实现

002-red-geometry 实际 shown Stage 为 480×480/640×480，light/dark 各一，4 tests / 4 failures / 0 skips。scene 为 464×441/624×441。旧 VBox 在 480 宽度最小 pane 高 516，取消按钮 Y=477.6..506.4；640 最小 pane 高 466，取消 Y=427.6..456.4；都超出 scene 底 441。480 的说明 Y=402..456 也裁切。原始 XML 记录每个控件 localToScene、localToScreen 和实际可见边界；没有以 isVisible 代替几何测量。

最小实现：把原内容放进 fitToWidth/fitToHeight ScrollPane，内容采用可计算首选最小高度和零最小宽度，列表/SQL/命令宽度受 viewport 约束，取消按钮留在 DialogPane footer。焦点变化、viewport 尺寸/内容高度变化显露控件，Ctrl+F 即使过滤框已持有焦点也重新显露。将 SQL/ListView 内部焦点统一至控件边界，不操纵其 caret/selection/内部滚动；close 移除焦点监听且队列回调检查 closed。原 write/load/pendingRefresh、确认及 try(view)+showAndWait 所有权没有改写。

004-green-geometry 同四例 4 / 0 / 0。两个窄尺寸 viewport 为 (11,11)..(439,382) / (11,11)..(599,382)，高度 371，SQL 显露后下边界 382；底部状态/说明滚动可达，取消按钮处于 scene 内。

## 执行账本

所有命令通过原样复制的本轮 coordination Run-Main.ps1 运行，离线 JDK 25.0.1+8、单一 Gradle 执行者、专用合成 profile 和真实 8.3 temp；每次独立 command.json/exit.json/gradle.log，仅归档该次真正执行 test 的 XML。

| 目录 | 实际结果与原因 |
| --- | --- |
| 001-red | compileTestJava 失败：新夹具把两参数 favorite 调成三参数；不是产品红灯，无 test XML。 |
| 002-red-geometry | 4 tests / 4 failures / 0 skips，真实裁切红灯。 |
| 003-green-geometry | compileJava 失败：文本布局块未替换导致 scroll 未定义；不是产品红灯，无 test XML。 |
| 004-green-geometry | 4 tests / 0 failures / 0 skips，真实几何转绿。 |
| 005-expanded-targeted | 65 tests / 10 failures / 0 skips，约 1m40 正常完成，没有中断；六个 640 案例错误要求固定通知必然多行，四个滚轮案例错误把 ScrollEvent 发在 TextArea 外壳。两个过滤名错误导致 Tabs/Draft 未运行，不能算最终六组。旧 29 Dialog 测试本轮通过。曾检查结束后的合成 worker PID，未找到进程、未生成线程文件；随后 rg 的字面通配符查询失败，不是产品检查。 |
| 006-focus-diagnostics | 5 tests / 1 failure / 0 skips；四例正确靶点 .content 的真实内部滚动转绿，宽窗口错误要求 vvalue=0，虽全部几何可见且内容 fit-height，历史 normalized 值仍可保留。改核对 content/viewport 高度。 |
| 007-final-targeted | 84 tests / 0 failures / 0 skips，约 1m24，正确六组均真正执行且退出0；详见 summary.json 和原始 XML。 |

## 新回归边界

- 实际 shown 480×480、640×480，light/dark；节点与 viewport/scene 边界，所选行在 ListView 内；全部收藏按钮在适用状态实际 fire，并核对读取/保存/删除/恢复及确认计数。
- 过滤框已经焦点但手动滚离后 Ctrl+F 显露/selectAll；Tab 到列表；禁用过滤框时 Ctrl+F 保留 SQL 焦点、正文、选区、内部 scrollTop 和外层 vvalue。
- 超长单行加 180 行合成 SQL，真实 TextArea skin .content ScrollEvent 使内部 scrollTop 增加；跨 layout pulses 核对正文/选区/焦点和外层 vvalue 不变；显式 replaceSelection 编辑后 resize 保留 caret/selection，宽度受约束。
- mock Repository 注入的保存/删除/恢复失败，明确重试成功但后续读取失败的 pending 状态、失败重读、成功重读和离线打开/取消；保护副本及首次 busy 读取/关闭拒绝；长状态和说明可滚动查看。
- 焦点/布局不变的手动滚动稳定；footer 焦点不拉动内容；960×760 宽窗口/resize 后编辑焦点保留；合成长状态实际改变内容高度并排队 reveal，关闭后不移动 vvalue、不恢复文本，也不调用 repository。

冻结：final-source/ 为最终源码副本，final.patch 为生产/测试补丁，final-source-sha256.json 记录字节 SHA256。红绿 XML/log 是本轮实际执行原件，失败未覆盖；未声称早期版本源码副本已归档。

原生键盘/鼠标、OS 缩放和完整 M8 未验证。root 已独立审核 007 原始六套 XML、冻结源码与 SHA 并授权全量 clean test、强制 buildSrc:test、jpackageImage；下面补实际执行结果。
红灯复现方案（非新执行）：base f5b2b7d 生产源码配合 final test，仅过滤 compactShownStagesKeepFocusedControlsAndBottomGuidanceReachable，可复现几何越界；不把重构版本当成早期源码原件。

## 审核后执行

root 已独立核对 007 六套 XML 的 84 / 0 / 0 / 0，以及生产/测试实际字节 SHA；审核通过后授权以下顺序执行。源码仍为原冻结版本。

| 目录 | 任务 | 实际结果 |
| --- | --- | --- |
| 008-full-reviewed | clean test | 314 suites / 4000 tests / 0 failures / 0 errors / 3 skips；退出0，4m28。3997 项执行通过，3 个真库 live 门禁未启用的用例跳过，不计通过；详见 skips.json。 |
| 009-buildsrc-forced | root :buildSrc:test --rerun-tasks | 1 suite / 8 tests / 0 failures / 0 errors / 0 skips；退出0，7s。 |
| 010-image-forced | jpackageImage --rerun-tasks | 退出0，49s；14 个任务实际执行，完成 jlink/jpackageImage。使用脚本 Image 分支，测试 JVM 参数未传入包装子进程；该次不归档陈旧测试 XML。 |

全量跳过具体为 RedisLiveIntegrationTest.standaloneRedisSupportsFiveTypesScanTtlAndLifecycle、SchemaDiffLiveIntegrationTest.oracleSafeDeploymentConvergesInDisposableSchemas 和 postgresqlSafeDeploymentConvergesInDisposableSchemas；本轮只 mock/合成/TempDir，未启用这些真库检查。收藏及相关生命周期最终定向 84 项仍为 0 skip。
最后检查：本轮三阶段均完成，Gradle 已释放；冻结生产/测试/patch/Run-Main SHA 全部复核一致。工作树无额外产品修改，未提交。原生键盘/OS缩放/M8依旧未验，main及镜像启动/零连接驱动发现由 root 独立交付验证。
