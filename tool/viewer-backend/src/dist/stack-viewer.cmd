@echo off
rem Stack viewer launcher (Windows cmd). Behaves like the `stack-viewer` sh launcher next to it.
rem Usage: stack-viewer --workspace=<path> [--recordings=<path>] [--server.port=<n>]   (default port 8090, on 127.0.0.1)
rem Needs Java 25+: from %STACK_VIEWER_JAVA_HOME%\bin\java.exe when that is set (a Java for the viewer only, leaving
rem JAVA_HOME and PATH to the machine's default Java), else from %JAVA_HOME%\bin\java.exe when that is set, otherwise
rem `java` on PATH.

setlocal DisableDelayedExpansion

set MIN_JAVA=25

rem The jar sits next to this script; %~dp0 is its absolute directory (with a trailing backslash).
set "jar=%~dp0stack-viewer.jar"

set java_home_var=
if defined JAVA_HOME (
    set java_home_var=JAVA_HOME
    set "java_home=%JAVA_HOME:"=%"
)
if defined STACK_VIEWER_JAVA_HOME (
    set java_home_var=STACK_VIEWER_JAVA_HOME
    set "java_home=%STACK_VIEWER_JAVA_HOME:"=%"
)
if defined java_home_var goto java_from_home

set java_cmd=java
where java >nul 2>&1
if errorlevel 1 (
    echo stack-viewer: Java %MIN_JAVA% or newer is required, but 'java' was not found on PATH. Install a JDK %MIN_JAVA%+ and set STACK_VIEWER_JAVA_HOME or JAVA_HOME. 1>&2
    exit /b 1
)
goto check_version

:java_from_home
set "java_cmd=%java_home%\bin\java.exe"
rem Messages that print a path stay out of ( ) blocks: a ")" in it, as in "Program Files (x86)", would end the block.
if exist "%java_cmd%" goto check_version
echo stack-viewer: Java %MIN_JAVA% or newer is required, but %java_home_var% (%java_home%) has no bin\java.exe. 1>&2
exit /b 1

:check_version
rem The version line of `java -version` is e.g.: openjdk version "25.0.1" 2025-10-21  /  java version "1.8.0_392"
rem The JVM may print "Picked up JAVA_TOOL_OPTIONS: ..." first, so pick the first line whose second word is `version`.
set version_line=
set version=
for /f "usebackq tokens=1,2,3*" %%a in (`call "%java_cmd%" -version 2^>^&1`) do (
    if not defined version if "%%b"=="version" (
        set "version_line=%%a %%b %%c %%d"
        set "version=%%~c"
    )
)
set major=
if defined version for /f "delims=._-" %%m in ("%version%") do set "major=%%m"
set major_ok=
if defined major echo %major%| findstr /r /x "[0-9][0-9]*" >nul && set major_ok=1
if defined major_ok if %major% GEQ %MIN_JAVA% goto check_jar
if not defined version_line set "version_line=unknown"
echo stack-viewer: Java %MIN_JAVA% or newer is required, but found: %version_line% 1>&2
exit /b 1

:check_jar
if exist "%jar%" goto launch
echo stack-viewer: %jar% not found. 1>&2
exit /b 1

:launch
"%java_cmd%" -jar "%jar%" %*
exit /b %ERRORLEVEL%
