#!/bin/sh

set -eu

APP_HOME=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
WRAPPER_JAR="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"

if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    bitos_java="$JAVA_HOME/bin/java"
elif command -v java >/dev/null 2>&1 && java -version >/dev/null 2>&1; then
    bitos_java="$(command -v java)"
elif [ -x "/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin/java" ]; then
    bitos_java="/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin/java"
else
    echo "A JDK 17 or newer is required. Run: make doctor" >&2
    exit 1
fi
if [ ! -f "$WRAPPER_JAR" ]; then
    echo "Missing $WRAPPER_JAR. Run the repository bootstrap documented in docs/engineering/toolchains.md." >&2
    exit 1
fi

exec "$bitos_java" -Xmx64m -Xms64m -classpath "$WRAPPER_JAR" org.gradle.wrapper.GradleWrapperMain "$@"
