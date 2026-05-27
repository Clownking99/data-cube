#!/bin/bash
echo "编译..."
javac -cp "drivers/ojdbc17-23.26.1.0.0.jar;drivers/postgresql-42.7.10.jar" OracleToPgComplete.java
if [ $? -ne 0 ]; then
    echo "编译失败"
    exit 1
fi

echo "打包..."
echo "Manifest-Version: 1.0
Main-Class: OracleToPgComplete
Class-Path: drivers/ojdbc17-23.26.1.0.0.jar drivers/postgresql-42.7.10.jar" > MANIFEST.MF

# 先打包到临时文件，避免 JAR 被占用时失败
jar cfm OracleToPgComplete_new.jar MANIFEST.MF OracleToPgComplete.class OracleToPgComplete\$ColumnInfo.class
if [ $? -ne 0 ]; then
    echo "打包失败"
    rm -f MANIFEST.MF
    exit 1
fi

rm -f OracleToPgComplete.class OracleToPgComplete\$ColumnInfo.class MANIFEST.MF

# 替换旧 JAR（如果未被占用）
if [ -f OracleToPgComplete.jar ]; then
    mv -f OracleToPgComplete_new.jar OracleToPgComplete.jar 2>/dev/null
    if [ $? -ne 0 ]; then
        echo "旧 JAR 被占用，新文件: OracleToPgComplete_new.jar"
        echo "请关闭正在运行的程序后手动替换"
        exit 0
    fi
fi

echo "完成: OracleToPgComplete.jar"
