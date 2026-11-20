#!/bin/bash
set -euo pipefail

project=$(cd -- "$(dirname -- "$0")/../.." && pwd -P)
if [[ $# -lt 1 || $# -gt 3 ]]; then
    printf 'Usage: %s OCR_ROOT [DESTINATION] [dmg|app-image]\n' "$0" >&2
    exit 2
fi
if [[ "$(uname -s)" != Darwin || "$(uname -m)" != arm64 ]]; then
    printf '%s\n' 'Build on a macOS arm64 target with a native JDK 21.' >&2
    exit 2
fi
ocr_root=$(cd -- "$1" && pwd -P)
destination=${2:-"$project/release/macos-arm64"}
package_type=${3:-dmg}
case "$package_type" in dmg|app-image) ;; *) exit 2 ;; esac
icon="$project/packaging/icons/expense-assistant.icns"
if [[ ! -f "$icon" || ! -r "$icon" ]]; then
    printf 'Application icon is missing or unreadable: %s\n' "$icon" >&2
    exit 2
fi
mkdir -p -- "$destination" "$project/target"
destination=$(cd -- "$destination" && pwd -P)

java_home=${JAVA_HOME:-$(/usr/libexec/java_home -v 21)}
package_tool="$java_home/bin/jpackage"
if [[ ! "$("$package_tool" --version)" =~ ^21(\.|$) ]]; then
    printf '%s\n' 'JDK 21 jpackage is required.' >&2
    exit 2
fi
export JAVA_HOME="$java_home"

scratch=$(mktemp -d "$project/target/package-macos.XXXXXX")
trap 'rm -rf -- "$scratch"' EXIT
input="$scratch/input"
mkdir -p -- "$input"
cd -- "$project"
maven_flags=(-B -ntp)
if [[ "${EXPENSEASSISTANT_MAVEN_OFFLINE:-0}" == 1 ]]; then
    maven_flags+=(-o)
fi
version=$(xmllint --xpath 'string(/*[local-name()="project"]/*[local-name()="version"])' pom.xml)
native_version=$("$java_home/bin/java" packaging/ReleaseVersion.java packaging/release-versions.properties "$version")
resources="$scratch/resources"
mkdir -- "$resources"
"$java_home/bin/jmod" extract --dir "$scratch/jpackage" "$java_home/jmods/jdk.jpackage.jmod"
plist_template="$scratch/jpackage/classes/jdk/jpackage/internal/resources/Info-lite.plist.template"
if ! awk -v native="$native_version" '
    {
        internal += gsub(/DEPLOY_BUNDLE_CFBUNDLE_VERSION/, native)
        display += gsub(/DEPLOY_BUNDLE_SHORT_VERSION/, "DEPLOY_BUNDLE_SHORT_VERSION")
        print
    }
    END { if (internal != 1 || display != 1) exit 1 }
' "$plist_template" > "$resources/Info.plist"; then
    printf '%s\n' 'Unexpected version placeholders in the JDK 21 Info.plist template.' >&2
    exit 2
fi
jar_name="expense-assistant-$version.jar"
mvn "${maven_flags[@]}" verify org.apache.maven.plugins:maven-dependency-plugin:3.8.1:copy-dependencies \
    -DincludeScope=runtime "-DoutputDirectory=$input"
cp -- "target/$jar_name" "$input/$jar_name"
"$java_home/bin/java" --class-path "$input/*" packaging/StageRelease.java \
    packaging/components.json macos-arm64 "$input" "$ocr_root" "$jar_name"

package_args=(--type "$package_type" --input "$input" --dest "$destination" \
    --name ExpenseAssistant --app-version "$version" --main-jar "$jar_name" \
    --main-class expenseassistant.DesktopLauncher --mac-package-identifier com.expenseassistant.desktop \
    --add-modules java.se,jdk.unsupported,jdk.crypto.ec,jdk.charsets,jdk.localedata,jdk.zipfs \
    --resource-dir "$resources" --icon "$icon")
if [[ "$package_type" == dmg ]]; then
    # JDK 21's default layout script waits for permission to automate Finder.
    printf '%s\n' 'return' > "$resources/ExpenseAssistant-dmg-setup.scpt"
fi
"$package_tool" "${package_args[@]}"
