#!/usr/bin/env python3
"""
Merge legacy Singularity / Notable flip-side sidecars into unified Excalidraw notes.

Converts pairs like:
  inbox/2026-03-18-20-09-39.md          (text + flip-side: frontmatter)
  inbox/2026-03-18-20-09-39.flip.excalidraw.md

Into one Obsidian-compatible file:
  excalidraw-plugin frontmatter + markdown body + compressed-json drawing block

Usage (from your Mac, vault root as cwd or argument):

  pip3 install -r scripts/requirements.txt   # once
  python3 scripts/unify_flipsides.py /path/to/vault
  python3 scripts/unify_flipsides.py . --dry-run
  python3 scripts/unify_flipsides.py . --delete-sidecars

Skips notes that already contain a drawing section unless --force is passed.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

try:
    import lzstring
except ImportError:
    print(
        "Missing dependency: pip3 install lzstring\n"
        "  or: pip3 install -r scripts/requirements.txt",
        file=sys.stderr,
    )
    sys.exit(1)

FLIP_SIDE_SUFFIX = ".flip.excalidraw.md"
DRAWING_WARNING = (
    "==⚠  Switch to EXCALIDRAW VIEW in the MORE OPTIONS menu of this document. ⚠== "
    "You can decompress Drawing data with the command palette: "
    "'Decompress current Excalidraw file'. For more info check in plugin settings under 'Saving'"
)

FLIP_SIDE_FM_RE = re.compile(r"^flip-side:\s*.+$", re.MULTILINE)
PDF_FM_RE = re.compile(r"^pdf:\s*.+$", re.MULTILINE)
FLIP_SIDE_LINK_RE = re.compile(
    r'^flip-side:\s*["\']?\[\[([^\]]+)]]', re.MULTILINE
)
DRAWING_MARKERS = ("==⚠", "## Drawing", "# Drawing", "```compressed-json", "```json")


def frontmatter_end(content: str) -> int:
    if not content.startswith("---"):
        return 0
    end = content.find("\n---", 3)
    if end < 0:
        return 0
    after = end + len("\n---")
    if after < len(content) and content[after] == "\n":
        return after + 1
    return after


def drawing_section_start(content: str, search_from: int = 0) -> int:
    indices = [content.find(m, search_from) for m in DRAWING_MARKERS]
    found = [i for i in indices if i >= 0]
    return min(found) if found else -1


def has_drawing_section(content: str) -> bool:
    return drawing_section_start(content) >= 0 or content.strip().startswith("{")


def extract_markdown_body(content: str) -> str:
    start = frontmatter_end(content)
    draw = drawing_section_start(content, start)
    body = content[start:draw] if draw >= 0 else content[start:]
    return body.strip()


def extract_fenced_block(content: str, language: str) -> str | None:
    fence = f"```{language}"
    draw_start = drawing_section_start(content)
    search_from = draw_start if draw_start >= 0 else 0
    pos = content.find(fence, search_from)
    if pos < 0:
        pos = content.find(fence)
    if pos < 0:
        return None
    body_start = pos + len(fence)
    end = content.find("```", body_start)
    if end < 0:
        return None
    text = content[body_start:end].strip()
    return text or None


def extract_drawing_json(content: str) -> str | None:
    trimmed = content.strip()
    if trimmed.startswith("{"):
        return trimmed

    compressed = extract_fenced_block(content, "compressed-json")
    if compressed:
        cleaned = re.sub(r"[\r\n]", "", compressed)
        try:
            decoded = lzstring.LZString().decompressFromBase64(cleaned)
        except (IndexError, ValueError, TypeError):
            decoded = None
        if decoded:
            return decoded

    plain = extract_fenced_block(content, "json")
    if plain:
        return plain

    return None


def ensure_excalidraw_frontmatter(content: str) -> str:
    stripped = FLIP_SIDE_FM_RE.sub("", content)
    stripped = PDF_FM_RE.sub("", stripped)
    stripped = re.sub(r"\n{3,}", "\n\n", stripped)

    if not stripped.startswith("---"):
        parts = [
            "---",
            "excalidraw-plugin: parsed",
            "tags:",
            "  - excalidraw",
            "---",
        ]
        if stripped.strip():
            parts.extend(["", stripped.strip(), ""])
        return "\n".join(parts)

    end = stripped.find("\n---", 3)
    if end < 0:
        return stripped

    fm = stripped[:end]
    tail = stripped[end:]
    if "excalidraw-plugin:" not in fm:
        fm += "\nexcalidraw-plugin: parsed"
    if "excalidraw" not in fm:
        if "tags:" in fm:
            fm += "\n  - excalidraw"
        else:
            fm += "\ntags:\n  - excalidraw"
    return fm + tail


def build_drawing_section(json_text: str) -> str:
    # Normalise JSON so compression matches Obsidian / Singularity expectations.
    parsed = json.loads(json_text)
    compact = json.dumps(parsed, separators=(",", ":"), ensure_ascii=False)
    compressed = lzstring.LZString().compressToBase64(compact)
    return (
        f"{DRAWING_WARNING}\n\n\n"
        f"## Drawing\n"
        f"```compressed-json\n"
        f"{compressed}\n"
        f"```\n"
        f"%%"
    )


def rewrite_unified(note_content: str, markdown_body: str, drawing_json: str) -> str:
    with_fm = ensure_excalidraw_frontmatter(note_content)
    head_end = frontmatter_end(with_fm)
    head = with_fm[:head_end].rstrip()
    body = markdown_body.strip()
    lines = [head]
    if body:
        lines.extend(["", body])
    lines.extend(["", build_drawing_section(drawing_json)])
    return "\n".join(lines) + "\n"


def default_sidecar_for(note_file: Path) -> Path:
    stem = note_file.name[:-3] if note_file.name.lower().endswith(".md") else note_file.name
    return note_file.parent / f"{stem}{FLIP_SIDE_SUFFIX}"


def resolve_sidecar(note_file: Path, vault_root: Path, note_content: str) -> Path:
    default = default_sidecar_for(note_file)
    match = FLIP_SIDE_LINK_RE.search(note_content)
    if not match:
        return default

    target = match.group(1).strip()
    with_md = target if target.lower().endswith(".md") else f"{target}.md"
    note_dir = note_file.parent.relative_to(vault_root).as_posix()
    candidates: list[Path] = []
    if note_dir and note_dir != ".":
        candidates.append(vault_root / note_dir / with_md)
        candidates.append(vault_root / note_dir / target)
    candidates.append(vault_root / with_md)
    candidates.append(vault_root / target)
    candidates.append(default)

    for path in candidates:
        if path.is_file():
            return path
    return default


def note_for_sidecar(sidecar: Path) -> Path:
    if not sidecar.name.endswith(FLIP_SIDE_SUFFIX):
        raise ValueError(f"Not a flip sidecar: {sidecar}")
    base = sidecar.name[: -len(FLIP_SIDE_SUFFIX)]
    return sidecar.parent / f"{base}.md"


def unify_pair(
    vault_root: Path,
    note_file: Path,
    sidecar_file: Path,
    *,
    dry_run: bool,
    force: bool,
    delete_sidecars: bool,
) -> str:
    if not sidecar_file.is_file():
        return "skip (no sidecar)"

    sidecar_content = sidecar_file.read_text(encoding="utf-8")
    drawing_json = extract_drawing_json(sidecar_content)
    if not drawing_json:
        return f"skip (no drawing JSON in {sidecar_file.name})"

    if note_file.is_file():
        note_content = note_file.read_text(encoding="utf-8")
        if has_drawing_section(note_content) and not force:
            if delete_sidecars and dry_run:
                return f"would delete orphan sidecar (note already unified): {sidecar_file.name}"
            if delete_sidecars:
                sidecar_file.unlink()
                return f"deleted orphan sidecar (note already unified): {sidecar_file.name}"
            return f"skip (note already has drawing): {note_file.name}"

        body = extract_markdown_body(note_content)
        # Sidecar may contain text only in rare cases; prefer note body.
        if not body.strip():
            body = extract_markdown_body(sidecar_content)
        unified = rewrite_unified(note_content, body, drawing_json)
    else:
        body = extract_markdown_body(sidecar_content)
        unified = rewrite_unified("", body, drawing_json)

    if dry_run:
        rel = note_file.relative_to(vault_root) if note_file.is_relative_to(vault_root) else note_file.name
        return f"would unify -> {rel}"

    note_file.parent.mkdir(parents=True, exist_ok=True)
    note_file.write_text(unified, encoding="utf-8")
    if delete_sidecars:
        sidecar_file.unlink()
    action = "unified + deleted sidecar" if delete_sidecars else "unified (sidecar kept)"
    return f"{action}: {note_file.name}"


def collect_sidecars(vault_root: Path) -> list[Path]:
    sidecars: list[Path] = []
    for path in vault_root.rglob("*"):
        if path.is_file() and path.name.endswith(FLIP_SIDE_SUFFIX):
            if any(part.startswith(".") for part in path.relative_to(vault_root).parts):
                continue
            sidecars.append(path)
    return sorted(sidecars)


def collect_flip_frontmatter_notes(vault_root: Path) -> list[tuple[Path, Path]]:
    """Notes with flip-side: frontmatter and an existing sidecar not yet unified."""
    pairs: list[tuple[Path, Path]] = []
    for path in vault_root.rglob("*.md"):
        if path.name.endswith(FLIP_SIDE_SUFFIX):
            continue
        if any(part.startswith(".") for part in path.relative_to(vault_root).parts):
            continue
        try:
            content = path.read_text(encoding="utf-8")
        except OSError:
            continue
        if not FLIP_SIDE_FM_RE.search(content):
            continue
        sidecar = resolve_sidecar(path, vault_root, content)
        if sidecar.is_file():
            pairs.append((path, sidecar))
    return pairs


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Merge .flip.excalidraw.md sidecars into unified Excalidraw markdown notes."
    )
    parser.add_argument(
        "vault",
        type=Path,
        help="Vault root directory (folder containing inbox, .obsidian, etc.)",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="Print actions without writing files",
    )
    parser.add_argument(
        "--force",
        action="store_true",
        help="Replace an existing drawing section in the note",
    )
    parser.add_argument(
        "--delete-sidecars",
        action="store_true",
        help="Remove .flip.excalidraw.md files after a successful merge",
    )
    args = parser.parse_args()

    vault_root = args.vault.expanduser().resolve()
    if not vault_root.is_dir():
        print(f"Not a directory: {vault_root}", file=sys.stderr)
        return 1

    seen: set[tuple[Path, Path]] = set()
    results: list[str] = []

    for sidecar in collect_sidecars(vault_root):
        note = note_for_sidecar(sidecar)
        key = (note.resolve(), sidecar.resolve())
        if key in seen:
            continue
        seen.add(key)
        results.append(
            unify_pair(
                vault_root,
                note,
                sidecar,
                dry_run=args.dry_run,
                force=args.force,
                delete_sidecars=args.delete_sidecars,
            )
        )

    for note, sidecar in collect_flip_frontmatter_notes(vault_root):
        key = (note.resolve(), sidecar.resolve())
        if key in seen:
            continue
        seen.add(key)
        results.append(
            unify_pair(
                vault_root,
                note,
                sidecar,
                dry_run=args.dry_run,
                force=args.force,
                delete_sidecars=args.delete_sidecars,
            )
        )

    if not results:
        print("No flip-side sidecars found.")
        return 0

    for line in results:
        print(line)

    unified = sum(1 for r in results if r.startswith("unified") or r.startswith("would unify"))
    print(f"\nDone: {unified} processed, {len(results) - unified} skipped.")
    if args.dry_run:
        print("(dry run — no files changed)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
