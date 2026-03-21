#!/bin/bash
#
# release.sh — Build the release APK, package the installer zip, and print version info.
#
# Usage:
#   ./release.sh              # Build and package
#   ./release.sh --info       # Just print current version info
#

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
BUILD_GRADLE="$ROOT_DIR/app/build.gradle.kts"
RELEASE_DIR="$ROOT_DIR/release/Hyperborea"
APPS_DIR="$RELEASE_DIR/apps"
OUTPUT_DIR="$ROOT_DIR/release"

GREEN='\033[0;32m'; RED='\033[0;31m'; CYAN='\033[0;36m'; BOLD='\033[1m'; NC='\033[0m'
ok()   { echo -e "  ${GREEN}✓${NC} $*"; }
fail() { echo -e "  ${RED}✗${NC} $*"; exit 1; }
info() { echo -e "  ${CYAN}→${NC} $*"; }

# Extract version info from build.gradle.kts
VERSION_CODE=$(grep -oP 'versionCode\s*=\s*\K\d+' "$BUILD_GRADLE")
VERSION_NAME=$(grep -oP 'versionName\s*=\s*"\K[^"]+' "$BUILD_GRADLE")

if [[ "${1:-}" == "--info" ]]; then
    echo ""
    echo -e "${BOLD}Hyperborea v${VERSION_NAME} (code ${VERSION_CODE})${NC}"
    echo ""
    exit 0
fi

echo ""
echo -e "${BOLD}Building Hyperborea v${VERSION_NAME} (code ${VERSION_CODE})${NC}"
echo ""

# Step 0: Validate configuration
LOCAL_PROPS="$ROOT_DIR/local.properties"
for key in server.url license.public.key r2.base.url release.keystore.password release.key.password; do
    val=$(grep "^${key}=" "$LOCAL_PROPS" 2>/dev/null | cut -d= -f2-)
    if [[ -z "$val" ]]; then
        fail "Required key '$key' not set in local.properties"
    fi
done
ok "Configuration validated"

# Step 0b: Run lint and tests
info "Running lint..."
cd "$ROOT_DIR"
./gradlew clean lint --quiet 2>&1 | tail -5
ok "Lint passed"

info "Running tests..."
./gradlew test --quiet 2>&1 | tail -5
ok "Tests passed"

# Step 1: Build standard release APK
info "Building standardRelease APK..."
cd "$ROOT_DIR"
./gradlew :app:assembleStandardRelease --quiet 2>&1 | tail -5
APK_PATH="$ROOT_DIR/app/build/outputs/apk/standard/release/app-standard-release.apk"
if [[ ! -f "$APK_PATH" ]]; then
    fail "APK not found at $APK_PATH"
fi
APK_SIZE=$(du -h "$APK_PATH" | cut -f1)
ok "Built APK ($APK_SIZE)"

# Step 2: Copy APK to release folder
mkdir -p "$APPS_DIR"
cp "$APK_PATH" "$APPS_DIR/Hyperborea.apk"
ok "Copied to release/Hyperborea/apps/Hyperborea.apk"

# Step 3: Create zip bundle
ZIP_NAME="Hyperborea-v${VERSION_NAME}.zip"
ZIP_PATH="$OUTPUT_DIR/$ZIP_NAME"
rm -f "$ZIP_PATH"
cd "$OUTPUT_DIR"
zip -r "$ZIP_NAME" Hyperborea/ -x "Hyperborea/.DS_Store" "Hyperborea/**/.DS_Store" --quiet
ZIP_SIZE=$(du -h "$ZIP_PATH" | cut -f1)
ok "Created $ZIP_NAME ($ZIP_SIZE)"

# Summary
echo ""
echo -e "${BOLD}Release artifacts ready${NC}"
echo ""
echo -e "  Version Code:  ${BOLD}${VERSION_CODE}${NC}"
echo -e "  Version Name:  ${BOLD}${VERSION_NAME}${NC}"
echo -e "  APK:           ${CYAN}${APK_PATH}${NC}"
echo -e "  ZIP:           ${CYAN}${ZIP_PATH}${NC}"
echo ""
echo -e "  Upload form values:"
echo -e "    Version Code:  ${VERSION_CODE}"
echo -e "    Version Name:  ${VERSION_NAME}"
echo -e "    APK File:      release/Hyperborea/apps/Hyperborea.apk"
echo -e "    ZIP Bundle:    release/${ZIP_NAME}"
echo ""
