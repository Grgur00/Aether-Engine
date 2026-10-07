import importlib.util
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("contributor_docs", ROOT / "scripts/build-contributor-docs.py")
docs = importlib.util.module_from_spec(spec)
spec.loader.exec_module(docs)


def test_onboarding_links_stay_on_website():
    source = docs.SOURCE / "CODE-TOUR.md"
    assert docs.link_target("MODULE-GUIDE.md", source) == "modules.html"
    assert docs.link_target("STORAGE-ENGINE.md#3-online-write-path", source) == "storage.html#3-online-write-path"
    assert docs.link_target("#local-section", source) == "#local-section"


def test_source_links_keep_repository_and_fragment():
    source = docs.SOURCE / "CODE-TOUR.md"
    assert docs.link_target("../../settings.gradle.kts", source) == docs.REPO + "settings.gradle.kts"
    assert docs.link_target("https://example.com/page#test", source) == "https://example.com/page#test"


def test_every_generated_guide_matches_current_markdown():
    for source, target, title in docs.PAGES:
        assert (docs.OUTPUT / target).read_text(encoding="utf-8") == docs.page(source, target, title)


def test_module_guide_contains_all_49_modules():
    body, headings = docs.render(docs.SOURCE / "MODULE-GUIDE.md")
    assert body.count("<tr>") == 52  # Three table headers plus 49 module rows.
    assert any(anchor == "remote-and-distributed-foundations" for anchor, _ in headings)


def test_diagrams_have_readable_fallback_and_local_renderer():
    diagram_count = 0
    for source, target, title in docs.PAGES:
        html = docs.page(source, target, title)
        diagram_count += html.count('class="docs-diagram"')
        assert 'src="../assets/vendor/mermaid-10.9.5.min.js"' in html
        assert html.count('class="docs-diagram"') == html.count('class="docs-diagram-source" open')
    assert diagram_count == 22
    assert (ROOT / "website/assets/vendor/mermaid-10.9.5.min.js").is_file()


def test_mermaid_source_is_escaped():
    html = docs.parser.render('```mermaid\nflowchart TD\nA["<script>alert(1)</script>"]\n```')
    assert '<script>' not in html
    assert '&lt;script&gt;' in html
    assert '<details class="docs-diagram-source" open>' in html
