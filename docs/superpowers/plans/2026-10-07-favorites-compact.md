# SQL 收藏小窗口与焦点可见性

2026-10-07，维护者继续推进产品。main基线f5b2b7dff4fd2000a234a1b12fbee863f84efd34，授权工作区干净；复用已附加独立worktree，新分支codex/favorites-compact-20261007。前轮写完成/刷新失败状态已交付，不能重复实现或削弱。

## S0 目标与证据边界

SqlFavoritesDialog仍为760×650首选VBox内容、没有外层滚动，最小内容可能超出小窗口。当前是源码风险，先用实际shown JavaFX窗口的尺寸与屏幕坐标/viewport测量复现裁切或不可达，不能靠节点isVisible断言。只用mock、合成正文/UUID和独占TempDir；不访问真实用户配置、凭据、SQL历史或业务文件、真库、系统剪贴板；.testagent禁读改/枚举/暂存。最终为工程与合成FX证据，不宣称原生输入/OS缩放/完整M8完成。

## S1 限定产品修复

目标是紧凑窗口（优先480×480和640×480）下，收藏列表/名称/分组/SQL/所有操作/状态和说明均可通过滚动访问，底部取消保留；宽窗口正常。键盘焦点变化自动显露需要交互的控件，Ctrl+F包括过滤框已经保持焦点而被手动滚离时应重新显露；禁用过滤框不抢焦点/不清除编辑。长SQL不得迫使窗口/内容横向无限增长，SQL编辑内部滚动/光标保留。首次读取、保存、真实写失败、已完成待重读、受保护副本和重读恢复的状态均保留；异步布局变化和关闭后迟到工作不能复活窗口。

优先在SqlFavoritesDialog及对应测试内作最小调整，参考现有SchemaObjectSearchDialog/SchemaMetadataSearchDialog的布局经验，不改已有搜索框或建立无需求的新通用框架。GPT-6.1-sol负责复现/实现，root独立审查源码和原始红绿，先定向后全量。不要仅为关闭夹具重写生产所有权；生产show已有try(view)+showAndWait。

## S2 本地与主线交付

冻结源码SHA、最终定向（收藏Dialog/Tabs/Store、FxTaskScope、DraftDirectory、TabFileLifecycle以及必要布局回归）、clean test、root :buildSrc:test --rerun-tasks、jpackageImage。Gradle单一执行者；只归档实际执行任务XML，跳过不算通过，失败和中断保留。root镜像隔离/零连接驱动发现、精确原件manifest/Git字节核验，提交并合并main，新执行同一矩阵，更新交接和M8待验。

按现有授权只推送main至既定GitHub仓库，命令级7897代理，精确SHA CI通过后交付；不fetch/force/tag/PR/发布/安装更新/外部联系、不创建新侧栏线程或自动恢复datacube跟进，v3.2.9与PAUSED状态保持。重大范围变化才问；常规方案自主决定并记录。

S0：当前main/worktree范围干净与源文件已核对，尚无本轮新复现/测试。下一步建立真实尺寸裁切/焦点不可见红灯，再确定最小修复。

S1：实际shown Stage两尺寸/两主题4例几何红灯：取消越出scene441高，480宽说明部分被裁切。001编译夹具错误与002产品几何失败分别保留。root接受最小scroll/宽度/焦点方案，已要求修正测试手动滚动与延迟断言顺序；实现及新定向进行中，full/main/原生仍未验。

S2：最终六组84项新定向全通过、0skip；root完成生产/测试/原XML审核，冻结源和测试SHA相符。哈希字段格式不一致的root工具误报已澄清，未改源码/放宽摘要校验。全量、root buildSrc、image串行启动，完整main/原生待验。

S3：分支最终84项定向、3997全量通过/3live跳过、buildSrc8和image均新执行完成；root独立原XML/摘要/镜像隔离及零连接审计通过，源码已提交125f954。准备证据冻结与main合并、新复验；原生/完整M8不提升验收等级。

S4：main集成f5bc602并完成新定向84、全量3997通过/3live跳过、buildSrc8、image/镜像隔离与零连接复验，三实际产物与分支SHA/长度一致，370份worker原件及受验产品树复核通过。本地限定目标完成，原生/完整M8仍待验。仅剩最终main记录归档、授权推送及精确SHA CI收尾，保留旧tag和PAUSED跟进。

S5：最终证据 bdf274c 已推送。Verify 37593098366 首次 Windows 旧概览测试发生 5 秒 FX 等待超时，linked image 跳过，其他三个任务成功；失败原件保留。root/sol 未找到确定修复依据，未改源码/测试/timeout，仅同 SHA 失败 job 单次重跑。重跑结果与后续精确最终 SHA 验证待核对，完整 M8 仍未完成。

S6：同 bdf274c 的唯一一次重跑 attempt 2 四任务成功，Windows单元测试及linked image实际通过，失败/重跑原件分开保留。只能说明本次未复现，旧FX超时根因未知仍待诊断；源码/断言/timeout均未改。仅合并此证据并推送，最终精确SHA的新Verify以独占回执确认，原生/完整M8不提升验收等级。
