#!/bin/bash
echo "编译..."

# 基础编译文件（CLI + 核心层）
BASE_FILES="src/com/datacube/DataCube.java src/com/datacube/cli/*.java src/com/datacube/core/*.java src/com/datacube/source/*.java src/com/datacube/target/*.java"

# JavaFX（可选，无 SDK 时跳过 GUI 文件）
JAVAFX_CP=""
JAVAFX_FILES=""
if ls lib/javafx*.jar 1>/dev/null 2>&1; then
    for jar in lib/javafx*.jar; do
        JAVAFX_CP="$JAVAFX_CP;$jar"
    done
    JAVAFX_FILES="src/com/datacube/fx/*.java src/com/datacube/DataCubeFx.java"
    echo "  检测到 JavaFX SDK，编译 GUI 模块"
else
    echo "  未检测到 JavaFX SDK，跳过 GUI 模块（仅编译 CLI）"
fi

javac -cp "drivers/ojdbc17-23.26.1.0.0.jar;drivers/postgresql-42.7.10.jar$JAVAFX_CP" \
    $BASE_FILES $JAVAFX_FILES
if [ $? -ne 0 ]; then
    echo "编译失败"
    exit 1
fi

echo "打包..."
echo "Manifest-Version: 1.0
Main-Class: com.datacube.DataCube
Class-Path: drivers/ojdbc17-23.26.1.0.0.jar drivers/postgresql-42.7.10.jar" > MANIFEST.MF

# 先打包到临时文件，避免 JAR 被占用时失败
jar cfm DataCube_new.jar MANIFEST.MF -C src com
if [ $? -ne 0 ]; then
    echo "打包失败"
    rm -f MANIFEST.MF
    exit 1
fi

# 清理编译产物
find src -name "*.class" -delete
rm -f MANIFEST.MF

# 替换旧 JAR（如果未被占用）
if [ -f DataCube.jar ]; then
    mv -f DataCube_new.jar DataCube.jar 2>/dev/null
    if [ $? -ne 0 ]; then
        echo "旧 JAR 被占用，新文件: DataCube_new.jar"
        echo "请关闭正在运行的程序后手动替换"
        exit 0
    fi
fi

echo "完成: DataCube.jar"
