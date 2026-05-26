#!/usr/bin/env python3
"""
One-shot refactor: align Kotlin package declarations with directory layout.

Reads every .kt file under app/src/{main,test}/java, computes the directory-
based package, and if it differs from the declared package:
  1) Rewrites the file's `package` declaration.
  2) Records a (oldPkg, symbol) -> newPkg mapping for every top-level symbol
     declared in that file.

Then walks every .kt file and AndroidManifest.xml and rewrites:
  - `import oldPkg.symbol`            -> `import newPkg.symbol`
  - `import oldPkg.*`                 -> `import newPkg.*` (if oldPkg maps
                                         unambiguously to one newPkg)
  - fully-qualified `oldPkg.symbol`   -> `newPkg.symbol` in code bodies
  - manifest `android:name=".oldShort.Class"` -> `".newShort.Class"`

No behavior changes. Idempotent: running twice is a no-op.
"""
from __future__ import annotations

import os
import re
import sys
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC_ROOTS = [
    ROOT / "app" / "src" / "main" / "java",
    ROOT / "app" / "src" / "test" / "java",
    ROOT / "app" / "src" / "androidTest" / "java",
]
APP_PKG_PREFIX = "com.waterfountainmachine.app"
MANIFEST = ROOT / "app" / "src" / "main" / "AndroidManifest.xml"

# Regex pieces ---------------------------------------------------------------

# Top-level declarations we want to surface as importable symbols.
DECL_RE = re.compile(
    r"^\s*"
    r"(?:public\s+|internal\s+|private\s+|protected\s+)?"  # visibility (ignored)
    r"(?:abstract\s+|open\s+|final\s+|sealed\s+|data\s+|inner\s+|enum\s+|annotation\s+|inline\s+|value\s+|external\s+)*"
    r"(?:class|object|interface|fun|val|var|typealias)"
    r"(?:\s*<[^>]*>)?"  # generic params on fun
    r"\s+`?([A-Za-z_][A-Za-z0-9_]*)`?"
)
# We also want `const val` / `lateinit var`, covered above by the modifier sweep.

PACKAGE_RE = re.compile(r"^\s*package\s+([\w.]+)\s*$", re.MULTILINE)
IMPORT_RE = re.compile(r"^\s*import\s+([\w.]+)(\.\*)?\s*$", re.MULTILINE)


def file_to_dir_pkg(path: Path) -> str | None:
    """Compute package from filesystem location."""
    for root in SRC_ROOTS:
        try:
            rel = path.relative_to(root)
            return ".".join(rel.parts[:-1])
        except ValueError:
            continue
    return None


def collect_top_level_symbols(text: str) -> set[str]:
    """Extract top-level declarations (classes, objects, top-level funs/vals)."""
    symbols: set[str] = set()
    depth = 0
    for raw in text.splitlines():
        # Crude brace depth tracker so we only collect symbols at file scope.
        # Strip strings/comments-lite for the counter; not perfect but fine
        # for well-formatted code.
        line = re.sub(r"//.*$", "", raw)
        line_no_strings = re.sub(r'"(?:\\.|[^"\\])*"', '""', line)
        if depth == 0:
            m = DECL_RE.match(line)
            if m:
                symbols.add(m.group(1))
        depth += line_no_strings.count("{") - line_no_strings.count("}")
        if depth < 0:
            depth = 0
    return symbols


# Step 1: scan all .kt files; build rename plan -----------------------------

renames: dict[tuple[str, str], str] = {}  # (oldPkg, symbol) -> newPkg
pkg_renames: dict[str, set[str]] = defaultdict(set)  # oldPkg -> {newPkg, ...}
files_to_rewrite: list[tuple[Path, str, str]] = []  # (path, oldPkg, newPkg)

all_kt: list[Path] = []
for root in SRC_ROOTS:
    if not root.exists():
        continue
    for p in root.rglob("*.kt"):
        all_kt.append(p)

for p in all_kt:
    text = p.read_text()
    m = PACKAGE_RE.search(text)
    if not m:
        continue
    declared = m.group(1)
    if not declared.startswith(APP_PKG_PREFIX):
        continue
    dir_pkg = file_to_dir_pkg(p)
    if dir_pkg is None or dir_pkg == declared:
        continue
    files_to_rewrite.append((p, declared, dir_pkg))
    pkg_renames[declared].add(dir_pkg)
    for sym in collect_top_level_symbols(text):
        renames[(declared, sym)] = dir_pkg

