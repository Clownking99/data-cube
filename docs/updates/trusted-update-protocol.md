# DataCube 可信更新协议 v1

状态：2026-09-24，本地实现；生产信任根和签名发布流程尚未配置。仓库中的
`resources/com/datacube/update/trusted-keys.properties` 故意不含生产公钥。
当前构建允许检查更新、打开官方页面，禁止自动下载执行未认证更新。

## 信任与兼容迁移

SHA-256 只能证明内容与清单一致。同一个 Release 中的未认证摘要不能证明发布者身份。
客户端仅接受随应用审查分发的 Ed25519 公钥，不从 Release 下载新的信任根。
签名错误、缺失、过期、未知 key id、错误版本/平台均拒绝自动执行，没有仅校验长度的降级路径。
旧客户端不具备此能力；应从可信渠道手动取得并验证新构建，再进入受信任更新链。
旧 Release 没有协议资产时仍可显示版本信息，但只提供官方手动下载页。

生产公钥选择和独立核对、私钥托管、受保护发布流水线、签名和远端资产上传均待明确授权。
本轮未生成、读取、提交生产私钥，测试仅使用进程内临时密钥。
轮换方案：先由现有可信版本分发经审查的新旧公钥集合；过渡期按客户端可识别的 key id
签名；随后发布移除旧公钥的构建。v1 每个清单只有一个签名，不能宣称任意旧客户端都能
无缝跨过轮换。密钥泄露需停止自动发布、独立渠道公告并手动迁移信任根；v1 没有在线撤销服务。

## 发布资产与精确字节格式

固定仓库 `https://github.com/Clownking99/data-cube`，tag 为 `vMAJOR.MINOR.PATCH`。
版本各部分为 0 或不含前导零的 1–9 位十进制数，必须高于当前客户端版本。
发布需同时提供两个包、`datacube-update.manifest` 和 `datacube-update.manifest.sig`。
客户端检查 API 中资产名及 URL；下载仅经 HTTPS，逐跳检查 GitHub/CDN 主机白名单，最多 5 次重定向。

清单使用 UTF-8 无 BOM、LF 换行，固定下面 8 行，最后也有 LF；字段顺序固定。
最后两行分隔符是实际 TAB，示例尖括号内容需替换，不能包含空行或 CR。

```text
datacube-update-v1
key=<key-id>
version=<MAJOR.MINOR.PATCH>
platform=windows-x64
issued=<UTC Unix seconds>
expires=<UTC Unix seconds>
setup<TAB>DataCube-v<version>-win64-setup.exe<TAB><bytes><TAB><lowercase SHA-256>
portable<TAB>DataCube-v<version>-win64-portable.zip<TAB><bytes><TAB><lowercase SHA-256>
```

key id 为 1–64 个 ASCII 字母、数字、`_` 或 `-`。公钥资源采用
`key-id=Base64(X.509 SubjectPublicKeyInfo)` Properties 格式；只装载 Ed25519 公钥。
签名是对清单**原始全部字节**的 Ed25519 签名，`.sig` 为原始 64 字节，非 Base64/hex。
清单最多 16 KiB，签发时间最多允许快 300 秒，有效期不超过 90 天且未过期。
时间依赖本机时钟；不提供持久化的最高已见版本或离线防回退硬件保证。
每个包 1 字节至 1 GiB，签名绑定版本、平台、精确文件名、大小和摘要。

获授权后的发布顺序：构建并测试最终两个包 → 根据最终字节计算大小/摘要 →
构造上述清单 → 在受保护签名环境签署精确字节 → 离线用已核对公钥验证 →
将两个包、清单及签名一起发布至对应 tag。不得重打包后复用原签名。
当前 release workflow 未接入签名，不能据此文档宣称线上自动更新已启用。

## 下载、替换与关闭

