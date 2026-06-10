#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TEAVM_DIR="${ROOT_DIR}/third_party/teavm"
WEB_POM="${ROOT_DIR}/org.eclipse.jdt.ls.web/pom.xml"
PACKAGE_DIR="${ROOT_DIR}/org.eclipse.jdt.ls.web/target/generated"
PACK_DEST="${ROOT_DIR}/org.eclipse.jdt.ls.web/target"

TEAVM_TASKS=(
  :core:publishToMavenLocal
  :classlib:publishToMavenLocal
  :jso:core:publishToMavenLocal
  :jso:apis:publishToMavenLocal
  :tools:maven:plugin:publishToMavenLocal
  :platform:publishToMavenLocal
  :jso:impl:publishToMavenLocal
  :metaprogramming:impl:publishToMavenLocal
  :extension:spi:publishToMavenLocal
  :interop:core:publishToMavenLocal
  :metaprogramming:api:publishToMavenLocal
  :extension:apis:publishToMavenLocal
  :extension:spi-util:publishToMavenLocal
  :relocated:libs:asm:publishToMavenLocal
  :relocated:libs:asm-tree:publishToMavenLocal
  :relocated:libs:asm-analysis:publishToMavenLocal
  :relocated:libs:asm-commons:publishToMavenLocal
  :relocated:libs:asm-util:publishToMavenLocal
  :relocated:libs:hppc:publishToMavenLocal
  :relocated:libs:rhino:publishToMavenLocal
  :tools:core:publishToMavenLocal
  :tools:deobfuscator-wasm-gc:publishToMavenLocal
)

if [[ "${SKIP_TEAVM_PUBLISH:-}" != "1" ]]; then
  (cd "${TEAVM_DIR}" && ./gradlew "${TEAVM_TASKS[@]}")
fi

"${ROOT_DIR}/mvnw" -f "${WEB_POM}" process-classes
mkdir -p "${PACK_DEST}"
npm pack "${PACKAGE_DIR}" --pack-destination "${PACK_DEST}"
