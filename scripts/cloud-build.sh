#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
export ANDROID_HOME=/workspace/tooling/android
export ANDROID_USER_HOME=/workspace/tooling/android-user
export GRADLE_USER_HOME=/workspace/tooling/gradle-home
export JAVA_HOME=/workspace/tooling/jdk-21.0.8+9
export PATH="$JAVA_HOME/bin:$PATH"
args=()
if [ -n "${HTTPS_PROXY:-}" ]; then
  proxy_host=$(python -c 'import os,urllib.parse;print(urllib.parse.urlsplit(os.environ["HTTPS_PROXY"]).hostname)')
  proxy_port=$(python -c 'import os,urllib.parse;print(urllib.parse.urlsplit(os.environ["HTTPS_PROXY"]).port or 80)')
  args+=("-Dhttps.proxyHost=$proxy_host" "-Dhttps.proxyPort=$proxy_port" "-Dhttp.proxyHost=$proxy_host" "-Dhttp.proxyPort=$proxy_port")
fi
bash scripts/fetch-model.sh
./gradlew "${args[@]}" -Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts assembleDebug testDebugUnitTest lintDebug --no-daemon --max-workers=2
./gradlew "${args[@]}" -Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts assembleDemo --no-daemon --max-workers=2
