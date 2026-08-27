@rem BitOS Gradle wrapper launcher
@echo off
setlocal
set APP_HOME=%~dp0
if not exist "%APP_HOME%gradle\wrapper\gradle-wrapper.jar" (
  echo Missing gradle\wrapper\gradle-wrapper.jar. See docs\engineering\toolchains.md. 1>&2
  exit /b 1
)
java -Xmx64m -Xms64m -classpath "%APP_HOME%gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain %*
endlocal
