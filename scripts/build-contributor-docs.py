"""Render source-backed onboarding Markdown into the static website."""
from html import escape
from pathlib import Path
import re
from urllib.parse import urlsplit

from markdown_it import MarkdownIt

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "Docs/onboarding"
OUTPUT = ROOT / "website/contributors"
REPO = "https://github.com/Grgur00/Aether-Engine/blob/main/"
PAGES = [
    ("README.md", "index.html", "Overview"),
    ("GETTING-STARTED.md", "getting-started.html", "Getting started"),
    ("ARCHITECTURE.md", "architecture.html", "Architecture"),
    ("MODULE-GUIDE.md", "modules.html", "Module guide"),
    ("CODE-TOUR.md", "code-tour.html", "Code tour"),
    ("STORAGE-ENGINE.md", "storage.html", "Storage engine"),
    ("TYPED-API-AND-SCHEMAS.md", "schemas.html", "Typed API and schemas"),
    ("TRAINING-CACHE-AND-PYTHON.md", "python.html", "Python and training cache"),
    ("TESTING-AND-CONTRIBUTING.md", "testing.html", "Testing and contributing"),
    ("OPERATIONS-AND-DEBUGGING.md", "operations.html", "Operations and debugging"),
    ("EXPERIMENTS-AND-PROFILING.md", "research.html", "Research tooling"),
    ("GLOSSARY.md", "glossary.html", "Glossary"),
]
TARGETS = {SOURCE / source: output for source, output, _ in PAGES}
parser = MarkdownIt("commonmark", {"html": False}).enable("table")
default_fence = parser.renderer.rules["fence"]


def render_fence(tokens, index, options, env):
    token = tokens[index]
    if token.info.strip() != "mermaid":
        return default_fence(tokens, index, options, env)
    source = escape(token.content)
    return ('<figure class="docs-diagram" data-diagram-state="pending">'
            '<div class="docs-diagram-view" tabindex="0" aria-label="Diagram" hidden></div>'
            '<details class="docs-diagram-source" open><summary>Diagram source</summary>'
            f'<pre><code class="language-mermaid">{source}</code></pre></details></figure>\n')


parser.renderer.rules["fence"] = render_fence


def slug(text):
    return re.sub(r"[^\w -]", "", text.lower()).replace(" ", "-")


def link_target(href, source):
    parsed = urlsplit(href)
    if parsed.scheme or not parsed.path:
        return href
    path = (source.parent / parsed.path).resolve()
    suffix = ("#" + parsed.fragment) if parsed.fragment else ""
    if path in TARGETS:
        return TARGETS[path] + suffix
    relative = path.relative_to(ROOT).as_posix()
    if relative == "website/docs/index.html":
        return "../docs/index.html" + suffix
    return REPO + relative + suffix


def render(source):
    tokens = parser.parse(source.read_text(encoding="utf-8"))
    headings = []
    seen = {}
    for index, token in enumerate(tokens):
        if token.type == "heading_open":
            inline = tokens[index + 1]
            text = "".join(child.content for child in inline.children or []
                           if child.type in {"text", "code_inline"})
            base = slug(text)
            count = seen.get(base, 0)
            seen[base] = count + 1
            anchor = base if not count else f"{base}-{count}"
            token.attrSet("id", anchor)
            if token.tag == "h2":
                headings.append((anchor, text))
        for child in token.children or []:
            if child.type == "link_open":
                child.attrSet("href", link_target(child.attrGet("href"), source))
    return parser.renderer.render(tokens, parser.options, {}), headings


def page(source_name, output, title):
    source = SOURCE / source_name
    body, headings = render(source)
    nav = ""
    for _, target, label in PAGES:
        current = ' aria-current="page"' if target == output else ""
        nav += f'<a href="{target}"{current}>{escape(label)}</a>'
    toc = "".join(f'<a href="#{anchor}">{escape(label)}</a>' for anchor, label in headings)
    return f'''<!doctype html>
<!-- Generated from Docs/onboarding/{source_name}; run scripts/build-contributor-docs.py. -->
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <meta name="description" content="{escape(title)}: source-backed contributor documentation for Aether Engine.">
  <title>{escape(title)} | Aether Engine Contributors</title>
  <link rel="icon" href="../assets/favicon.svg" type="image/svg+xml">
  <link rel="stylesheet" href="../assets/site.css">
  <link rel="stylesheet" href="../assets/docs.css">
  <link rel="stylesheet" href="../assets/contributors.css">
  <script src="../assets/site.js" defer></script>
  <script src="../assets/vendor/mermaid-10.9.5.min.js" defer></script>
  <script src="../assets/contributors.js" defer></script>
</head>
<body class="docs-body contributor-body">
  <a class="skip-link" href="#docs-content">Skip to documentation</a>
  <header class="site-header docs-header" data-header>
    <div class="header-inner">
      <a class="brand" href="../index.html"><img class="brand-mark" src="../assets/favicon.svg" alt="" width="38" height="38"><span>Aether Engine</span></a>
      <span class="docs-label">Contributor handbook</span>
      <button class="docs-menu-button" type="button" aria-expanded="false" aria-controls="docs-sidebar">Browse guides</button>
      <nav class="docs-top-nav" aria-label="Project navigation"><a href="../index.html">Home</a><a href="../docs/index.html">API docs</a><a href="https://github.com/Grgur00/Aether-Engine">Source</a></nav>
    </div>
  </header>
  <div class="docs-layout">
    <aside id="docs-sidebar" class="docs-sidebar" aria-label="Contributor navigation">
      <div class="docs-search"><label for="guide-search">Filter guides</label><input id="guide-search" type="search" placeholder="Find a guide" autocomplete="off"></div>
      <nav class="docs-nav-group"><strong>Working on Aether</strong>{nav}</nav>
      <p class="docs-nav-empty" hidden>No matching guides</p>
      <div class="docs-sidebar-footer"><a href="{REPO}Docs/onboarding/{source_name}">Edit this guide on GitHub</a><span>Current source, not release certification</span></div>
    </aside>
    <main id="docs-content" class="docs-content contributor-content">
      <p class="docs-kicker">Aether Engine / Contributor handbook</p>
      {body}
      <footer class="docs-end"><a href="index.html">All contributor guides</a><a href="../docs/index.html">Public API documentation</a></footer>
    </main>
    <aside class="docs-toc" aria-label="On this page"><strong>On this page</strong><nav>{toc}</nav></aside>
  </div>
</body>
</html>
'''


def main():
    OUTPUT.mkdir(parents=True, exist_ok=True)
    for source, target, title in PAGES:
        (OUTPUT / target).write_text(page(source, target, title), encoding="utf-8", newline="\n")
    print(f"Rendered {len(PAGES)} contributor guides from onboarding Markdown")


if __name__ == "__main__":
    main()
