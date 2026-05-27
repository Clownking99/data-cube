#!/bin/bash
echo "编译..."
javac -sourcepath src -cp "drivers/ojdbc17-23.26.1.0.0.jar;drivers/postgresql-42.7.10.jar" \
    src/com/datacube/*.java
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
