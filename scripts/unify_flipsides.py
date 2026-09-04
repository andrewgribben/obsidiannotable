#!/usr/bin/env python3
"""
Merge legacy Singularity / Notable flip-side sidecars into unified Excalidraw notes.

Converts pairs like:
  inbox/2026-03-18-20-09-39.md          (markdown text + flip-side: frontmatter)
  inbox/2026-03-18-20-09-39.flip.excalidraw.md

Into one Obsidian-compatible file (see Template.excalidraw.md):
  excalidraw frontmatter + markdown body + %% commented excalidraw json block

Usage (from your Mac, vault root as cwd or argument):

  pip3 install -r scripts/requirements.txt   # once
  python3 scripts/unify_flipsides.py /path/to/vault
  python3 scripts/unify_flipsides.py . --dry-run
  python3 scripts/unify_flipsides.py . --delete-sidecars

Skips notes that already contain a drawing section unless --force is passed.
"""

from __future__ import annotations

import argparse
import copy
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
DEFAULT_TEMPLATE = Path(__file__).resolve().parent.parent / "Template.excalidraw.md"

FLIP_SIDE_FM_RE = re.compile(r"^flip-side:\s*.+$", re.MULTILINE)
PDF_FM_RE = re.compile(r"^pdf:\s*.+$", re.MULTILINE)
FLIP_SIDE_LINK_RE = re.compile(
    r'^flip-side:\s*["\']?\[\[([^\]]+)]]', re.MULTILINE
)
PAIRED_COMMENT_RE = re.compile(r"%%[\s\S]*?%%")
DRAWING_MARKERS = (
    "==⚠",
    "# Excalidraw Data",
    "## Drawing",
    "# Drawing",
    "```compressed-json",
    "```json",
    "\n%%",
)
TEMPLATE_BLOCK_RE = re.compile(
    r"(%%\s*\n# Excalidraw Data[\s\S]*?```json\n)([\s\S]*?)(\n```\s*\n%%\s*)",
    re.MULTILINE,
)


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


def strip_hidden_regions(content: str) -> str:
    visible = PAIRED_COMMENT_RE.sub("", content)
    start = frontmatter_end(visible)
    draw = drawing_section_start(visible, start)
    if draw >= 0:
        visible = visible[:draw]
    return visible


def extract_markdown_body(content: str) -> str:
    cleaned = strip_hidden_regions(content)
    start = frontmatter_end(cleaned)
    return cleaned[start:].strip()


def parse_frontmatter_keys(content: str) -> dict[str, str]:
    if not content.startswith("---"):
        return {}
    end = content.find("\n---", 3)
    if end < 0:
        return {}
    keys: dict[str, str] = {}
    for line in content[3:end].splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith("#"):
            continue
        if ":" not in stripped:
            continue
        key, _, value = stripped.partition(":")
        keys[key.strip()] = value.strip()
    return keys


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
    if body_start < len(content) and content[body_start] == "\n":
        body_start += 1
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


def load_template(template_path: Path) -> dict:
    text = template_path.read_text(encoding="utf-8")
    fm_end = frontmatter_end(text)
    fm = text[:fm_end].rstrip() + "\n"
    rest = text[fm_end:].lstrip("\n")
    match = TEMPLATE_BLOCK_RE.search(rest)
    if not match:
        raise ValueError(f"Template missing Excalidraw comment block: {template_path}")
    default_json = json.loads(match.group(2))
    return {
        "frontmatter": fm,
        "comment_prefix": match.group(1),
        "comment_suffix": match.group(3),
        "default_json": default_json,
    }


def merge_frontmatter(note_content: str, template_fm: str) -> str:
    note_keys = parse_frontmatter_keys(note_content)
    for obsolete in ("flip-side", "pdf"):
        note_keys.pop(obsolete, None)

    lines = template_fm.rstrip().splitlines()
    closing_idx = len(lines) - 1
    extras: list[str] = []
    for key, value in note_keys.items():
        if key in ("excalidraw-plugin", "excalidraw-open-md", "tags"):
            continue
        extras.append(f"{key}: {value}")

    if extras:
        lines = lines[:closing_idx] + extras + lines[closing_idx:]
    return "\n".join(lines) + "\n"


def merge_drawing_json(sidecar_json: str, default_json: dict) -> str:
    side = json.loads(sidecar_json)
    merged = copy.deepcopy(default_json)
    for key in ("type", "version", "source", "elements", "files", "appState"):
        if key in side:
            merged[key] = side[key]
    if not merged.get("elements"):
        merged["elements"] = side.get("elements", [])
    return json.dumps(merged, indent="\t", ensure_ascii=False)


def build_unified_note(
    note_content: str,
    markdown_body: str,
    drawing_json: str,
    template: dict,
) -> str:
    fm = merge_frontmatter(note_content, template["frontmatter"])
    body = markdown_body.strip()
    drawing = merge_drawing_json(drawing_json, template["default_json"])
    parts = [fm.rstrip()]
    if body:
        parts.extend(["", body])
    parts.extend(
        [
            "",
            template["comment_prefix"] + drawing + template["comment_suffix"].rstrip(),
        ]
    )
    return "\n".join(parts) + "\n"


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
    template: dict,
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
        if not body.strip():
            body = extract_markdown_body(sidecar_content)
        unified = build_unified_note(note_content, body, drawing_json, template)
    else:
        body = extract_markdown_body(sidecar_content)
        unified = build_unified_note("", body, drawing_json, template)

    if dry_run:
        rel = (
            note_file.relative_to(vault_root)
            if note_file.is_relative_to(vault_root)
            else note_file.name
        )
        preview = " (no md body)" if not body.strip() else ""
        return f"would unify -> {rel}{preview}"

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
        "--template",
        type=Path,
        default=DEFAULT_TEMPLATE,
        help=f"Excalidraw template markdown (default: {DEFAULT_TEMPLATE})",
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

    template_path = args.template.expanduser().resolve()
    if not template_path.is_file():
        print(f"Template not found: {template_path}", file=sys.stderr)
        return 1

    try:
        template = load_template(template_path)
    except (ValueError, json.JSONDecodeError) as exc:
        print(f"Invalid template: {exc}", file=sys.stderr)
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
                template,
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
                template,
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
