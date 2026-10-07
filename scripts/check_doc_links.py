# Copyright (c) 2020-2026 gridDigIt Kft.
# Licensed under the EUPL-1.2-or-later.
# SPDX-License-Identifier: EUPL-1.2+
"""Checks the relative links in Markdown files: the target file or folder exists and, for
`file.md#anchor` links, the anchor matches a heading of that file (GitHub's slug rules).

External links (http, https, mailto) are not fetched. Links inside code blocks and inline code
are ignored. Standard library only (Python 3.10+).

    python scripts/check_doc_links.py                      # docs/guide/** and README.md
    python scripts/check_doc_links.py docs/cli docs/guide   # other files or folders

Exit code 0 when every link resolves, 1 otherwise (each broken link is listed).
"""
from __future__ import annotations

import re
import sys
from pathlib import Path
from urllib.parse import unquote

REPO = Path(__file__).resolve().parents[1]
DEFAULT_TARGETS = ["docs/guide", "README.md"]

FENCE = re.compile(r"^\s*(```|~~~)")
INLINE_CODE = re.compile(r"`[^`\n]*`")
LINK = re.compile(r"(?<!!)\[(?:[^\]\[]|\[[^\]]*\])*\]\(\s*<?([^)\s>]+)>?(?:\s+\"[^\"]*\")?\s*\)")
IMAGE = re.compile(r"!\[[^\]]*\]\(\s*<?([^)\s>]+)>?(?:\s+\"[^\"]*\")?\s*\)")
HEADING = re.compile(r"^(#{1,6})\s+(.*?)\s*#*\s*$")
HTML_ANCHOR = re.compile(r"<a\s+(?:name|id)=\"([^\"]+)\"", re.I)


def slug(heading: str) -> str:
    """GitHub's heading anchor: lower case, markup and punctuation dropped, spaces to hyphens."""
    text = re.sub(r"`([^`]*)`", r"\1", heading)                 # inline code keeps its text
    text = re.sub(r"\[([^\]]*)\]\([^)]*\)", r"\1", text)       # links keep their text
    text = re.sub(r"<[^>]+>", "", text)                         # HTML tags
    text = text.strip().lower()
    text = re.sub(r"[^\w\- ]", "", text)                        # keeps letters, digits, _, -, space
    return text.replace(" ", "-")


def anchors(md: Path) -> set[str]:
    found: set[str] = set()
    counts: dict[str, int] = {}
    in_code = False
    for line in md.read_text(encoding="utf-8").splitlines():
        if FENCE.match(line):
            in_code = not in_code
            continue
        if in_code:
            continue
        found.update(HTML_ANCHOR.findall(line))
        m = HEADING.match(line)
        if m:
            base = slug(m.group(2))
            n = counts.get(base, 0)
            found.add(base if n == 0 else f"{base}-{n}")
            counts[base] = n + 1
    return found


def links(md: Path):
    in_code = False
    for number, line in enumerate(md.read_text(encoding="utf-8").splitlines(), start=1):
        if FENCE.match(line):
            in_code = not in_code
            continue
        if in_code:
            continue
        line = INLINE_CODE.sub("", line)
        for pattern in (LINK, IMAGE):
            for m in pattern.finditer(line):
                yield number, m.group(1)


def check(md: Path, cache: dict[Path, set[str]]) -> list[str]:
    problems = []
    for number, target in links(md):
        if re.match(r"^[a-z][a-z0-9+.-]*:", target, re.I):   # http:, https:, mailto: ...
            continue
        path_part, _, anchor = target.partition("#")
        dest = md if not path_part else (md.parent / unquote(path_part)).resolve()
        where = f"{md.relative_to(REPO)}:{number}"
        if not dest.exists():
            problems.append(f"{where}: missing target {target}")
            continue
        if anchor and dest.suffix.lower() == ".md":
            if dest not in cache:
                cache[dest] = anchors(dest)
            if anchor.lower() not in cache[dest]:
                problems.append(f"{where}: no heading for #{anchor} in {dest.relative_to(REPO)}")
    return problems


def main(argv: list[str]) -> int:
    targets = argv or DEFAULT_TARGETS
    files: list[Path] = []
    for t in targets:
        p = (REPO / t).resolve()
        if p.is_dir():
            files.extend(sorted(p.rglob("*.md")))
        elif p.is_file():
            files.append(p)
        else:
            print(f"no such file or folder: {t}", file=sys.stderr)
            return 1
    cache: dict[Path, set[str]] = {}
    problems = [problem for f in files for problem in check(f, cache)]
    for problem in problems:
        print(problem)
    print(f"{len(files)} file(s) checked, {len(problems)} broken link(s)", file=sys.stderr)
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
