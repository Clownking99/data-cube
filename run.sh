#!/bin/bash
# 自动检测：优先使用内置 jre，否则使用系统 java
if [ -d "jre" ]; then
    JAVA="./jre/bin/java"
else
    JAVA="java"
fi
$JAVA -jar OracleToPgComplete.jar
