@echo off
if exist "jre" (
    .\jre\bin\java.exe -jar OracleToPgComplete.jar
) else (
    java -jar OracleToPgComplete.jar
)
