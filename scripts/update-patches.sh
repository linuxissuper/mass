#!/usr/bin/env bash
# Regenerate patches/ from a branch of an upstream clone.
# The branch must sit on top of an upstream android-v* tag; every commit after
# that tag becomes one patch.
#
# usage: scripts/update-patches.sh <upstream-clone> [branch]   (branch defaults to volume-keys)
set -euo pipefail

here=$(cd "$(dirname "$0")/.." && pwd)
clone=${1:?path to the upstream clone}
branch=${2:-volume-keys}

base=$(git -C "$clone" describe --tags --abbrev=0 --match 'android-v*' "$branch")
echo "Exporting $base..$branch"

rm -f "$here"/patches/*.patch
mkdir -p "$here/patches"
git -C "$clone" format-patch --zero-commit --no-signature -o "$here/patches" "$base..$branch"
