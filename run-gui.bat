@echo off
rem JavaFX GUI launcher
rem Auto-detect fat-jar (embedded JavaFX) vs thin-jar (external JavaFX modules)
rem Auto-add JDK 17+ required --enable-native-access=ALL-UNNAMED

setlocal enabledelayedexpansion

rem Ensure working directory is the script directory (safe for double-click / run-as-admin)
cd /d "%~dp0"

rem Detect java
if exist "jre" (
    set "JAVA=.\jre\bin\java.exe"
) else (
    set "JAVA=java"
)

rem Common opts: JDK 17+ needs native access unlocked
set "JDK17_OPTS=--enable-native-access=ALL-UNNAMED"

rem Auto-append native lib path
set "NATIVE_OPTS="
if exist "lib\native\win" (
    set "NATIVE_OPTS=-Djava.library.path=lib\native\win"
)

rem Detect fat-jar (contains javafx.application.Application class)
set "FAT=0"
if exist "DataCube.jar" (
    jar tf DataCube.jar 2>nul | findstr /R "javafx[/\\]application[/\\]Application\.class" >nul 2>&1
    if not errorlevel 1 set "FAT=1"
)

if "%FAT%"=="1" (
    rem Fat-jar with embedded JavaFX
    if defined NATIVE_OPTS (
        "%JAVA%" %JDK17_OPTS% !NATIVE_OPTS! -jar DataCube.jar --gui
    ) else (
        "%JAVA%" %JDK17_OPTS% -jar DataCube.jar --gui
    )
) else (
    rem Thin-jar: needs external lib\javafx*.jar
    if exist "lib\javafx*.jar" (
        set "JAVAFX_OPTS=--module-path lib --add-modules javafx.controls,javafx.fxml"
        if defined NATIVE_OPTS (
            "%JAVA%" %JDK17_OPTS% !JAVAFX_OPTS! !NATIVE_OPTS! -jar DataCube.jar --gui
        ) else (
            "%JAVA%" %JDK17_OPTS% !JAVAFX_OPTS! -jar DataCube.jar --gui
        )
    ) else (
        echo [ERR] JavaFX SDK jar or native dll not found. Put one of the following into lib\:
        echo   - Option A ^(recommended^): download openjfx SDK, unzip, then run build.sh to repackage.
        echo   - Option B ^(minimal^): download javafx-*.jar and Windows native dll into lib\.
        echo Download: https://gluonhq.com/products/javafx/
        pause
        exit /b 1
    )
)

rem If launch failed (java not found, jar missing, class load error, etc.) keep window open
if errorlevel 1 (
    echo.
    echo [ERR] Non-zero exit code. If the window should have appeared but did not, check the error above.
    pause
)

endlocal
