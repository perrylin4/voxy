#!/usr/bin/env bash
set -euo pipefail

edition="${1:-}"
repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

case "$edition" in
  neoforge-1.21.1)
    project="$repo"; java_version=21 ;;
  client-1.20.1)
    project="$repo/editions/forge-1.20.1-client"; java_version=17 ;;
  client-26.1.2)
    project="$repo/editions/neoforge-26.1.2-client"; java_version=25 ;;
  *)
    echo 'Usage: scripts/build.sh {neoforge-1.21.1|client-1.20.1|client-26.1.2}' >&2
    exit 2 ;;
esac

java_home_var="JAVA_HOME_${java_version}"
java_home="${!java_home_var:-}"
if [[ -n "$java_home" ]]; then
  export JAVA_HOME="$java_home"
  export PATH="$JAVA_HOME/bin:$PATH"
fi
actual_java="$(java -version 2>&1 | head -n 1)"
if [[ ! "$actual_java" =~ \"${java_version}([.\"]|$) ]]; then
  echo "JDK $java_version is required for $edition; set $java_home_var." >&2
  exit 2
fi

if [[ -z "${GRADLE_USER_HOME:-}" && -d "$(dirname "$repo")/.gradle-user-home" ]]; then
  export GRADLE_USER_HOME="$(dirname "$repo")/.gradle-user-home"
fi

chmod +x "$project/gradlew"
gradle_launcher="$project/gradlew"
if [[ -n "${GRADLE_USER_HOME:-}" ]]; then
  distribution_url="$(sed -n 's/^distributionUrl=//p' "$project/gradle/wrapper/gradle-wrapper.properties")"
  distribution_name="$(basename "${distribution_url%.zip}")"
  cached_launcher="$(find "$GRADLE_USER_HOME/wrapper/dists/$distribution_name" -type f -path '*/bin/gradle' -print -quit 2>/dev/null || true)"
  if [[ -n "$cached_launcher" ]]; then
    gradle_launcher="$cached_launcher"
  fi
fi

init_scripts=(-I init.gradle)
if [[ "${CI:-false}" == "true" ]]; then
  init_scripts+=(-I "$repo/scripts/ci-javac.gradle")
fi
(cd "$project" && "$gradle_launcher" "${init_scripts[@]}" clean build --no-daemon --no-configuration-cache)

mapfile -t release_jars < <(
  find "$project/build/libs" -maxdepth 1 -type f \
    -name 'neo-voxy-*.jar' \
    ! -name '*-sources.jar' \
    ! -name '*-dev.jar' \
    ! -name '*-unoptimized.jar' \
    -print | sort
)
if (( ${#release_jars[@]} != 1 )); then
  printf 'Expected one release JAR for %s, found %d:\n' "$edition" "${#release_jars[@]}" >&2
  find "$project/build/libs" -maxdepth 1 -type f -name '*.jar' -print >&2
  exit 1
fi
source_jar="${release_jars[0]}"
jar="$(basename "$source_jar")"
mkdir -p "$repo/dist"
cp "$source_jar" "$repo/dist/$jar"
ls -lh "$repo/dist/$jar"
