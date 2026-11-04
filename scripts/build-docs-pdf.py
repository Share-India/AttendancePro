"""
Builds one PDF from the Markdown in docs/.

Run:  python -I scripts/build-docs-pdf.py

Markdown is rendered by marked.js inside headless Chrome, which is already on
this machine, rather than by pandoc or wkhtmltopdf, which are not. That avoids
installing a toolchain for something run occasionally, and Chrome's print
engine handles the tables these documents are mostly made of.

The output is a generated artefact. It is not committed; regenerate it when the
documents change.
"""
import json
import os
import pathlib
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
DOCS = ROOT / "docs"
OUT_PDF = DOCS / "employee-attendance-system-documentation.pdf"
OUT_HTML = ROOT / "scripts" / ".docs-bundle.html"

CHROME = pathlib.Path(r"C:\Program Files\Google\Chrome\Application\chrome.exe")

# Order matters: this is the reading order of the bundle.
ORDER = [
    ("README.md", "Overview and Setup", ROOT / "README.md"),
    ("docs/README.md", "Documentation Index", DOCS / "README.md"),
    ("01", "System Architecture", DOCS / "01-architecture.md"),
    ("02", "Project Structure", DOCS / "02-project-structure.md"),
    ("03", "Database Design", DOCS / "03-database-design.md"),
    ("04", "Workflows", DOCS / "04-workflows.md"),
    ("05", "API Design", DOCS / "05-api-design.md"),
    ("06", "Deployment", DOCS / "06-deployment.md"),
    ("07", "Exam Module", DOCS / "07-exam-module-plan.md"),
]

# Stated on the cover rather than left for the reader to discover. A bundle
# that looks complete but silently predates three modules is worse than one
# that says so on its first page.
COVERAGE_NOTE = """
These documents describe the platform as documented to date. Four pieces of
work are **not yet reflected** in them and are listed here so nobody reads an
absence as a statement that something does not exist:

- **Morning meetings** — four tables, twelve endpoints, five screens.
- **Weekly study exams** — nine tables; covered in document 07 but not in the
  schema, API or workflow documents.
- **Direct attendance override** — one permission, one endpoint, one screen.
- **Mobile layout work** for Android and iOS.

The schema document therefore describes roughly thirty tables where the
database now has about fifty, and the permission matrix omits twelve
permissions added by migrations V17, V19 and V20.
"""

CSS = """
@page { size: A4; margin: 18mm 16mm 20mm 16mm; }
/* A wide diagram squeezed into the portrait text column prints at a few
   pixels per label. Those get their own landscape page instead. */
@page diagramLandscape { size: A4 landscape; margin: 14mm; }
* { box-sizing: border-box; }
body {
  font-family: "Segoe UI", Inter, system-ui, sans-serif;
  font-size: 10.5pt; line-height: 1.55; color: #16181d; margin: 0;
  -webkit-print-color-adjust: exact; print-color-adjust: exact;
}
.cover { page-break-after: always; padding-top: 55mm; }
.cover h1 { font-size: 30pt; line-height: 1.15; margin: 0 0 6mm; color: #00236f; letter-spacing: -0.02em; }
.cover .sub { font-size: 13pt; color: #44485a; margin: 0 0 14mm; }
.cover .meta { font-size: 10pt; color: #6a6f80; }
.notice {
  margin-top: 14mm; padding: 6mm; border: 1px solid #e0b43a;
  background: #fdf6e3; border-radius: 3mm; font-size: 9.5pt;
}
.notice h2 { margin: 0 0 3mm; font-size: 12pt; color: #7a5c00; }
.notice ul { margin: 2mm 0; padding-left: 6mm; }
.toc { page-break-after: always; }
.toc h2 { font-size: 18pt; color: #00236f; margin: 0 0 6mm; }
.toc ol { padding-left: 6mm; }
.toc li { margin-bottom: 2.5mm; font-size: 11pt; }
.doc { page-break-before: always; }
.doc > .doc-title {
  font-size: 9pt; text-transform: uppercase; letter-spacing: 0.1em;
  color: #6a6f80; border-bottom: 1px solid #d6d9e0;
  padding-bottom: 2mm; margin-bottom: 6mm;
}
h1 { font-size: 20pt; color: #00236f; margin: 0 0 4mm; letter-spacing: -0.01em; }
h2 { font-size: 14.5pt; color: #0b2a6b; margin: 8mm 0 3mm; page-break-after: avoid; }
h3 { font-size: 12pt; color: #16181d; margin: 6mm 0 2mm; page-break-after: avoid; }
h4 { font-size: 10.5pt; margin: 4mm 0 2mm; page-break-after: avoid; }
p { margin: 0 0 3mm; }
ul, ol { margin: 0 0 3mm; padding-left: 6mm; }
li { margin-bottom: 1.2mm; }
a { color: #00479d; text-decoration: none; }
code {
  font-family: "IBM Plex Mono", Consolas, monospace; font-size: 9pt;
  background: #f1f3f7; padding: 0.4mm 1.2mm; border-radius: 1mm;
}
pre {
  background: #f6f8fb; border: 1px solid #e3e7ef; border-radius: 2mm;
  padding: 3mm; page-break-inside: avoid; margin: 0 0 4mm;
  /* Paper has no horizontal scrollbar: overflow-x would be clipped away
     silently, so long lines wrap instead. */
  white-space: pre-wrap; overflow-wrap: anywhere;
}
pre code { background: none; padding: 0; font-size: 8.5pt; line-height: 1.4; }
table {
  border-collapse: collapse; width: 100%; margin: 0 0 4mm;
  font-size: 9pt; page-break-inside: avoid;
}
th, td { border: 1px solid #d6d9e0; padding: 1.8mm 2.5mm; text-align: left; vertical-align: top; }
th { background: #eef1f6; font-weight: 600; }
tr:nth-child(even) td { background: #fafbfd; }
blockquote {
  margin: 0 0 4mm; padding: 2mm 4mm; border-left: 3px solid #00479d;
  background: #f6f8fb; color: #2d3142;
}
hr { border: 0; border-top: 1px solid #d6d9e0; margin: 6mm 0; }
img { max-width: 100%; }
.mermaid { text-align: center; margin: 0 0 4mm; page-break-inside: avoid; }
.mermaid svg { max-width: 100%; height: auto; }
.mermaid.landscape {
  page: diagramLandscape;
  page-break-before: always; page-break-after: always;
}
.diagram-note {
  margin-top: 2mm; font-size: 8pt; color: #6a6f80; font-style: italic;
}
"""

