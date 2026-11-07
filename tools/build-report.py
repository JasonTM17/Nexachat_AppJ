#!/usr/bin/env python3
"""Build docs/report.docx — the NexaChat final report.

The docx is generated directly from docs/report.md (the canonical source):
headings, paragraphs with inline bold/italic/code, bullet and numbered
lists, GitHub tables, fenced code blocks, blockquotes and horizontal rules.
python-docx cannot render Mermaid, so fenced blocks (including the Mermaid
source) are emitted as monospace boxes.

Outline: the report title/subtitle open the cover page; body H2 sections
become docx Heading 1 (with a page break before each, as the previous builder
did) and H3 subsections become docx Heading 2. Code boxes keep the previous
monospace/shaded/bordered look but are drawn as bordered paragraphs so they
do not inflate the document table count (which must equal the markdown table
count).

Usage (from repo root or anywhere):
    python tools/build-report.py

Output: docs/report.docx (written to a temp file, then replaced atomically)
Requires: python-docx >= 1.1
"""
from __future__ import annotations

import argparse
import os
import re
import sys
import tempfile
from pathlib import Path

from docx import Document
from docx.enum.text import WD_ALIGN_PARAGRAPH
from docx.oxml import OxmlElement
from docx.oxml.ns import qn
from docx.shared import Cm, Pt, RGBColor

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "docs" / "report.md"
OUT = ROOT / "docs" / "report.docx"

MONO = "Consolas"
TAGLINE = ("Java 17 · Android XML Views / Material 3 · Spring Boot · "
           "PostgreSQL · WebSocket/STOMP")
# Cover tables keep the look of the previous hand-built cover page.
COVER_WIDTHS = {2: [6, 9], 4: [1, 4.5, 3, 6.5]}

HEADING_RE = re.compile(r"^(#{1,6})\s+(.*)$")
HR_RE = re.compile(r"^\s*([-*_])\s*(\1\s*){2,}$")
LIST_RE = re.compile(r"^(\s*)([-*+]|\d+\.)\s+(.*)$")
DELIM_CELL_RE = re.compile(r"^:?-+:?$")


# --------------------------------------------------------------------------
# styling helpers (kept from the previous builder)
# --------------------------------------------------------------------------
def _shade(el, hex_fill):
    """Shade a cell (or paragraph) with a solid fill."""
    if hasattr(el, "_tc"):
        pr = el._tc.get_or_add_tcPr()
    else:
        pr = el._p.get_or_add_pPr()
    shd = OxmlElement("w:shd")
    shd.set(qn("w:val"), "clear")
    shd.set(qn("w:color"), "auto")
    shd.set(qn("w:fill"), hex_fill)
    pr.append(shd)


def _border(p, color="A6A6A6", size="4"):
    pPr = p._p.get_or_add_pPr()
    pBdr = OxmlElement("w:pBdr")
    for edge in ("top", "left", "bottom", "right"):
        el = OxmlElement(f"w:{edge}")
        el.set(qn("w:val"), "single")
        el.set(qn("w:sz"), size)
        el.set(qn("w:space"), "4")
        el.set(qn("w:color"), color)
        pBdr.append(el)
    pPr.append(pBdr)


def h1(doc, text):
    return add_heading(doc, 1, text)


def h2(doc, text):
    return add_heading(doc, 2, text)


def h3(doc, text):
    return add_heading(doc, 3, text)


def add_heading(doc, level, text):
    p = doc.add_heading("", level=level)
    add_runs(p, text)
    return p


def para(doc, text="", bold=False, italic=False, size=11, align=None):
    p = doc.add_paragraph()
    run = p.add_run(text)
    run.bold = bold
    run.italic = italic
    run.font.size = Pt(size)
    if align is not None:
        p.alignment = align
    return p


def rich(doc, segments, align=None):
    """segments: list of (text, bold, italic)."""
    p = doc.add_paragraph()
    for text, bold, italic in segments:
        run = p.add_run(text)
        run.bold = bold
        run.italic = italic
    if align is not None:
        p.alignment = align
    return p


def bullet(doc, text, level=1):
    style = "List Bullet" if level == 1 else f"List Bullet {level}"
    p = doc.add_paragraph(style=style)
    add_runs(p, text)
    return p


