@echo off
rem Gradle Wrapper startup script for Windows

set DIRNAME=%~dp0
if "%DIRNAME%"=="" set DIRNAME=.
set APP_HOME=%DIRNAME%
set CLASSPATH=%APP_HOME%\gradle\wrapper\gradle-wrapper.jar

if defined JAVA_HOME goto findJava
set JAVA_EXE=java.exe
%JAVA_EXE% -version >NUL 2>&1
if %ERRORLEVEL% equ 0 goto execute
echo ERROR: JAVA_HOME not set and java not found in PATH.
exit /b 1

:findJava
set JAVA_HOME=%JAVA_HOME:"=%
set JAVA_EXE=%JAVA_HOME%/bin/java.exe
if exist "%JAVA_EXE%" goto execute
echo ERROR: JAVA_HOME points to invalid directory: %JAVA_HOME%
exit /b 1

:execute
"%JAVA_EXE%" -Xmx64m -Xms64m ^
    -Dorg.gradle.appname=%~n0 ^
    -classpath "%CLASSPATH%" ^
    org.gradle.wrapper.GradleWrapperMain %*
exit /b %ERRORLEVEL%