HTML = """<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<title>Employee Attendance and Leave Management System</title>
<script src="https://cdn.jsdelivr.net/npm/marked@12.0.2/marked.min.js"></script>
<script src="https://cdn.jsdelivr.net/npm/mermaid@10.9.1/dist/mermaid.min.js"></script>
<style>__CSS__</style>
</head>
<body>
<div class="cover">
  <h1>Employee Attendance &amp;<br>Leave Management System</h1>
  <p class="sub">Technical documentation</p>
  <p class="meta">__COUNT__ documents</p>
  <div class="notice" id="notice"></div>
</div>

<div class="toc">
  <h2>Contents</h2>
  <ol id="toc"></ol>
</div>

<div id="out"></div>

<script>
  const DOCS = __DOCS__;
  const NOTICE = __NOTICE__;

  marked.setOptions({ gfm: true, breaks: false });

  document.getElementById('notice').innerHTML =
    '<h2>Before you rely on this</h2>' + marked.parse(NOTICE);

  const toc = document.getElementById('toc');
  const out = document.getElementById('out');

  DOCS.forEach((doc) => {
    const li = document.createElement('li');
    li.textContent = doc.title;
    toc.appendChild(li);

    const wrap = document.createElement('div');
    wrap.className = 'doc';
    const label = document.createElement('div');
    label.className = 'doc-title';
    label.textContent = doc.label + ' \\u2014 ' + doc.title;
    wrap.appendChild(label);

    const body = document.createElement('div');
    body.innerHTML = marked.parse(doc.markdown);
    wrap.appendChild(body);
    out.appendChild(wrap);
  });

  document.title = 'Employee Attendance and Leave Management System';

  // Turn the mermaid code blocks into diagrams. A block that fails to parse
  // keeps its source on the page — a broken diagram must not become a blank
  // gap where a workflow used to be.
  (async () => {
    const blocks = [...document.querySelectorAll('#out pre > code.language-mermaid')];
    if (blocks.length && window.mermaid) {
      mermaid.initialize({ startOnLoad: false, theme: 'neutral', securityLevel: 'loose' });
      blocks.forEach((code, i) => {
        const pre = code.parentElement;
        const holder = document.createElement('div');
        holder.className = 'mermaid';
        // textContent, not innerHTML: marked escaped the <br/> in node labels
        // and mermaid needs them back as markup.
        holder.textContent = code.textContent;
        holder.dataset.fallback = pre.outerHTML;
        pre.replaceWith(holder);
      });
      for (const holder of document.querySelectorAll('.mermaid')) {
        try {
          await mermaid.run({ nodes: [holder], suppressErrors: false });
          window.__DIAGRAMS__ = (window.__DIAGRAMS__ || 0) + 1;
        } catch (err) {
          holder.outerHTML = holder.dataset.fallback;
          window.__DIAGRAM_FAILURES__ = (window.__DIAGRAM_FAILURES__ || 0) + 1;
        }
      }
    }
    fitDiagrams();
    window.__READY__ = true;
  })();

  // Scale every diagram to the larger of what a portrait or a landscape page
  // can hold, and say so when even that leaves it too small to read. A diagram
  // nobody can read is worse than an honest note, because it looks like
  // information.
  function fitDiagrams() {
    const PX_PER_MM = 96 / 25.4;
    const BOXES = [
      { cls: null,        w: 178, h: 259 },   // portrait, inside @page margins
      { cls: 'landscape', w: 269, h: 182 },   // landscape, inside 14mm margins
    ];
    const LABEL_PX = 16;          // mermaid's default label size
    const LEGIBLE_PX = 6.5;       // below this it is decoration, not information

    document.querySelectorAll('.mermaid svg').forEach((svg) => {
      const vb = (svg.getAttribute('viewBox') || '').split(/[\s,]+/).map(Number);
      const natW = vb[2], natH = vb[3];
      if (!natW || !natH) return;
      const wMm = natW / PX_PER_MM;
      const hMm = natH / PX_PER_MM;

      let best = null;
      for (const box of BOXES) {
        const scale = Math.min(1, box.w / wMm, box.h / hMm);
        if (!best || scale > best.scale) best = { ...box, scale };
      }

      svg.style.width = (wMm * best.scale).toFixed(1) + 'mm';
      svg.style.maxWidth = 'none';
      svg.style.height = 'auto';
      const holder = svg.closest('.mermaid');
      if (best.cls) holder.classList.add(best.cls);

      if (LABEL_PX * best.scale < LEGIBLE_PX) {
        const note = document.createElement('p');
        note.className = 'diagram-note';
        note.textContent =
          'Reduced to ' + Math.round(best.scale * 100) + '% to fit the page. ' +
          'Open the source document for a readable version.';
        holder.appendChild(note);
        window.__DIAGRAMS_TOO_SMALL__ = (window.__DIAGRAMS_TOO_SMALL__ || 0) + 1;
      }
    });
  }
</script>
</body>
</html>
"""


