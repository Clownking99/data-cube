# C11：G9 P3 最终工程交付完成

当前目标：完成既有 G9 整表导出可靠性范围；不启动下一轮。

改动：产品 e7950123、测试兼容修正 6b8ceb93 已审查、合并；最终 main `b6622c95d5efc0b240dc7ae4b788f44542c87b5a` 已直连推送，远端 SHA 相同。后续回执独占保存于本目录，避免为记录 CI 结果改变受验 SHA。

验证：新 main 受影响 85 通过，clean 全量 4664 通过/3 live 跳过，buildSrc 8 通过，jpackageImage 与外置 linked probes 通过；另有 headless 16 项明确跳过/0 通过。492 份本地验证原件与 Git 提交字节核验一致。精确 [Verify 37828177473](https://github.com/Clownking99/data-cube/actions/runs/37828177473) attempt 1 四任务全部 success，逐 job 原日志确认 Linux/Windows 实际 test、buildSrc:test 和 Windows 实际 jlink；CI 未发布完整通过/跳过计数，不套用本地数值。详见 delivery-result.json、independent-ci-review.json 与 manifest.json。

失败/未验：首次 CI Run 37822449579 两平台失败原件保留；原 Windows FX 初始化超时在新本地与本次 CI 未复现，但根因未知，未改该用例等待、断言或产品取消逻辑。真实 DB/pg_dump/libpq、驱动 MVCC/undo/网络/取消、大值内部分配、原生桌面/chooser/Excel/缩放、慢或网络文件系统/原子替换与校验至 move/unlink 竞争窗口、永久阻塞 pending、捕获进程家族边界、保守 DDL 类型/仅列与 PK 和 DDL-数据非完整 Schema 快照、安装升级/回退/生产签名与完整 M8 仍保留待验。本次是 G9 工程交付，不是完整发布验收。

下一步：已依维护者授权将 datacube-g9 跟进设为 PAUSED，实际保存状态已核对；旧 datacube 与 v3.2.9 未改，不启动新任务。未 fetch、操作 tag、建 PR、发布或联系外部人员。