def numbered(doc, text, level=1):
    style = "List Number" if level == 1 else f"List Number {level}"
    p = doc.add_paragraph(style=style)
    add_runs(p, text)
    return p


def code_box(doc, text):
    """Monospace code inside a bordered, shaded box (a paragraph, not a table).

    Kept under the same name/look as the previous table-based helper, but
    implemented as a bordered paragraph so code boxes do not inflate the
    document table count.
    """
    p = doc.add_paragraph()
    _border(p)
    _shade(p, "F2F2F2")
    pf = p.paragraph_format
    pf.space_before = Pt(0)
    pf.space_after = Pt(0)
    for i, line in enumerate(text.split("\n")):
        if i:
            p.add_run().add_break()
        run = p.add_run(line if line else " ")
        run.font.name = MONO
        run.font.size = Pt(8.5)
        # keep every space significant so ASCII diagrams stay aligned
        for t in run._r.findall(qn("w:t")):
            t.set(qn("xml:space"), "preserve")
    doc.add_paragraph().paragraph_format.space_after = Pt(2)
    return p


def table(doc, headers, rows, widths=None, font_size=9):
    tbl = doc.add_table(rows=1, cols=len(headers))
    tbl.style = "Table Grid"
    hdr = tbl.rows[0].cells
    for i, htext in enumerate(headers):
        hdr[i].text = ""
        add_runs(hdr[i].paragraphs[0], htext, size=font_size, bold=True)
        _shade(hdr[i], "D9E2F3")
    for row in rows:
        cells = tbl.add_row().cells
        for i, val in enumerate(row):
            cells[i].text = ""
            add_runs(cells[i].paragraphs[0], str(val), size=font_size)
    if widths:
        for i, w in enumerate(widths):
            for row in tbl.rows:
                row.cells[i].width = Cm(w)
    doc.add_paragraph().paragraph_format.space_after = Pt(2)
    return tbl


def note(doc, text):
    p = doc.add_paragraph()
    add_runs(p, text, size=9.5, italic=True, color=RGBColor(0x44, 0x44, 0x44))
    return p


def page_break(doc):
    doc.add_page_break()


# --------------------------------------------------------------------------
# inline markdown: **bold**, *italic*, `code`, [text](url), escapes
# --------------------------------------------------------------------------
ESC_CHARS = "`*_{}[]()#+-.!|"


def parse_inline(text):
    """Return a list of runs: (text, bold, italic, mono)."""
    runs = []
    buf = ""
    i = 0
    n = len(text)

    def flush():
        nonlocal buf
        if buf:
            runs.append((buf, False, False, False))
            buf = ""

    while i < n:
        c = text[i]
        if c == "\\" and i + 1 < n and text[i + 1] in ESC_CHARS:
            buf += text[i + 1]
            i += 2
            continue
        if c == "`":
            j = text.find("`", i + 1)
            if j == -1:
                buf += c
                i += 1
                continue
            flush()
            runs.append((text[i + 1:j], False, False, True))
            i = j + 1
            continue
        if c == "*":
            if text.startswith("**", i):
                j = text.find("**", i + 2)
                if j == -1:
                    buf += "**"
                    i += 2
                    continue
                flush()
                for t, b, it, m in parse_inline(text[i + 2:j]):
                    runs.append((t, True or b, it, m))
                i = j + 2
                continue
            j = text.find("*", i + 1)
            if j == -1:
                buf += c
                i += 1
                continue
            flush()
            for t, b, it, m in parse_inline(text[i + 1:j]):
                runs.append((t, b, True or it, m))
            i = j + 1
            continue
        if c == "[":
            m = re.match(r"\[([^\]]*)\]\(([^)]*)\)", text[i:])
            if m:
                flush()
                for t, b, it, mono in parse_inline(m.group(1)):
                    runs.append((t, b, it, mono))
                i += m.end()
                continue
            buf += c
            i += 1
            continue
        if c == "!" and text.startswith("![", i):
            m = re.match(r"!\[([^\]]*)\]\(([^)]*)\)", text[i:])
            if m:
                flush()
                runs.append((m.group(1), False, False, False))
                i += m.end()
                continue
        buf += c
        i += 1
    flush()
    return runs