元数据请求上限 1 MiB、30 秒；更新任务总期限 10 分钟，连接超时 15 秒。
没有 Content-Length 时仍执行流量上限和签名大小/摘要校验。
取消或关闭服务会关闭在途流并中断工作线程；交接之前取消不得启动进程。
交接与取消有互斥边界；交接后不能声称“已取消”，同一服务实例不再启动第二个 helper。

便携更新在目标同级创建唯一 `.datacube-update-*` 目录；安装版使用唯一临时目录。
包只写入新文件，拒绝覆盖既有文件。Zip 解压最多 10,000 条目、2 GiB，根目录必须是
DataCube，拒绝绝对/越界路径、Windows 设备名/别名和大小写重复文件。
链接属性不会被转换为文件系统链接。要求 exe、配置、运行时模块和 java.exe 非空。
运行前再校验包摘要；路径和 helper 会拒绝链接/重解析目录。

helper 使用 Windows PowerShell 5.1 和 .NET 路径/摘要 API，不使用 cmd 拼接，不绕过系统执行策略。
计划绑定唯一目录、token、版本、目标、包摘要、旧 PID 和启动时间，避免误等复用 PID。
更新 UI 不直接退出进程：用户在 5 分钟内通过主窗口正常关闭，保留事务和资源关闭守卫。
窗口未关闭则 helper 取消替换。PowerShell 策略/权限阻止 helper 时可能停留 PREPARED；
“已交接”不表示 helper 已完成、安装完成或升级成功，查看状态文件并走手动恢复。

## 状态与恢复

每次尝试的工作目录保留 `plan.json`、`owner`、包和 `state.json`。

| 状态 | 含义 / 恢复材料 |
| --- | --- |
| PREPARED | 已验证并暂存，尚未开始替换 |
| FAILED_OR_CANCELLED_BEFORE_HANDOFF | 准备失败或取消；原目录不变 |
| WAITING_FOR_EXIT / CANCELLED_BEFORE_SWAP | 等待正常退出 / 超时未替换 |
| BACKING_UP / BACKED_UP | 移动原目录前 / 旧版已在 `previous` |
| REPLACED / STARTING | 新目录已就位 / 尝试启动，不代表成功 |
| START_CONFIRMED | 新主窗口显示后，版本、token、appDir、PID 的启动确认匹配 |
| START_UNCONFIRMED_BACKUP_RETAINED | 60 秒仍未确认，保留当前进程和备份 |
| ROLLED_BACK | 旧目录已恢复，并尝试重启旧版 |
| FAILED_BEFORE_SWAP | 替换前失败，原目录不变 |
| RECOVERY_REQUIRED_PROCESS_RUNNING | 新进程还活着；不强杀、不移动运行中的目录 |
| RECOVERY_REQUIRED_BACKUP_RETAINED | 恢复/旧版启动失败，保留现场供人工恢复 |
| INSTALLER_STARTING / INSTALLER_STARTED_UNCONFIRMED | 安装程序交接阶段；不代表安装器完成 |

便携版将原目录移至 `previous`，再移动暂存的新目录。新进程启动失败或确认前退出时，
将新目录保留为 `failed-new` 并恢复旧目录；恢复失败不删除任何版本。
确认后的备份也不自动清理。断电/强制终止可能发生在两次目录移动之间，状态可能滞后；
此时不自动重放计划，人工检查实际目录：先退出相关进程，核对 plan 的目标与 token，
保留当前和失败目录，确认 `previous` 完整后再恢复到精确原路径。不要按通配符删除目录。
本地同权限攻击者能修改应用、信任根或计划，不在此协议提供的安全边界内。

安装版仍委托已认证安装程序；安装器事务、UAC、回滚和实际安装结果尚未原生验收。
启动确认仅表示对应主窗口已显示，不等于所有功能、数据迁移或真实数据库通过。
本地测试覆盖 mock 下载、恶意包、取消/关闭及合成安装树中的真实 helper 脚本，
不能替代真实升级、签名流水线、原生桌面和最终发布验收。
