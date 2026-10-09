#!/usr/bin/env bash
# Prepare the complete first-launch runtime bundle before APK packaging.
# Runtime initialization only unpacks assets and installs from this local repo;
# it never contacts an APT or npm network registry.
set -euo pipefail

ABI="${1:-arm64-v8a}"
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd -- "$SCRIPT_DIR/../android-ide" && pwd)"
ASSET_DIR="$PROJECT_DIR/android/assets/termux"
TOOLS_DIR="$PROJECT_DIR/runtime-tools"

case "$ABI" in
  arm64-v8a) ASSET_ABI="aarch64" ;;
  armeabi-v7a) ASSET_ABI="arm" ;;
  x86) ASSET_ABI="i686" ;;
  x86_64) ASSET_ABI="x86_64" ;;
  *) echo "Unsupported Android ABI: $ABI" >&2; exit 2 ;;
esac

if ! command -v python3 >/dev/null 2>&1; then
  echo "Python 3 is required to prepare the verified local Termux package repository." >&2
  exit 127
fi
if ! command -v npm >/dev/null 2>&1 || ! command -v node >/dev/null 2>&1; then
  echo "Node.js and npm are required to stage the locked language-server bundle." >&2
  exit 127
fi
node -e 'const [major,minor,patch]=process.versions.node.split(".").map(Number); if (major < 24 || (major === 24 && minor < 18)) process.exit(1)' || {
  echo "Use Node.js 24.18 or newer to prepare the pinned TypeScript language server bundle." >&2
  exit 3
}

test -s "$TOOLS_DIR/package.json" || { echo "The runtime-tools package manifest is missing." >&2; exit 4; }
test -s "$TOOLS_DIR/package-lock.json" || { echo "The locked runtime-tools dependency tree is missing." >&2; exit 4; }
mkdir -p "$ASSET_DIR"
WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/android-ide-runtime.XXXXXX")"
trap 'rm -rf "$WORK_DIR"' EXIT
BUNDLE_ROOT="$WORK_DIR/bundle"
mkdir -p "$BUNDLE_ROOT/offline-repo" "$BUNDLE_ROOT/tools"

python3 "$SCRIPT_DIR/prepare-termux-offline-repo.py" "$ABI" "$BUNDLE_ROOT/offline-repo"

NPM_STAGE="$WORK_DIR/npm"
mkdir -p "$NPM_STAGE"
cp "$TOOLS_DIR/package.json" "$TOOLS_DIR/package-lock.json" "$NPM_STAGE/"
npm ci --prefix "$NPM_STAGE" --omit=dev --ignore-scripts --no-audit --no-fund

python3 - "$NPM_STAGE/node_modules" "$BUNDLE_ROOT/tools/node_modules" "$BUNDLE_ROOT/tools/commands.json" "$TOOLS_DIR/package.json" <<'PY'
import json
import re
import shutil
import sys
from pathlib import Path

source = Path(sys.argv[1]).resolve()
target = Path(sys.argv[2]).resolve()
commands_file = Path(sys.argv[3])
manifest_file = Path(sys.argv[4])
manifest = json.loads(manifest_file.read_text(encoding="utf-8"))
if not source.is_dir():
    raise SystemExit("npm ci did not produce a dependency tree")
shutil.copytree(
    source,
    target,
    symlinks=False,
    ignore=shutil.ignore_patterns(".bin"),
)

commands = {}
for name in manifest["dependencies"]:
    package_dir = target.joinpath(*name.split("/"))
    package = json.loads((package_dir / "package.json").read_text(encoding="utf-8"))
    bins = package.get("bin", {})
    if isinstance(bins, str):
        bins = {name.rsplit("/", 1)[-1]: bins}
    for command, entry in bins.items():
        if not re.fullmatch(r"[A-Za-z0-9._-]+", command):
            raise SystemExit(f"Unsafe npm command name: {command}")
        entry_path = (package_dir / entry).resolve(strict=True)
        if not entry_path.is_file() or not entry_path.is_relative_to(package_dir.resolve()):
            raise SystemExit(f"Invalid npm command entrypoint for {command}")
        commands[command] = {
            "package": name,
            "entry": entry_path.relative_to(package_dir.resolve()).as_posix(),
        }

required = {
    "live-server",
    "pyright-langserver",
    "tsc",
    "tsserver",
    "typescript-language-server",
    "vscode-css-language-server",
    "vscode-html-language-server",
    "vscode-json-language-server",
}
missing = required - commands.keys()
if missing:
    raise SystemExit("The locked npm tools do not provide required commands: " + ", ".join(sorted(missing)))
commands_file.write_text(json.dumps({"commands": commands}, indent=2) + "\n", encoding="utf-8")
PY

OUTPUT="$ASSET_DIR/runtime-$ASSET_ABI.zip"
PARTIAL="$OUTPUT.partial"
rm -f "$PARTIAL"
python3 - "$BUNDLE_ROOT" "$PARTIAL" <<'PY'
import sys
import zipfile
from pathlib import Path

root = Path(sys.argv[1]).resolve()
out = Path(sys.argv[2])
with zipfile.ZipFile(out, "w", allowZip64=True) as archive:
    for path in sorted(root.rglob("*")):
        if not path.is_file():
            continue
        relative = path.relative_to(root).as_posix()
        info = zipfile.ZipInfo(relative, date_time=(1980, 1, 1, 0, 0, 0))
        info.create_system = 3
        info.external_attr = (0o100644 << 16)
        info.compress_type = zipfile.ZIP_STORED if path.suffix == ".deb" else zipfile.ZIP_DEFLATED
        archive.writestr(info, path.read_bytes(), compress_type=info.compress_type, compresslevel=6)
PY
mv "$PARTIAL" "$OUTPUT"
printf 'Prepared locked, offline Termux runtime bundle for %s at %s (%s bytes)\n' "$ABI" "$OUTPUT" "$(wc -c < "$OUTPUT")"