def add_runs(p, text, size=None, bold=False, italic=False, color=None):
    for t, b, it, mono in parse_inline(text):
        if not t:
            continue
        run = p.add_run(t)
        run.bold = bold or b
        run.italic = italic or it
        if mono:
            run.font.name = MONO
        if size is not None:
            run.font.size = Pt(size)
        if color is not None:
            run.font.color.rgb = color
    return p


# --------------------------------------------------------------------------
# block parser
# --------------------------------------------------------------------------
def is_list_start(lines, i):
    """A list may only start at indent 0; a '+' marker needs a blank line
    above it so wrapped paragraph lines such as '+ jump), ...' are not
    mistaken for bullets."""
    m = LIST_RE.match(lines[i])
    if not m or len(m.group(1)) != 0:
        return False
    if m.group(2) == "+":
        return i == 0 or not lines[i - 1].strip()
    return True


def is_block_start(lines, i):
    line = lines[i]
    s = line.strip()
    if not s:
        return True
    if s.startswith("```"):
        return True
    if HEADING_RE.match(line):
        return True
    if HR_RE.match(line):
        return True
    if line.lstrip().startswith(">"):
        return True
    if line.lstrip().startswith("|"):
        return True
    if is_list_start(lines, i):
        return True
    return False


def split_row(line):
    s = line.strip()
    if s.startswith("|"):
        s = s[1:]
    if s.endswith("|"):
        s = s[:-1]
    cells = []
    buf = ""
    in_code = False
    for ch in s:
        if ch == "`":
            in_code = not in_code
            buf += ch
        elif ch == "|" and not in_code:
            cells.append(buf.strip())
            buf = ""
        else:
            buf += ch
    cells.append(buf.strip())
    return cells


def is_delimiter(line):
    s = line.strip()
    if s.startswith("|"):
        s = s[1:]
    if s.endswith("|"):
        s = s[:-1]
    cells = [c.strip() for c in s.split("|")]
    return bool(cells) and all(DELIM_CELL_RE.match(c) for c in cells)


def parse_table(tlines):
    headers = split_row(tlines[0])
    rows = [split_row(l) for l in tlines[1:] if not is_delimiter(l)]
    return ("table", headers, rows)


def is_nested_marker(marker):
    # '+' is excluded so wrapped continuation lines such as '+ "Đã xem" ...'
    # are not mistaken for nested bullets.
    return marker in ("-", "*") or bool(re.match(r"\d+\.$", marker))


def parse_list(lines, i):
    n = len(lines)
    m = LIST_RE.match(lines[i])
    base_indent = len(m.group(1))
    ordered = m.group(2)[0].isdigit()
    items = []
    while i < n:
        line = lines[i]
        m = LIST_RE.match(line)
        if m and len(m.group(1)) == base_indent:
            items.append({"text": m.group(3).strip(), "sublist": None})
            i += 1
            continue
        if m and len(m.group(1)) > base_indent and is_nested_marker(m.group(2)):
            sub, i = parse_list(lines, i)
            if items:
                items[-1]["sublist"] = sub
            continue
        if line.strip() and (len(line) - len(line.lstrip())) > base_indent:
            if items:
                items[-1]["text"] += " " + line.strip()
            i += 1
            continue
        break
    return ("list", ordered, items), i


