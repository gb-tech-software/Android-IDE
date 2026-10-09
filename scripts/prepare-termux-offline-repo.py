#!/usr/bin/env python3
"""Prepare a minimal, checksum-verified Termux APT repository for offline setup.

This is a build-time asset preparation helper. The Android app never downloads
packages or accesses a network repository during runtime initialization.
"""
from __future__ import annotations

import concurrent.futures
import gzip
import hashlib
import io
import json
import os
import re
import shutil
import sys
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path, PurePosixPath

REPOSITORY = "https://packages.termux.dev/apt/termux-main/"
ROOT_PACKAGES = (
    "ca-certificates",
    "curl",
    "git",
    "nodejs-lts",
    "openssh",
    "procps",
    "python",
    "unzip",
    "zip",
)
ABI_ARCH = {
    "arm64-v8a": "aarch64",
    "armeabi-v7a": "arm",
    "x86": "i686",
    "x86_64": "x86_64",
}


def download(url: str, timeout: int = 120) -> bytes:
    request = urllib.request.Request(url, headers={"User-Agent": "Android-IDE-runtime-bundler/1"})
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return response.read()


def parse_stanzas(text: str) -> list[tuple[dict[str, str], str]]:
    records: list[tuple[dict[str, str], str]] = []
    for raw in re.split(r"\n\s*\n", text.strip()):
        fields: dict[str, str] = {}
        current: str | None = None
        for line in raw.splitlines():
            if line[:1].isspace() and current:
                fields[current] += " " + line.strip()
            elif ": " in line:
                current, value = line.split(": ", 1)
                fields[current] = value
        if fields.get("Package"):
            records.append((fields, raw.strip() + "\n"))
    return records


def dependency_groups(value: str) -> list[list[str]]:
    groups: list[str] = []
    current: list[str] = []
    depth = 0
    for char in value:
        if char == "(":
            depth += 1
        elif char == ")":
            depth = max(0, depth - 1)
        if char == "," and depth == 0:
            groups.append("".join(current).strip())
            current = []
        else:
            current.append(char)
    if current:
        groups.append("".join(current).strip())
    result: list[list[str]] = []
    for group in groups:
        alternatives = []
        for alternative in group.split("|"):
            match = re.match(r"\s*([A-Za-z0-9][A-Za-z0-9+.-]*)(?::[A-Za-z0-9_-]+)?", alternative)
            if match:
                alternatives.append(match.group(1))
        if alternatives:
            result.append(alternatives)
    return result