print(f"[scan] {len(all_kt)} .kt files total")
print(f"[scan] {len(files_to_rewrite)} files need package rewrite")
print(f"[scan] {len(renames)} symbol mappings")
print(f"[scan] {len(pkg_renames)} distinct old packages")

# Show ambiguity: old packages that map to multiple new packages
ambiguous = {old: news for old, news in pkg_renames.items() if len(news) > 1}
if ambiguous:
    print("[scan] AMBIGUOUS old packages (multiple new locations):")
    for old, news in ambiguous.items():
        print(f"  {old} -> {sorted(news)}")

unambiguous_pkg_map: dict[str, str] = {
    old: next(iter(news)) for old, news in pkg_renames.items() if len(news) == 1
}

# Step 2: rewrite package declarations --------------------------------------

for p, old, new in files_to_rewrite:
    text = p.read_text()
    new_text = PACKAGE_RE.sub(f"package {new}", text, count=1)
    if new_text != text:
        p.write_text(new_text)
print(f"[step2] rewrote {len(files_to_rewrite)} package declarations")

# Step 3: rewrite imports + FQ usages everywhere ----------------------------

# Build sorted symbol patterns (longest first to avoid prefix collisions).
# For FQ usages, regex like: \bcom\.waterfountainmachine\.app\.OLD\.SYM\b
def rewrite_imports_and_usages(text: str) -> tuple[str, int]:
    changes = 0

    # 3a. explicit imports
    def import_sub(m: re.Match) -> str:
        nonlocal changes
        full = m.group(1)
        is_wildcard = m.group(2) is not None
        if is_wildcard:
            if full in unambiguous_pkg_map:
                changes += 1
                return f"import {unambiguous_pkg_map[full]}.*"
            return m.group(0)
        # symbol = last segment, pkg = the rest
        if "." not in full:
            return m.group(0)
        pkg, sym = full.rsplit(".", 1)
        key = (pkg, sym)
        if key in renames:
            changes += 1
            return f"import {renames[key]}.{sym}"
        return m.group(0)

    text = IMPORT_RE.sub(import_sub, text)

    # 3b. fully-qualified usages in code bodies
    # Sort symbols by length desc to avoid partial overlaps
    for (old_pkg, sym), new_pkg in sorted(
        renames.items(), key=lambda kv: -len(kv[0][0]) - len(kv[0][1])
    ):
        pattern = re.compile(
            r"\b" + re.escape(f"{old_pkg}.{sym}") + r"\b"
        )
        new_text, n = pattern.subn(f"{new_pkg}.{sym}", text)
        if n:
            changes += n
            text = new_text

    return text, changes


total_changes = 0
files_touched = 0
for p in all_kt:
    text = p.read_text()
    new_text, n = rewrite_imports_and_usages(text)
    if n > 0:
        p.write_text(new_text)
        files_touched += 1
        total_changes += n
print(f"[step3] rewrote {total_changes} imports/usages across {files_touched} files")

# Step 4: rewrite AndroidManifest -------------------------------------------

# Manifest uses short form: android:name=".pkgSuffix.Class"
# Map: short-form old pkg suffix -> short-form new pkg suffix per class.
# We need the per-symbol mapping but rooted at the app prefix.
PREFIX_LEN = len(APP_PKG_PREFIX) + 1  # +1 for the trailing dot

def manifest_rewrite(text: str) -> tuple[str, int]:
    changes = 0
    pattern = re.compile(r'android:name="\.([\w.]+)\.([A-Za-z_][A-Za-z0-9_]*)"')

    def sub(m: re.Match) -> str:
        nonlocal changes
        short_pkg = m.group(1)
        sym = m.group(2)
        full_old_pkg = f"{APP_PKG_PREFIX}.{short_pkg}"
        key = (full_old_pkg, sym)
        if key in renames:
            new_full = renames[key]
            if new_full.startswith(APP_PKG_PREFIX + "."):
                new_short = new_full[PREFIX_LEN:]
                changes += 1
                return f'android:name=".{new_short}.{sym}"'
        return m.group(0)

    return pattern.sub(sub, text), changes


if MANIFEST.exists():
    text = MANIFEST.read_text()
    new_text, n = manifest_rewrite(text)
    if n:
        MANIFEST.write_text(new_text)
    print(f"[step4] manifest: {n} activity paths updated")
else:
    print("[step4] manifest not found, skipping")

print("[done]")
