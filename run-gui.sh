#!/bin/bash
# JavaFX GUI 模式启动脚本
# 自动检测 fat-jar（内嵌 JavaFX 类）vs 瘦 jar（外部 JavaFX 模块）
# 自动添加 JDK 17+ 所需的 --enable-native-access=ALL-UNNAMED

# 选择 java
if [ -d "jre" ]; then
    JAVA="./jre/bin/java"
else
    JAVA="java"
fi

# ===== 公共参数：JDK 17+ 必须解锁 native access =====
JDK17_OPTS="--enable-native-access=ALL-UNNAMED"

# ===== 自动追加 native lib 路径 =====
NATIVE_OPTS=""
if [ -d "lib/native/win" ]; then
    NATIVE_OPTS="-Djava.library.path=lib/native/win"
fi

# 探测 fat-jar
FAT=0
if [ -f "DataCube.jar" ]; then
    if unzip -l DataCube.jar 2>/dev/null | grep -q "javafx/application/Application\.class"; then
        FAT=1
    elif jar tf DataCube.jar 2>/dev/null | grep -q "javafx/application/Application\.class"; then
        FAT=1
    fi
fi

if [ "$FAT" = "1" ]; then
    # 内嵌 JavaFX 的 fat-jar
    $JAVA $JDK17_OPTS $NATIVE_OPTS -jar DataCube.jar --gui
else
    # 瘦 jar：需外部 lib/javafx*.jar
    if ls lib/javafx*.jar >/dev/null 2>&1; then
        JAVAFX_OPTS="--module-path lib --add-modules javafx.controls,javafx.fxml"
        $JAVA $JDK17_OPTS $JAVAFX_OPTS $NATIVE_OPTS -jar DataCube.jar --gui
    else
        echo "[ERR] 未检测到 JavaFX SDK jar 或 native dll。请将以下任一放入 lib/ 目录："
        echo "  - 方式 A（推荐）：下载 openjfx-21.0.7 SDK 并解压到 lib/javafx-sdk/，然后执行 ./build.sh 重新打包。"
        echo "  - 方式 B（最小）：下载 javafx-*.jar 和 Windows native dll 到 lib/。"
        echo "下载地址: https://gluonhq.com/products/javafx/"
        exit 1
    fi
fi
