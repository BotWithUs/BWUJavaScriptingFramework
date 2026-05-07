@echo off
REM ============================================================================
REM Regenerate jextract bindings for NXTCache.dll from the C header.
REM
REM Run this whenever nxtcache_c.h changes meaningfully (new function, changed
REM signature). The hand-rolled bindings in core/.../cache/NXTCache.java are
REM the production path; this script produces a verbose drop-in alternative
REM you can diff against to spot drift.
REM
REM Prerequisites
REM -------------
REM  1) jextract — download from https://jdk.java.net/jextract/ and put it on
REM     PATH (or set JEXTRACT_HOME below).
REM  2) The NXTCache project headers under E:\BotWithUsv2.5\NXTCacheLibrary\src.
REM
REM Output
REM ------
REM  Generated Java sources are written to
REM     core/build/generated/jextract/com/botwithus/bot/core/cache/jextract/
REM  This directory is NOT on the Gradle source path by default. To use the
REM  generated bindings, either copy the .java files into the source tree or
REM  add the directory as an additional sourceSet root in core/build.gradle.kts.
REM ============================================================================

setlocal

if "%JEXTRACT_HOME%"=="" (
    set JEXTRACT=jextract
) else (
    set JEXTRACT=%JEXTRACT_HOME%\bin\jextract
)

set HEADER=E:\BotWithUsv2.5\NXTCacheLibrary\src\c_api\nxtcache_c.h
set OUT_DIR=%~dp0..\core\build\generated\jextract
set TARGET_PKG=com.botwithus.bot.core.cache.jextract

if not exist "%HEADER%" (
    echo ERROR: header not found at %HEADER%
    exit /b 1
)

if not exist "%OUT_DIR%" mkdir "%OUT_DIR%"

echo Regenerating jextract bindings...
echo   header   : %HEADER%
echo   output   : %OUT_DIR%
echo   package  : %TARGET_PKG%
echo.

"%JEXTRACT%" ^
    --output "%OUT_DIR%" ^
    --target-package %TARGET_PKG% ^
    --header-class-name NxtCacheNative ^
    --library NXTCache ^
    "%HEADER%"

if errorlevel 1 (
    echo.
    echo jextract failed.
    exit /b 1
)

echo.
echo Done. Review the generated sources in %OUT_DIR% and update
echo NXTCache.java if any signature changed.

endlocal
