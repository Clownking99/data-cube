@echo off
rem JavaFX GUI 模式启动脚本
rem 需要将 JavaFX SDK 的 jar 文件放入 lib\ 目录

set JAVAFX_OPTS=--module-path lib --add-modules javafx.controls,javafx.fxml

if exist "jre" (
    .\jre\bin\java.exe %JAVAFX_OPTS% -jar DataCube.jar --gui
) else (
    java %JAVAFX_OPTS% -jar DataCube.jar --gui
)
