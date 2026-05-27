@echo off
if exist "jre" (
    .\jre\bin\java.exe -jar DataCube.jar
) else (
    java -jar DataCube.jar
)
