@ECHO OFF
SETLOCAL
SET DIR=%~dp0
SET JAVA_EXE=java
IF DEFINED JAVA_HOME SET JAVA_EXE=%JAVA_HOME%\bin\java.exe
IF NOT DEFINED JAVA_HOME IF EXIST "%ProgramFiles%\Android\Android Studio\jbr\bin\java.exe" SET JAVA_EXE=%ProgramFiles%\Android\Android Studio\jbr\bin\java.exe
"%JAVA_EXE%" -classpath "%DIR%gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain %*
EXIT /B %ERRORLEVEL%
