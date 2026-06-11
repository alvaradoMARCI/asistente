@rem NubiaAgent Gradle Wrapper Bootstrap Script
@rem This script downloads and executes Gradle 8.5

@if "%DEBUG%"=="" @echo off
set DEFAULT_JVM_OPTS="-Xmx2048m"
set JAVA_EXE=java.exe
set CLASSPATH=%~dp0gradle\wrapper\gradle-wrapper.jar

"%JAVA_EXE%" %DEFAULT_JVM_OPTS% %JAVA_OPTS% -classpath "%CLASSPATH%" org.gradle.wrapper.GradleWrapperMain %*
