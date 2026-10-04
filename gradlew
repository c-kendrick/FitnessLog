#!/bin/sh
APP_HOME=$(cd "${0%/*}" >/dev/null 2>&1 && pwd -P)
CLASSPATH=$APP_HOME/gradle/wrapper/gradle-wrapper.jar
JAVA_EXE=java
if [ -n "$JAVA_HOME" ]; then JAVA_EXE="$JAVA_HOME/bin/java"; fi
exec "$JAVA_EXE" -classpath "$CLASSPATH" org.gradle.wrapper.GradleWrapperMain "$@"
