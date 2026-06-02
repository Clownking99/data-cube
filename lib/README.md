# JavaFX SDK

GUI 模式需要 JavaFX SDK。JDK 21+ 不含 JavaFX，需单独下载。

## 下载

从 https://gluonhq.com/products/javafx/ 下载对应平台的 SDK。

解压后将以下 JAR 文件放入此目录：

```
lib/
├── javafx-base.jar
├── javafx-controls.jar
├── javafx-fxml.jar
└── javafx-graphics.jar
```

## 本地编译（无 JavaFX）

如果不使用 GUI 功能，直接运行 `bash build.sh` 即可编译 CLI 版本。
JavaFX 相关文件在无 SDK 时会自动跳过编译。