def main() -> int:
    if len(sys.argv) != 3 or sys.argv[1] not in ABI_ARCH:
        print("usage: prepare-termux-offline-repo.py <android-abi> <output-directory>", file=sys.stderr)
        return 2
    abi, output_arg = sys.argv[1], sys.argv[2]
    arch = ABI_ARCH[abi]
    output = Path(output_arg).resolve()
    if output.exists():
        shutil.rmtree(output)
    output.mkdir(parents=True)

    index_url = urllib.parse.urljoin(REPOSITORY, f"dists/stable/main/binary-{arch}/Packages.gz")
    try:
        index = gzip.decompress(download(index_url)).decode("utf-8")
    except Exception as error:
        raise SystemExit(f"Unable to fetch the official Termux {arch} package index: {error}")

    records = parse_stanzas(index)
    candidates: dict[str, list[tuple[dict[str, str], str]]] = {}
    providers: dict[str, list[str]] = {}
    for fields, stanza in records:
        package = fields.get("Package", "")
        candidates.setdefault(package, []).append((fields, stanza))
        for provided in fields.get("Provides", "").split(","):
            name = re.match(r"\s*([A-Za-z0-9][A-Za-z0-9+.-]*)", provided)
            if name:
                providers.setdefault(name.group(1), []).append(package)

    selected: dict[str, tuple[dict[str, str], str]] = {}

    def choose(name: str) -> tuple[dict[str, str], str] | None:
        options = candidates.get(name)
        if not options:
            for provider in providers.get(name, []):
                options = candidates.get(provider)
                if options:
                    break
        if not options:
            return None
        compatible = [item for item in options if item[0].get("Architecture") in (arch, "all")]
        if not compatible:
            return None
        exact = [item for item in compatible if item[0].get("Architecture") == arch]
        return (exact or compatible)[0]

    pending = list(ROOT_PACKAGES)
    while pending:
        name = pending.pop()
        if name in selected:
            continue
        chosen = choose(name)
        if chosen is None:
            raise SystemExit(f"Termux package index has no {arch}/all package or provider for required package: {name}")
        fields, _ = chosen
        selected[fields["Package"]] = chosen
        for field_name in ("Pre-Depends", "Depends"):
            for alternatives in dependency_groups(fields.get(field_name, "")):
                dependency = next((candidate for candidate in alternatives if choose(candidate) is not None), None)
                if dependency is None:
                    raise SystemExit(
                        f"Cannot resolve {field_name} dependency of {fields['Package']}: {' | '.join(alternatives)}"
                    )
                if dependency not in selected:
                    pending.append(dependency)

    # A flat local APT repository is deliberately used so bootstrap setup can
    # point apt-get at one file: URL and disable every network source.
    rewritten_stanzas: list[str] = []
    download_jobs: list[tuple[str, Path, str, str]] = []
    for package, (fields, stanza) in sorted(selected.items()):
        filename = fields.get("Filename", "")
        relative = PurePosixPath(filename)
        digest = fields.get("SHA256", "").lower()
        if relative.is_absolute() or ".." in relative.parts or not filename or not re.fullmatch(r"[0-9a-f]{64}", digest):
            raise SystemExit(f"Invalid package metadata for {package}; refusing an unverified artifact")
        basename = relative.name
        download_url = urllib.parse.urljoin(REPOSITORY, filename)
        target = output / basename
        download_jobs.append((download_url, target, digest, package))
        rewritten = re.sub(r"(?m)^Filename: .*?$", f"Filename: {basename}", stanza)
        rewritten_stanzas.append(rewritten.rstrip() + "\n")

    def fetch_package(job: tuple[str, Path, str, str]) -> tuple[str, int]:
        url, target, expected, package = job
        target.parent.mkdir(parents=True, exist_ok=True)
        try:
            payload = download(url)
        except (urllib.error.URLError, TimeoutError) as error:
            raise RuntimeError(f"Unable to download {package}: {error}") from error
        actual = hashlib.sha256(payload).hexdigest()
        if actual != expected:
            raise RuntimeError(f"Checksum verification failed for {package}")
        target.write_bytes(payload)
        return package, len(payload)

    total_bytes = 0
    with concurrent.futures.ThreadPoolExecutor(max_workers=6) as pool:
        for package, size in pool.map(fetch_package, download_jobs):
            total_bytes += size
            print(f"Prepared {package} ({size} bytes)")

    packages_index = "\n".join(rewritten_stanzas).encode("utf-8") + b"\n"
    (output / "Packages").write_bytes(packages_index)
    (output / "Packages.gz").write_bytes(gzip.compress(packages_index, compresslevel=9, mtime=0))
    manifest = {
        "repository": REPOSITORY,
        "termuxArchitecture": arch,
        "androidAbi": abi,
        "packages": [
            {
                "name": name,
                "version": fields.get("Version", ""),
                "architecture": fields.get("Architecture", ""),
                "sha256": fields.get("SHA256", ""),
                "filename": fields.get("Filename", "").rsplit("/", 1)[-1],
            }
            for name, (fields, _) in sorted(selected.items())
        ],
        "archiveBytes": total_bytes,
    }
    (output / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    print(f"Prepared {len(selected)} offline Termux packages for {abi} ({total_bytes} package bytes)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
