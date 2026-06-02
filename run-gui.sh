#!/bin/bash
# JavaFX GUI 模式启动脚本
# 需要将 JavaFX SDK 的 jar 文件放入 lib/ 目录

JAVAFX_OPTS="--module-path lib --add-modules javafx.controls,javafx.fxml"

if [ -d "jre" ]; then
    ./jre/bin/java $JAVAFX_OPTS -jar DataCube.jar --gui
else
    java $JAVAFX_OPTS -jar DataCube.jar --gui
fi
