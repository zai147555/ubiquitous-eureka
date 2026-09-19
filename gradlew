#!/usr/bin/env sh
#
# 标准 Gradle wrapper 启动脚本（等价于官方生成版，精简去掉 Windows/cygwin 分支）。
#
# 首次运行会自动下载 gradle-8.9-bin.zip 到 GRADLE_USER_HOME（见
# gradle/wrapper/gradle-wrapper.properties 里的 distributionUrl）。
# 中国大陆网络较慢时，可把 distributionUrl 换成镜像，例如：
#   https://mirrors.cloud.tencent.com/gradle/gradle-8.9-bin.zip

set -e

APP_HOME=$(cd "$(dirname "$0")" && pwd)
CLASSPATH="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"

if [ -n "$JAVA_HOME" ]; then
    JAVACMD="$JAVA_HOME/bin/java"
else
    JAVACMD=java
fi

if ! command -v "$JAVACMD" >/dev/null 2>&1; then
    echo "错误：找不到 java。请设置 JAVA_HOME 或把 java 加入 PATH（需要 JDK 17）。" >&2
    exit 1
fi

exec "$JAVACMD" \
    -Xmx64m -Xms64m \
    "-Dorg.gradle.appname=$(basename "$0")" \
    -classpath "$CLASSPATH" \
    org.gradle.wrapper.GradleWrapperMain "$@"