def main() -> int:
    if not CHROME.exists():
        print(f"Chrome not found at {CHROME}", file=sys.stderr)
        return 1

    docs = []
    for label, title, path in ORDER:
        if not path.exists():
            print(f"  skipped (missing): {path}")
            continue
        docs.append(
            {
                "label": label,
                "title": title,
                "markdown": path.read_text(encoding="utf-8"),
            }
        )
        print(f"  included: {path.relative_to(ROOT)}")

    if not docs:
        print("No documents found", file=sys.stderr)
        return 1

    html = (
        HTML.replace("__CSS__", CSS)
        .replace("__DOCS__", json.dumps(docs))
        .replace("__NOTICE__", json.dumps(COVERAGE_NOTE))
        .replace("__COUNT__", str(len(docs)))
    )
    OUT_HTML.write_text(html, encoding="utf-8")

    OUT_PDF.parent.mkdir(parents=True, exist_ok=True)
    if OUT_PDF.exists():
        OUT_PDF.unlink()

    subprocess.run(
        [
            str(CHROME),
            "--headless",
            "--disable-gpu",
            "--no-pdf-header-footer",
            # marked renders client side, so give the page time to run before
            # the print is taken. Without this the PDF is a blank cover.
            "--virtual-time-budget=20000",
            f"--print-to-pdf={OUT_PDF}",
            OUT_HTML.as_uri(),
        ],
        check=True,
        capture_output=True,
    )

    if not OUT_PDF.exists():
        print("Chrome did not produce a PDF", file=sys.stderr)
        return 1

    # --keep-html leaves the intermediate behind, which is the only way to
    # check what Chrome was actually given when a PDF comes out wrong.
    if "--keep-html" not in sys.argv:
        OUT_HTML.unlink(missing_ok=True)
    else:
        print(f"kept {OUT_HTML.relative_to(ROOT)}")
    print(f"\n{OUT_PDF.relative_to(ROOT)}  —  {OUT_PDF.stat().st_size // 1024} KB")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
