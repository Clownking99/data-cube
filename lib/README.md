# lib 目录

包含构建 DataCube.jar 所需的全部第三方依赖。

## JavaFX SDK

GUI 模式需要 JavaFX SDK。**当前仓库已包含** `lib/javafx-*.jar` 四个核心模块，
从 https://gluonhq.com/products/javafx/ 下载的 21.0.7 版本：

```
lib/javafx.base.jar
lib/javafx.controls.jar
lib/javafx.fxml.jar
lib/javafx.graphics.jar
```

如果需要其他版本，从上述地址下载后覆盖即可。

## JavaFX Native dll（Windows）

`lib/native/win/` 下是 JavaFX 运行所需的 native dll：

- **13 个核心 dll**（prism_d3d/sw/common、glass、javafx_font/iio、decora_sse、
  msvcp140 系、vcruntime140 系、ucrtbase），共约 2.75 MB

构建时这些 dll 会被打包进 DataCube.jar 的 `lib/javafx/` 目录。
运行时由 `com.datacube.DataCube` 自动解压到 `%TEMP%/datacube-javafx-natives/` 并
以 `-Djava.library.path=<tempDir>` 重启 JVM。

**已删除的非必需 dll**（不需要打包，由 Windows 系统自带或功能未使用）：
- `api-ms-win-*.dll` (41 个)：Windows 系统 API 转发层，系统自带
- `jfxwebkit.dll` (89 MB)：JavaFX WebView（项目未使用）
- `jfxmedia.dll`、`gstreamer-lite.dll`、`glib-lite.dll`：媒体支持（项目未使用）
- `fxplugins.dll`：插件系统（项目未使用）

## 本地编译（无 JavaFX）

如果不使用 GUI 功能，直接运行 `bash build.sh` 即可编译 CLI 版本。
JavaFX 相关文件在无 SDK 时会自动跳过编译。# JavaFX SDK

GUI 模式需要 JavaFX SDK。JDK 21+ 不含 JavaFX，需单独下载。

## 下载

从 https://gluonhq.com/products/javafx/ 下载对应平台的 SDK。

解压后将以下 JAR 文件放入此目录：

```
lib/
├── javafx.base.jar
├── javafx.controls.jar
├── javafx.fxml.jar
└── javafx.graphics.jar
```

## 本地编译（无 JavaFX）

如果不使用 GUI 功能，直接运行 `bash build.sh` 即可编译 CLI 版本。
JavaFX 相关文件在无 SDK 时会自动跳过编译。
