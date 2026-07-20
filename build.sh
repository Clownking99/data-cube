#!/bin/bash
# DataCube 构建脚本
# 编译所有模块 + 打包 fat-jar（含 JavaFX classes + JDBC 驱动 + JavaFX native dlls）
#
# 用法: ./build.sh
# 产物: DataCube.jar（含全部 classes + drivers + JavaFX + 核心 Windows native dll）

set -e

cd "$(dirname "$0")"

# ===== [1/4] 编译 =====
echo "===== [1/4] 编译 ====="
rm -rf build-out
mkdir -p build-out

JAVAFX_CP=""
JAVAFX_FILES=""
if ls lib/javafx*.jar >/dev/null 2>&1; then
    for jar in lib/javafx*.jar; do
        JAVAFX_CP="$JAVAFX_CP;$jar"
    done
    JAVAFX_FILES="src/com/datacube/fx/*.java src/com/datacube/DataCubeFx.java"
    echo "  JavaFX SDK: 已检测到，编译 GUI 模块"
else
    echo "  JavaFX SDK: 未检测到，仅编译 CLI 模块"
fi

# JSqlParser（SQL 美化依赖）
JSQLPARSER_CP=""
if ls lib/jsqlparser*.jar >/dev/null 2>&1; then
    for jar in lib/jsqlparser*.jar; do
        JSQLPARSER_CP="$JSQLPARSER_CP;$jar"
    done
    echo "  JSqlParser: 已检测到，编译 SQL 编辑器模块"
else
    echo "  WARN: lib/jsqlparser*.jar 未找到，SQL 窗口美化功能不可用"
fi

# RichTextFX（SQL 编辑器语法高亮 CodeArea）及其传递依赖
RICHTEXT_CP=""
for jar in lib/richtextfx*.jar lib/reactfx*.jar lib/flowless*.jar lib/undofx*.jar lib/wellbehavedfx*.jar; do
    [ -e "$jar" ] || continue
    RICHTEXT_CP="$RICHTEXT_CP;$jar"
done
if [ -n "$RICHTEXT_CP" ]; then
    echo "  RichTextFX: 已检测到，编译语法高亮模块"
else
    echo "  WARN: lib/richtextfx*.jar 未找到，SQL 语法高亮功能不可用"
fi

javac -d build-out \
    -cp "drivers/ojdbc17-23.26.1.0.0.jar;drivers/postgresql-42.7.10.jar$JAVAFX_CP$JSQLPARSER_CP$RICHTEXT_CP" \
    src/com/datacube/DataCube.java \
    src/com/datacube/cli/*.java \
    src/com/datacube/core/*.java \
    src/com/datacube/spi/*.java \
    src/com/datacube/spi/model/*.java \
    src/com/datacube/config/*.java \
    src/com/datacube/service/*.java \
    src/com/datacube/provider/postgres/*.java \
    src/com/datacube/provider/oracle/*.java \
    src/com/datacube/migration/*.java \
    src/com/datacube/sqleditor/*.java \
    src/com/datacube/export/*.java \
    $JAVAFX_FILES

# 版本资源：fat-jar（本地开发）构建写入 dev 占位版本；
# 运行时 AppVersion 读到 0.0.0-dev 会跳过启动自检（仅手动检查可用于联调）。
mkdir -p build-out/com/datacube
echo "version=0.0.0-dev" > build-out/com/datacube/version.properties

# ===== [2/4] 解压依赖到 staging =====
echo "===== [2/4] 解压依赖到 staging ====="
STAGE=build-staging
rm -rf "$STAGE" MANIFEST.MF
mkdir -p "$STAGE"

# 编译产物
cp -r build-out/* "$STAGE/"

# JDBC 驱动类
for jar in drivers/*.jar; do
    echo "  解压驱动: $jar"
    (cd "$STAGE" && jar xf "../$jar")
    # 删除冲突的 MANIFEST.MF，保留 services 等其他 META-INF 文件
    rm -f "$STAGE/META-INF/MANIFEST.MF"
done

# JavaFX 类
if ls lib/javafx*.jar >/dev/null 2>&1; then
    for jar in lib/javafx*.jar; do
        echo "  解压 JavaFX: $jar"
        (cd "$STAGE" && jar xf "../$jar")
        rm -f "$STAGE/META-INF/MANIFEST.MF"
    done
fi

# JavaFX native dll（嵌入到 lib/javafx/，运行时由 DataCube 自动解压到 java.library.path）
if [ -d "lib/native/win" ]; then
    mkdir -p "$STAGE/lib/javafx"
    cp lib/native/win/*.dll "$STAGE/lib/javafx/"
    echo "  嵌入 native dll: $(ls lib/native/win/*.dll | wc -l) 个"
else
    echo "  WARN: lib/native/win 不存在，GUI 可能无法初始化（d3d/sw pipeline 缺失）"
fi

# JSqlParser 类（SQL 美化）
if ls lib/jsqlparser*.jar >/dev/null 2>&1; then
    for jar in lib/jsqlparser*.jar; do
        echo "  解压 JSqlParser: $jar"
        (cd "$STAGE" && jar xf "../$jar")
        rm -f "$STAGE/META-INF/MANIFEST.MF"
    done
fi

# RichTextFX 类（SQL 语法高亮）及其传递依赖
for jar in lib/richtextfx*.jar lib/reactfx*.jar lib/flowless*.jar lib/undofx*.jar lib/wellbehavedfx*.jar; do
    [ -e "$jar" ] || continue
    echo "  解压 RichTextFX: $jar"
    (cd "$STAGE" && jar xf "../$jar")
    rm -f "$STAGE/META-INF/MANIFEST.MF"
done

# 非代码资源（SQL 高亮样式表 sql-highlight.css 等）
if [ -d resources ]; then
    echo "  复制 resources 资源"
    cp -r resources/* "$STAGE/"
fi

# ===== [3/4] 打包 fat-jar =====
echo "===== [3/4] 打包 fat-jar ====="
cat > MANIFEST.MF <<EOF
Manifest-Version: 1.0
Multi-Release: true
Main-Class: com.datacube.DataCube
EOF

# 用 staging.jar 作为中间产物（避免目标 jar 被占用）
jar cfm staging.jar MANIFEST.MF -C "$STAGE" .

# ===== [4/4] 完成 + 清理 =====
echo "===== [4/4] 完成 ====="
if [ -f DataCube.jar ]; then
    mv -f staging.jar DataCube.jar 2>/dev/null || mv -f staging.jar DataCube_new.jar
else
    mv -f staging.jar DataCube.jar 2>/dev/null || mv -f staging.jar DataCube_new.jar
fi

rm -rf "$STAGE" MANIFEST.MF build-out

ls -lh DataCube*.jar 2>/dev/null
echo ""
echo "运行 GUI:  ./run-gui.bat  或  ./run-gui.sh"
echo "运行 CLI:  ./run.bat --help  或  ./run.sh --help"