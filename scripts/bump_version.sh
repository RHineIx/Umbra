#!/bin/bash

# ---------------------------------
# Interactive Android Version Bump Script
# Project: Umbra
# Strategy: VersionCode = Major * 10000 + Minor * 100 + Patch
# ---------------------------------

# Colors
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
RED='\033[0;31m'
NC='\033[0m' # No Color

# File paths
GRADLE_FILE="app/build.gradle.kts"

# --- Helper Functions ---
replace_in_file() {
  local pattern="$1"
  local file="$2"
  sed "$pattern" "$file" > "$file.tmp" && mv "$file.tmp" "$file"
}

# --- Check Environment ---
if [ ! -f "$GRADLE_FILE" ]; then
  echo -e "${RED}Error:${NC} $GRADLE_FILE not found! Are you in the project root?"
  exit 1
fi

# --- Read Current Version ---
current_version_name=$(grep 'versionName =' "$GRADLE_FILE" | sed -E 's/.*versionName = "([^"]+)".*/\1/')
current_version_code=$(grep 'versionCode =' "$GRADLE_FILE" | sed -E 's/.*versionCode = ([0-9]+).*/\1/')

if [ -z "$current_version_name" ]; then
  echo -e "${RED}Error:${NC} Failed to parse version info."
  exit 1
fi

# Clean and Split Version
base_version_name=$(echo "$current_version_name" | cut -d'-' -f1)
IFS='.' read -r major minor patch <<< "$base_version_name"
major=${major:-0}; minor=${minor:-0}; patch=${patch:-0}

# ==========================================
# INTERACTIVE MODE LOGIC
# ==========================================

# If no arguments provided, start interactive menu
if [ -z "$1" ]; then
    echo -e "${BLUE}======================================${NC}"
    echo -e "   📦 Umbra Version Manager"
    echo -e "${BLUE}======================================${NC}"
    echo -e "Current Version: ${YELLOW}$current_version_name${NC} (Code: $current_version_code)"
    echo
    echo "Select Bump Type:"
    echo "  1) Patch  ($major.$minor.$((patch+1)))   -> Bug fixes"
    echo "  2) Minor  ($major.$((minor+1)).0)   -> New features"
    echo "  3) Major  ($((major+1)).0.0)   -> Breaking changes"
    echo "  4) Exit"
    echo
    read -p "Enter choice [1-4]: " choice

    case $choice in
        1) BUMP_TYPE="patch" ;;
        2) BUMP_TYPE="minor" ;;
        3) BUMP_TYPE="major" ;;
        4) echo "Exiting..."; exit 0 ;;
        *) echo -e "${RED}Invalid choice!${NC}"; exit 1 ;;
    esac

    echo
    echo "Select Release Type:"
    echo "  1) Release (Stable)"
    echo "  2) Prerelease (Beta suffix)"
    echo
    read -p "Enter choice [1-2] (Default 1): " rel_choice
    rel_choice=${rel_choice:-1}

    case $rel_choice in
        1) RELEASE_TYPE="release" ;;
        2) RELEASE_TYPE="prerelease" ;;
        *) RELEASE_TYPE="release" ;;
    esac
    
    BUMP_COUNT=1

else
    # Non-Interactive Mode (Arguments passed)
    BUMP_TYPE=$1
    RELEASE_TYPE=$2
    BUMP_COUNT=${3:-1}
fi

# ==========================================
# CALCULATION LOGIC
# ==========================================

case $BUMP_TYPE in
  "patch") patch=$((patch + BUMP_COUNT)) ;;
  "minor") minor=$((minor + BUMP_COUNT)); patch=0 ;;
  "major") major=$((major + BUMP_COUNT)); minor=0; patch=0 ;;
esac

# Calculate New Code: Major * 10000 + Minor * 100 + Patch
new_version_code=$(( (major * 10000) + (minor * 100) + patch ))
new_version_name="$major.$minor.$patch"

if [[ "$RELEASE_TYPE" == "prerelease" ]]; then
  run_number=${GITHUB_RUN_NUMBER:-$(date +%s)}
  new_version_name="$new_version_name-beta.$run_number"
fi

# ==========================================
# EXECUTION
# ==========================================

echo
echo -e "Applying Update:"
echo -e "   Old: ${YELLOW}$current_version_name${NC} ($current_version_code)"
echo -e "   New: ${GREEN}$new_version_name${NC} ($new_version_code)"
echo

if [ -z "$1" ]; then
    read -p "Confirm update? (y/n): " confirm
    if [[ "$confirm" != "y" && "$confirm" != "Y" ]]; then
        echo "Cancelled."
        exit 0
    fi
fi

replace_in_file "s/versionName = \".*\"/versionName = \"$new_version_name\"/" "$GRADLE_FILE"
replace_in_file "s/versionCode = [0-9]*/versionCode = $new_version_code/" "$GRADLE_FILE"

echo -e "${GREEN}✔ Success! Files updated.${NC}"