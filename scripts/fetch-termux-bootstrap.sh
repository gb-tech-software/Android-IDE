#!/usr/bin/env bash
# Download one pinned Termux bootstrap archive for the requested APK ABI.
# The archive is generated into android-ide/android/assets/termux at build time;
# it is intentionally ignored by Git so the repository does not contain every ABI.
set -euo pipefail

# Gradle passes the selected ABI as the first argument. It must take
# precedence over a developer or CI environment variable so the bootstrap
# archive and APK ABI can never silently diverge.
ABI="${1:-${TERMUX_ABI:-arm64-v8a}}"
TAG="bootstrap-2026.09.13-r1+apt.android-7"
BASE_URL="https://github.com/termux/termux-packages/releases/download/${TAG}"
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
OUT_DIR="$SCRIPT_DIR/../android-ide/android/assets/termux"
mkdir -p "$OUT_DIR"
OUT_DIR="$(cd -- "$OUT_DIR" && pwd)"

case "$ABI" in
  arm64-v8a) ASSET="bootstrap-aarch64.zip"; SHA="dbf2805613ff2ace0b233c3b349e080bb0ff358f4ad64f4cca5966b27b93e7ee" ;;
  armeabi-v7a) ASSET="bootstrap-arm.zip"; SHA="ac65b4c4aa10322a9a54f8ec3e3122dec1371bef4834db4455503df0fc33f521" ;;
  x86) ASSET="bootstrap-i686.zip"; SHA="790481a60eb90dfcbaccc27113c11fa14d6820215bec7860b9a03e3417ec827" ;;
  x86_64) ASSET="bootstrap-x86_64.zip"; SHA="e5b7ce18642c6769acf6cd05586170191fc98f1882ddbde6b5fb5f0b1dc253f0" ;;
  *) echo "Unsupported TERMUX_ABI: $ABI" >&2; exit 2 ;;
esac

TARGET="$OUT_DIR/$ASSET"
if [[ ! -f "$TARGET" ]] || [[ "$(sha256sum "$TARGET" | cut -d' ' -f1)" != "$SHA" ]]; then
  if [[ "${TERMUX_BOOTSTRAP_OFFLINE:-0}" == "1" ]]; then
    echo "Offline mode is enabled and the pinned Termux bootstrap is missing or invalid: $TARGET" >&2
    echo "Provide a verified local archive or unset TERMUX_BOOTSTRAP_OFFLINE when network access is available." >&2
    exit 3
  fi
  if ! command -v curl >/dev/null 2>&1; then
    echo "Cannot download the pinned Termux bootstrap: curl is not installed." >&2
    echo "Install curl, provide a verified local $ASSET in $OUT_DIR, or set TERMUX_BOOTSTRAP_OFFLINE=1." >&2
    exit 127
  fi
  TMP="$TARGET.partial"
  trap 'rm -f "$TMP"' EXIT
  if ! curl --fail --location --retry 3 --silent --show-error "$BASE_URL/$ASSET" -o "$TMP"; then
    echo "Unable to download the pinned Termux bootstrap for $ABI; the network may be unavailable." >&2
    echo "Retry with network access or place the verified archive at $TARGET." >&2
    exit 4
  fi
  printf '%s  %s\n' "$SHA" "$TMP" | sha256sum -c -
  mv "$TMP" "$TARGET"
  trap - EXIT
fi

# Keep only the selected default bootstrap archive. Runtime package bundles are
# intentionally not generated or shipped; optional LSP packages install on demand.
find "$OUT_DIR" -maxdepth 1 -type f \( -name 'bootstrap-*.zip' ! -name "$ASSET" -o -name 'runtime-*.zip' \) -delete
printf 'Prepared pinned Termux bootstrap %s for %s at %s\n' "$TAG" "$ABI" "$TARGET"