def parse_blocks(lines):
    blocks = []
    i = 0
    n = len(lines)
    in_code = False
    code_lang = ""
    code_lines = []
    while i < n:
        line = lines[i]
        if line.strip().startswith("```"):
            if not in_code:
                in_code = True
                code_lang = line.strip()[3:].strip()
                code_lines = []
                i += 1
                continue
            blocks.append(("code", code_lang, "\n".join(code_lines)))
            in_code = False
            i += 1
            continue
        if in_code:
            code_lines.append(line)
            i += 1
            continue
        if not line.strip():
            i += 1
            continue
        m = HEADING_RE.match(line)
        if m:
            blocks.append(("heading", len(m.group(1)), m.group(2).strip()))
            i += 1
            continue
        if HR_RE.match(line):
            blocks.append(("hr",))
            i += 1
            continue
        if line.lstrip().startswith(">"):
            qlines = []
            while i < n and lines[i].lstrip().startswith(">"):
                qlines.append(re.sub(r"^\s*>\s?", "", lines[i]).strip())
                i += 1
            blocks.append(("blockquote", " ".join(q for q in qlines if q)))
            continue
        if line.lstrip().startswith("|"):
            tlines = []
            while i < n and lines[i].lstrip().startswith("|"):
                tlines.append(lines[i].strip())
                i += 1
            blocks.append(parse_table(tlines))
            continue
        if is_list_start(lines, i):
            block, i = parse_list(lines, i)
            blocks.append(block)
            continue
        plines = []
        while i < n and lines[i].strip() and not is_block_start(lines, i):
            plines.append(lines[i].strip())
            i += 1
        if plines:
            blocks.append(("para", " ".join(plines)))
        else:
            i += 1
    return blocks


def split_preamble(blocks):
    for idx, b in enumerate(blocks):
        if b[0] == "hr":
            return blocks[:idx], blocks[idx + 1:]
    return blocks, []


# --------------------------------------------------------------------------
# renderer
# --------------------------------------------------------------------------
def map_level(md_level):
    """Markdown H1 is the cover title; body H2 sections become docx H1 and
    H3 subsections become docx H2, matching the previous builder's outline."""
    return min(3, max(1, md_level - 1))


def add_cover_title(doc, text):
    p = doc.add_heading("", level=1)
    p.alignment = WD_ALIGN_PARAGRAPH.CENTER
    parts = text.split("—", 1)
    if len(parts) == 2:
        r1 = p.add_run(parts[0].strip())
        r1.bold = True
        r1.font.size = Pt(26)
        r1.font.name = "Calibri"
        r1.font.color.rgb = RGBColor(0, 0, 0)
        p.add_run().add_break()
        r2 = p.add_run(parts[1].strip())
        r2.bold = True
        r2.font.size = Pt(34)
        r2.font.name = "Calibri"
        r2.font.color.rgb = RGBColor(0, 0, 0)
    else:
        r = p.add_run(text)
        r.bold = True
        r.font.size = Pt(26)
        r.font.name = "Calibri"
        r.font.color.rgb = RGBColor(0, 0, 0)
    return p


def add_cover_subtitle(doc, text):
    p = doc.add_heading("", level=2)
    p.alignment = WD_ALIGN_PARAGRAPH.CENTER
    r = p.add_run(text)
    r.italic = True
    r.font.size = Pt(14)
    r.font.name = "Calibri"
    r.font.color.rgb = RGBColor(0, 0, 0)
    return p


def build_cover(doc, preamble):
    for _ in range(4):
        doc.add_paragraph()
    table_count = 0
    for block in preamble:
        if block[0] == "heading":
            if block[1] == 1:
                add_cover_title(doc, block[2])
            else:
                add_cover_subtitle(doc, block[2])
                doc.add_paragraph()
                para(doc, TAGLINE, size=11, align=WD_ALIGN_PARAGRAPH.CENTER)
        elif block[0] == "table":
            if table_count == 0:
                for _ in range(3):
                    doc.add_paragraph()
            else:
                doc.add_paragraph()
            table(doc, block[1], block[2],
                  widths=COVER_WIDTHS.get(len(block[1])),
                  font_size=11 if table_count == 0 else 10)
            table_count += 1
        elif block[0] == "blockquote":
            note(doc, block[1])
        elif block[0] == "para":
            para(doc, block[1])


def render_list(doc, ordered, items, level):
    for item in items:
        if ordered:
            numbered(doc, item["text"], level=level)
        else:
            bullet(doc, item["text"], level=level)
        if item["sublist"]:
            render_list(doc, item["sublist"][1], item["sublist"][2], level + 1)


def render_block(doc, block):
    kind = block[0]
    if kind == "heading":
        level = map_level(block[1])
        {1: h1, 2: h2, 3: h3}[level](doc, block[2])
    elif kind == "para":
        p = doc.add_paragraph()
        add_runs(p, block[1])
    elif kind == "table":
        table(doc, block[1], block[2])
    elif kind == "code":
        code_box(doc, block[2])
    elif kind == "blockquote":
        note(doc, block[1])
    elif kind == "hr":
        pass
    elif kind == "list":
        render_list(doc, block[1], block[2], 1)


