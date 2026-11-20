#!/bin/bash
set -euo pipefail

cd -- "$(dirname -- "$0")"
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"

if command -v mvn >/dev/null 2>&1; then
    maven="$(command -v mvn)"
else
    maven="$HOME/.local/share/bidtravel-tools/apache-maven-3.9.16/bin/mvn"
fi
if [[ ! -x "$maven" ]]; then
    printf '%s\n' '找不到 Maven，请确认本机的 Java 开发工具路径。' >&2
    exit 1
fi

ocr_root="${EXPENSEASSISTANT_OCR_ROOT:-/Users/tools/runtimes/tesseract-5.5.1-darwin-arm64}"
if [[ -x "$ocr_root/bin/tesseract" ]]; then
    export JDK_JAVA_OPTIONS="${JDK_JAVA_OPTIONS:+$JDK_JAVA_OPTIONS }\"-Dexpenseassistant.ocrRoot=$ocr_root\""
fi

exec "$maven" -o -B -ntp javafx:run
