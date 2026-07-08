@echo off
rem JavaFX GUI 模式启动脚本
rem 自动检测 fat-jar（内嵌 JavaFX 类）vs 瘦 jar（外部 JavaFX 模块）
rem 自动添加 JDK 17+ 所需的 --enable-native-access=ALL-UNNAMED

setlocal

rem 探测 java
if exist "jre" (
    set "JAVA=.\jre\bin\java.exe"
) else (
    set "JAVA=java"
)

rem ===== 公共参数：JDK 17+ 必须解锁 native access =====
set "JDK17_OPTS=--enable-native-access=ALL-UNNAMED"

rem ===== 自动追加 native lib 路径 =====
set "NATIVE_OPTS="
if exist "lib\native\win" (
    set "NATIVE_OPTS=-Djava.library.path=lib\native\win"
    if exist "lib\native\win\prism_d3d.dll" set "NATIVE_OPTS=-Djava.library.path=lib\native\win"
)

rem 探测 fat-jar（含 javafx.application.Application 类）
set "FAT=0"
if exist "DataCube.jar" (
    jar tf DataCube.jar 2>nul | findstr /R "javafx[/\\]application[/\\]Application\.class" >nul 2>&1
    if not errorlevel 1 set "FAT=1"
)

if "%FAT%"=="1" (
    rem 内嵌 JavaFX 的 fat-jar
    if defined NATIVE_OPTS (
        "%JAVA%" %JDK17_OPTS% %NATIVE_OPTS% -jar DataCube.jar --gui
    ) else (
        "%JAVA%" %JDK17_OPTS% -jar DataCube.jar --gui
    )
) else (
    rem 瘦 jar：需要外部 lib\javafx*.jar
    if exist "lib\javafx*.jar" (
        set "JAVAFX_OPTS=--module-path lib --add-modules javafx.controls,javafx.fxml"
        if defined NATIVE_OPTS (
            "%JAVA%" %JDK17_OPTS% %JAVAFX_OPTS% %NATIVE_OPTS% -jar DataCube.jar --gui
        ) else (
            "%JAVA%" %JDK17_OPTS% %JAVAFX_OPTS% -jar DataCube.jar --gui
        )
    ) else (
        echo [ERR] 未检测到 JavaFX SDK jar 或 native dll。请将以下任一放入 lib\ 目录：
        echo   - 方式 A（推荐）：下载 openjfx-21.0.7 SDK 并解压到 lib\javafx-sdk\，然后执行 build.sh 重新打包。
        echo   - 方式 B（最小）：下载 javafx-*.jar 和 Windows native dll 到 lib\。
        echo 下载地址: https://gluonhq.com/products/javafx/
        exit /b 1
    )
)

endlocal