# --------------------------------------------------------------------------
# build
# --------------------------------------------------------------------------
def build(src=SRC, out=OUT):
    text = src.read_text(encoding="utf-8")
    blocks = parse_blocks(text.split("\n"))
    preamble, body = split_preamble(blocks)

    doc = Document()
    normal = doc.styles["Normal"]
    normal.font.name = "Calibri"
    normal.font.size = Pt(11)

    core = doc.core_properties
    core.title = "NexaChat — Báo cáo đồ án"
    core.subject = "Real-time Android Messaging Platform"
    core.author = "{{GROUP_NAME}}"

    build_cover(doc, preamble)
    page_break(doc)

    first = True
    for block in body:
        if block[0] == "heading" and map_level(block[1]) == 1 and not first:
            page_break(doc)
        render_block(doc, block)
        first = False

    out.parent.mkdir(parents=True, exist_ok=True)
    fd, tmp = tempfile.mkstemp(dir=str(out.parent), prefix=".report-", suffix=".tmp")
    os.close(fd)
    try:
        doc.save(tmp)
        os.replace(tmp, out)
    except BaseException:
        try:
            os.unlink(tmp)
        except OSError:
            pass
        raise
    return out, blocks


# --------------------------------------------------------------------------
# verification
# --------------------------------------------------------------------------
def iter_all_paragraphs(doc):
    for p in doc.paragraphs:
        yield p
    for t in doc.tables:
        for row in t.rows:
            for cell in row.cells:
                for p in cell.paragraphs:
                    yield p


def is_code_box(p):
    pPr = p._p.pPr
    if pPr is None:
        return False
    shd = pPr.find(qn("w:shd"))
    return shd is not None and shd.get(qn("w:fill")) == "F2F2F2"


def check_artefacts(doc):
    bad = []
    for p in iter_all_paragraphs(doc):
        if is_code_box(p):
            continue
        for run in p.runs:
            if run.font.name == MONO:
                continue  # literal code span content is not an artefact
            for art in ("**", "`", "|---"):
                if art in run.text:
                    bad.append((art, run.text[:90]))
    return bad


def verify(blocks, out):
    doc = Document(out)
    heads = [p for p in doc.paragraphs if p.style.name.startswith("Heading")]
    md_headings = [b for b in blocks if b[0] == "heading"]
    md_tables = [b for b in blocks if b[0] == "table"]
    ok = True

    def report(label, docx_n, md_n):
        nonlocal ok
        good = docx_n == md_n
        ok = ok and good
        print(f"{'PASS' if good else 'FAIL'} {label}: docx={docx_n} md={md_n}")

    report("headings", len(heads), len(md_headings))
    report("tables", len(doc.tables), len(md_tables))
    artefacts = check_artefacts(doc)
    ok = ok and not artefacts
    print(f"{'PASS' if not artefacts else 'FAIL'} markdown artefacts: "
          f"{len(artefacts)} found (literal code-span content excluded)")
    for art, snippet in artefacts[:10]:
        print(f"      {art!r} in {snippet!r}")
    print(f"re-opened OK: {len(doc.paragraphs)} paragraphs, "
          f"{len(doc.tables)} tables, {len(heads)} headings")
    return ok


def main(argv=None):
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except (AttributeError, ValueError):
        pass
    parser = argparse.ArgumentParser(
        description="Generate the NexaChat final report DOCX from docs/report.md.")
    parser.add_argument("--source", type=Path, default=SRC,
                        help="markdown source (default: docs/report.md)")
    parser.add_argument("--out", type=Path, default=OUT,
                        help="docx output (default: docs/report.docx)")
    args = parser.parse_args(argv)

    out, blocks = build(args.source, args.out)
    size = out.stat().st_size
    print(f"wrote {out} ({size:,} bytes)")
    if size < 20_000:
        raise SystemExit("FAIL: docx < 20KB")
    if not verify(blocks, out):
        raise SystemExit("FAIL: verification failed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
