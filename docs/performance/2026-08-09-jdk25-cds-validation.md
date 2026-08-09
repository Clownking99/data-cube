# JDK 25 CDS 验证记录

## 结论

当前不在 DataCube 发布镜像中默认生成静态 CDS 归档。JDK 25 自定义运行时能够稳定生成并
加载归档，但本机对照结果没有显示可重复的启动或空闲内存收益；增加约 14MB 包体不符合
项目已批准的 CDS 启用门槛。默认启动器继续使用 JVM 的 `-Xshare:auto` 行为，不需要额外
参数，也不会在归档不存在时阻断启动。

## 环境与方法

- Windows，Temurin JDK 25.0.1+8。
- Gradle 9.2、org.beryx.jlink 4.1.0、JavaFX 25。
- jlink GUI 启动参数：`-Xms16m -Xmx256m -XX:+UseG1GC`，并保留项目现有的堆空闲比和
  G1 周期回收参数。
- 空闲内存在进程启动 12 秒后采样；启动时间从创建进程到 JavaFX 主窗口句柄可见。
- on/off 交替运行，每次只关闭测量脚本自身启动的 PID。

新生成的 jlink 镜像包含 `build/image/lib/classlist`，但不包含默认归档。执行：

```powershell
build\image\bin\java.exe -Xshare:dump
```

会在 JDK 25 Windows 运行时布局下生成
`build/image/bin/server/classes.jsa`。归档大小为 14,680,064 bytes；同一镜像连续生成两次的
SHA-256 均为：

```text
3CB94897070D94A3ADEF4D4AC675B49EE8C46C9D44677C4D1F785783253C3137
```

使用 G1/256MB 和 `-Xshare:on -Xlog:cds=info` 启动时，JVM 明确打开并映射了该归档。

## 结果

12 秒空闲内存，每种模式两个样本：

| CDS | 工作集 MB | 私有内存 MB | 线程 |
|---|---:|---:|---:|
| off | 163.1 | 244.1 | 70 |
| on | 165.4 | 256.8 | 69 |
| on | 160.6 | 241.1 | 69 |
| off | 161.7 | 245.6 | 69 |
| off 均值 | 162.4 | 244.8 | 69.5 |
| on 均值 | 163.0 | 249.0 | 69.0 |

热文件缓存下的六次交替启动：

| CDS | 样本 ms | 均值 ms |
|---|---|---:|
| off | 1014, 995, 1196 | 1068 |
| on | 1012, 1204, 945 | 1054 |

14ms 的启动均值差异明显小于两组自身约 200–260ms 的极差；内存指标没有改善。因此结果
不足以证明 CDS 带来可重复收益。

## 复测方法

先重新生成镜像和归档：

```powershell
.\gradlew.bat jlink --warning-mode fail
build\image\bin\java.exe -Xshare:dump
```

再分别运行至少三个独立样本：

```powershell
.\tools\measure-memory.ps1 -CdsMode off -Samples 3
.\tools\measure-memory.ps1 -CdsMode on -Samples 3
```

`jlink` 重新生成镜像后会移除手工归档。只有后续 JDK、JavaFX 或启动路径变化后出现稳定的
启动或内存收益，并同时通过 jlink、jpackageImage、GUI/CLI 和可复现性验证，才应重新考虑
把归档加入发布流程。
