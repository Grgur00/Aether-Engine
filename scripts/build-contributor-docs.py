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
    ("FUNCTION-INDEX.md", "function-index.html", "Function reference index"),
    ("ENGINE-FUNCTIONS.md", "engine-functions.html", "Engine function reference"),
    ("PERSISTENT-INTERNALS.md", "persistent-internals.html", "Persistent internals"),
    ("SSTABLE-BLOCK-FUNCTIONS.md", "sstable-block-functions.html", "SSTable block functions"),
    ("SSTABLE-READ-FUNCTIONS.md", "sstable-read-functions.html", "SSTable reads and verification"),
    ("SSTABLE-BUILD-FUNCTIONS.md", "sstable-build-functions.html", "SSTable construction"),
    ("MANIFEST-VERSION-FUNCTIONS.md", "manifest-version-functions.html", "Manifest publication and versions"),
    ("MANIFEST-CODEC-FUNCTIONS.md", "manifest-codec-functions.html", "Manifest wire formats"),
    ("WAL-FUNCTIONS.md", "wal-functions.html", "WAL format functions"),
    ("MVCC-REFERENCE-FUNCTIONS.md", "mvcc-reference-functions.html", "Heap MVCC functions"),
    ("NATIVE-MEMTABLE-FUNCTIONS.md", "native-memtable-functions.html", "Native memtable functions"),
    ("NATIVE-REGION-FUNCTIONS.md", "native-region-functions.html", "Native regions and allocation"),
    ("NATIVE-RECORD-FUNCTIONS.md", "native-record-functions.html", "Native records and access"),
    ("COMPACTION-PLANNING-FUNCTIONS.md", "compaction-planning-functions.html", "Compaction planning"),
    ("LSM-ITERATOR-FUNCTIONS.md", "lsm-iterator-functions.html", "LSM iterators and reclamation"),
    ("READ-VIEW-FUNCTIONS.md", "read-view-functions.html", "Read views and snapshot ownership"),
    ("WRITE-PRESSURE-FUNCTIONS.md", "write-pressure-functions.html", "Write pressure functions"),
    ("ADMISSION-FUNCTIONS.md", "admission-functions.html", "Resource admission functions"),
    ("RELIABILITY-FUNCTIONS.md", "reliability-functions.html", "Reliability and fault injection"),
    ("BLOCK-CACHE-FUNCTIONS.md", "block-cache-functions.html", "Block cache functions"),
    ("FORMAT-CATALOG-FUNCTIONS.md", "format-catalog-functions.html", "Format catalogs and fixtures"),
    ("TYPED-KEY-FUNCTIONS.md", "typed-key-functions.html", "Typed keys and namespaces"),
    ("TYPED-VALUE-FUNCTIONS.md", "typed-value-functions.html", "Typed values and envelopes"),
    ("COLLECTION-SCHEMA-FUNCTIONS.md", "collection-schema-functions.html", "Collection metadata and compatibility"),
    ("CANONICAL-RECORD-FUNCTIONS.md", "canonical-record-functions.html", "Canonical record functions"),
    ("GENERATED-CONTAINER-FUNCTIONS.md", "generated-container-functions.html", "Generated containers and codec lookup"),
    ("TYPED-ADAPTER-FUNCTIONS.md", "typed-adapter-functions.html", "Embedded typed adapter functions"),
    ("SCHEMA-ANNOTATION-FUNCTIONS.md", "schema-annotation-functions.html", "Schema annotations and validation"),
    ("SCHEMA-RESOURCE-FUNCTIONS.md", "schema-resource-functions.html", "Schema locks and generated resources"),
    ("CODEC-GENERATION-FUNCTIONS.md", "codec-generation-functions.html", "Record codec generation functions"),
    ("FILESYSTEM-IDENTITY-FUNCTIONS.md", "filesystem-identity-functions.html", "Filesystem ownership and identity"),
    ("BACKUP-ARCHIVE-FUNCTIONS.md", "backup-archive-functions.html", "Backup manifests and archives"),
    ("BACKUP-RESTORE-FUNCTIONS.md", "backup-restore-functions.html", "Backup restore functions"),
    ("CONFIG-LOADING-FUNCTIONS.md", "config-loading-functions.html", "Configuration loading and validation"),
    ("CONFIG-CHANGE-FUNCTIONS.md", "config-change-functions.html", "Configuration reload and cluster policy"),
    ("TRAINING-IDENTITY-FUNCTIONS.md", "training-identity-functions.html", "Training artifact identity and policy"),
    ("TRAINING-CACHE-FUNCTIONS.md", "training-cache-functions.html", "Training cache operations and segments"),
    ("TRAINING-DRIVER-FUNCTIONS.md", "training-driver-functions.html", "Java training diagnostic drivers"),
    ("TRAINING-DAEMON-FUNCTIONS.md", "training-daemon-functions.html", "Training cache daemon lifecycle"),
    ("TRAINING-PROTOCOL-FUNCTIONS.md", "training-protocol-functions.html", "Training cache protocol and traces"),
    ("BULK-PUBLICATION-FUNCTIONS.md", "bulk-publication-functions.html", "Offline bulk publication"),
    ("PYTHON-CLIENT-FUNCTIONS.md", "python-client-functions.html", "Python client connections and retries"),
    ("PYTHON-CACHE-VALUE-FUNCTIONS.md", "python-cache-value-functions.html", "Python cache values and batches"),
    ("PYTHON-MAPPING-FUNCTIONS.md", "python-mapping-functions.html", "Python mapped views and tensors"),
    ("PYTHON-PIPELINE-FUNCTIONS.md", "python-pipeline-functions.html", "Python loading, workers and prefetch"),
    ("PYTHON-STORE-ADAPTER-FUNCTIONS.md", "python-store-adapter-functions.html", "Python store adapters and resources"),
    ("PYTHON-TRANSFORM-FUNCTIONS.md", "python-transform-functions.html", "Python transform cache and codecs"),
    ("PYTHON-DATASET-FUNCTIONS.md", "python-dataset-functions.html", "Python dataset integration"),
    ("PYTHON-LIFECYCLE-FUNCTIONS.md", "python-lifecycle-functions.html", "Python ML lifecycle and operations"),
    ("PYTHON-FRAMEWORK-FUNCTIONS.md", "python-framework-functions.html", "Python PyTorch and MONAI adapters"),
    ("PYTHON-PROVENANCE-STORE-FUNCTIONS.md", "python-provenance-store-functions.html", "Filesystem artifact publication"),
    ("PYTHON-PROVENANCE-WORKFLOW-FUNCTIONS.md", "python-provenance-workflow-functions.html", "Filesystem datasets and lineage"),
    ("PYTHON-PROVENANCE-DIAGNOSTIC-FUNCTIONS.md", "python-provenance-diagnostic-functions.html", "Filesystem validation and diagnostics"),
    ("H2-PROTOCOL-FUNCTIONS.md", "h2-protocol-functions.html", "H2 protocol and source freeze"),
    ("H2-EXECUTION-FUNCTIONS.md", "h2-execution-functions.html", "H2 execution and evidence"),
    ("H2-SESSION-FUNCTIONS.md", "h2-session-functions.html", "H2 amended session controller"),
    ("DALI-COMPARISON-FUNCTIONS.md", "dali-comparison-functions.html", "DALI workload and comparisons"),
    ("BULK-LAYOUT-FUNCTIONS.md", "bulk-layout-functions.html", "Bulk layout regression checks"),
    ("DATASET-PREPARATION-FUNCTIONS.md", "dataset-preparation-functions.html", "Dataset preparation and manifests"),
    ("TRANSFORM-EVOLUTION-FUNCTIONS.md", "transform-evolution-functions.html", "Transform evolution checks"),
    ("PREFLIGHT-UTILITY-FUNCTIONS.md", "preflight-utility-functions.html", "Preflight and smoke utilities"),
    ("SYSTEM-DRIVER-FUNCTIONS.md", "system-driver-functions.html", "Concurrency and fault drivers"),
    ("SYSTEM-ANALYSIS-FUNCTIONS.md", "system-analysis-functions.html", "Cache comparisons and systems exports"),
    ("CAMPAIGN-AUDIT-FUNCTIONS.md", "campaign-audit-functions.html", "Campaign audit and preservation"),
    ("BUILD-UTILITY-FUNCTIONS.md", "build-utility-functions.html", "Build and documentation utilities"),
    ("ROOT-BUILD-ARCHITECTURE.md", "root-build-architecture.html", "Root build architecture"),
    ("MODULE-BUILD-ARCHITECTURE.md", "module-build-architecture.html", "Module build topology"),
    ("EXAMPLE-APPLICATION-FUNCTIONS.md", "example-application-functions.html", "Example application functions"),
    ("FLUSH-DIAGNOSTIC-FUNCTIONS.md", "flush-diagnostic-functions.html", "Flush and compaction diagnostics"),
    ("CLI-INSPECTION-FUNCTIONS.md", "cli-inspection-functions.html", "CLI inspection and verification"),
    ("H2-ANALYSIS-FUNCTIONS.md", "h2-analysis-functions.html", "H2 analysis and figures"),
    ("RESEARCH-CAMPAIGN-FUNCTIONS.md", "research-campaign-functions.html", "Research campaigns and evidence"),
    ("RESEARCH-PROCESS-FUNCTIONS.md", "research-process-functions.html", "Research build and process ownership"),
    ("RESEARCH-LIFECYCLE-FUNCTIONS.md", "research-lifecycle-functions.html", "Research checkpoints and persistent service"),
    ("RESEARCH-MANIFEST-FUNCTIONS.md", "research-manifest-functions.html", "Research dataset version manifests"),
    ("RESEARCH-ADAPTER-FUNCTIONS.md", "research-adapter-functions.html", "Research MONAI comparison adapters"),
    ("LONGITUDINAL-WORKER-FUNCTIONS.md", "longitudinal-worker-functions.html", "Longitudinal stage worker"),
    ("LONGITUDINAL-RUNNER-FUNCTIONS.md", "longitudinal-runner-functions.html", "Longitudinal pilot and analysis"),
    ("BENCHMARK-DATA-FUNCTIONS.md", "benchmark-data-functions.html", "Benchmark sources and artifact payloads"),
    ("BENCHMARK-TRAINING-FUNCTIONS.md", "benchmark-training-functions.html", "Benchmark model and training"),
    ("BENCHMARK-BACKEND-FUNCTIONS.md", "benchmark-backend-functions.html", "Benchmark backend ownership"),
    ("BENCHMARK-METRICS-FUNCTIONS.md", "benchmark-metrics-functions.html", "Benchmark utilization and process metrics"),
    ("BENCHMARK-RUNNER-FUNCTIONS.md", "benchmark-runner-functions.html", "Benchmark run control and validity"),
    ("BENCHMARK-ACCOUNTING-FUNCTIONS.md", "benchmark-accounting-functions.html", "Benchmark lifecycle and comparisons"),
    ("BENCHMARK-AGGREGATION-FUNCTIONS.md", "benchmark-aggregation-functions.html", "Benchmark aggregation and statistics"),
    ("POPULATION-PROFILE-FUNCTIONS.md", "population-profile-functions.html", "Population diagnostics and traces"),
    ("CACHE-PROFILE-FUNCTIONS.md", "cache-profile-functions.html", "Cache request and JFR diagnostics"),
    ("JFR-ANALYSIS-FUNCTIONS.md", "jfr-analysis-functions.html", "JFR analysis and attribution"),
    ("BULK-DIAGNOSTIC-DRIVERS.md", "bulk-diagnostic-drivers.html", "Bulk JFR and compaction diagnostics"),
    ("VERIFICATION-CAMPAIGN-FUNCTIONS.md", "verification-campaign-functions.html", "Bulk verification campaign"),
    ("HIT-PATH-FUNCTIONS.md", "hit-path-functions.html", "Steady-state hit path diagnostics"),
    ("EXPERIMENT-OWNERSHIP-FUNCTIONS.md", "experiment-ownership-functions.html", "Experiment output and scratch ownership"),
    ("RPC-FRAME-FUNCTIONS.md", "rpc-frame-functions.html", "RPC frames and handshake"),
    ("RPC-API-FUNCTIONS.md", "rpc-api-functions.html", "RPC API and admission"),
    ("RPC-TRANSPORT-LIMITS.md", "rpc-transport-limits.html", "RPC transport limits and identity"),
    ("RPC-SERVER-FUNCTIONS.md", "rpc-server-functions.html", "Development RPC server and sockets"),
    ("RPC-CLIENT-FUNCTIONS.md", "rpc-client-functions.html", "Development RPC client and admission"),
    ("REMOTE-ROUTING-FUNCTIONS.md", "remote-routing-functions.html", "Remote client routing and retries"),
    ("REMOTE-FACADE-FUNCTIONS.md", "remote-facade-functions.html", "Remote client facade and typed collections"),
    ("CLIENT-PROTOCOL-FUNCTIONS.md", "client-protocol-functions.html", "Client protocol contracts"),
    ("CLIENT-CODEC-FUNCTIONS.md", "client-codec-functions.html", "Client message codecs"),
    ("CRYPTO-FUNCTIONS.md", "crypto-functions.html", "Encryption and key wrapping"),
    ("SECURITY-FUNCTIONS.md", "security-functions.html", "Authorization, audit and node identity"),
    ("REPLICATION-CONTRACT-FUNCTIONS.md", "replication-contract-functions.html", "Replication contracts and applied state"),
    ("REPLICATION-FORMAT-FUNCTIONS.md", "replication-format-functions.html", "Replicated log formats and codecs"),
    ("REPLICATED-STORE-FUNCTIONS.md", "replicated-store-functions.html", "Replicated log storage and recovery"),
    ("RAFT-CORE-FUNCTIONS.md", "raft-core-functions.html", "Raft contracts and progress"),
    ("RAFT-STORAGE-FUNCTIONS.md", "raft-storage-functions.html", "Raft vote and state-slot codecs"),
    ("CLUSTER-MEMBERSHIP-FUNCTIONS.md", "cluster-membership-functions.html", "Cluster membership and quorum"),
    ("CLUSTER-CODEC-FUNCTIONS.md", "cluster-codec-functions.html", "Cluster identity and configuration codecs"),
    ("OBSERVABILITY-FUNCTIONS.md", "observability-functions.html", "Observability and database metrics"),
    ("JAVA-BENCHMARK-RUNNER-FUNCTIONS.md", "java-benchmark-runner-functions.html", "Java benchmark profiles and execution"),
    ("JAVA-BENCHMARK-EVIDENCE-FUNCTIONS.md", "java-benchmark-evidence-functions.html", "Java benchmark evidence and baselines"),
    ("JAVA-BENCHMARK-COMPARISON-FUNCTIONS.md", "java-benchmark-comparison-functions.html", "Java benchmark comparisons and gates"),
    ("RELEASE-CERTIFICATION-FUNCTIONS.md", "release-certification-functions.html", "Release certification functions"),
    ("GRADLE-PLUGIN-FUNCTIONS.md", "gradle-plugin-functions.html", "Gradle plugin and build conventions"),
    ("WORKBENCH-WORKSPACE-FUNCTIONS.md", "workbench-workspace-functions.html", "Workbench workspace and inspectors"),
    ("WORKBENCH-TYPED-VALUE-FUNCTIONS.md", "workbench-typed-value-functions.html", "Workbench universal typed values"),
    ("WORKBENCH-RECORD-DIALOG-FUNCTIONS.md", "workbench-record-dialog-functions.html", "Workbench record dialog"),
    ("WORKBENCH-WINDOW-FUNCTIONS.md", "workbench-window-functions.html", "Workbench window and actions"),
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
        if token.type == "table_open" and source.name in {
                "PYTHON-TRANSFORM-FUNCTIONS.md", "PYTHON-DATASET-FUNCTIONS.md",
                "PYTHON-LIFECYCLE-FUNCTIONS.md", "PYTHON-FRAMEWORK-FUNCTIONS.md",
                "BULK-PUBLICATION-FUNCTIONS.md", "PYTHON-PIPELINE-FUNCTIONS.md",
                "PYTHON-STORE-ADAPTER-FUNCTIONS.md", "PYTHON-PROVENANCE-STORE-FUNCTIONS.md",
                "PYTHON-PROVENANCE-WORKFLOW-FUNCTIONS.md", "PYTHON-PROVENANCE-DIAGNOSTIC-FUNCTIONS.md",
                "H2-PROTOCOL-FUNCTIONS.md", "H2-EXECUTION-FUNCTIONS.md", "H2-ANALYSIS-FUNCTIONS.md",
                "H2-SESSION-FUNCTIONS.md",
                "DALI-COMPARISON-FUNCTIONS.md",
                "BULK-LAYOUT-FUNCTIONS.md",
                "DATASET-PREPARATION-FUNCTIONS.md",
                "TRANSFORM-EVOLUTION-FUNCTIONS.md",
                "PREFLIGHT-UTILITY-FUNCTIONS.md",
                "SYSTEM-DRIVER-FUNCTIONS.md",
                "SYSTEM-ANALYSIS-FUNCTIONS.md",
                "CAMPAIGN-AUDIT-FUNCTIONS.md",
                "BUILD-UTILITY-FUNCTIONS.md",
                "ROOT-BUILD-ARCHITECTURE.md",
                "MODULE-BUILD-ARCHITECTURE.md",
                "EXAMPLE-APPLICATION-FUNCTIONS.md",
                "FLUSH-DIAGNOSTIC-FUNCTIONS.md",
                "CLI-INSPECTION-FUNCTIONS.md",
                "TRAINING-DRIVER-FUNCTIONS.md",
                "RESEARCH-CAMPAIGN-FUNCTIONS.md", "RESEARCH-PROCESS-FUNCTIONS.md",
                "RESEARCH-LIFECYCLE-FUNCTIONS.md", "RESEARCH-MANIFEST-FUNCTIONS.md",
                "RESEARCH-ADAPTER-FUNCTIONS.md", "LONGITUDINAL-WORKER-FUNCTIONS.md",
                "LONGITUDINAL-RUNNER-FUNCTIONS.md", "BENCHMARK-DATA-FUNCTIONS.md",
                "BENCHMARK-TRAINING-FUNCTIONS.md", "BENCHMARK-BACKEND-FUNCTIONS.md",
                "BENCHMARK-METRICS-FUNCTIONS.md", "BENCHMARK-RUNNER-FUNCTIONS.md",
                "BENCHMARK-ACCOUNTING-FUNCTIONS.md", "BENCHMARK-AGGREGATION-FUNCTIONS.md",
                "POPULATION-PROFILE-FUNCTIONS.md", "CACHE-PROFILE-FUNCTIONS.md",
                "JFR-ANALYSIS-FUNCTIONS.md", "BULK-DIAGNOSTIC-DRIVERS.md",
                "VERIFICATION-CAMPAIGN-FUNCTIONS.md", "HIT-PATH-FUNCTIONS.md",
                "EXPERIMENT-OWNERSHIP-FUNCTIONS.md", "RPC-FRAME-FUNCTIONS.md", "RPC-API-FUNCTIONS.md",
                "RPC-TRANSPORT-LIMITS.md", "RPC-SERVER-FUNCTIONS.md", "RPC-CLIENT-FUNCTIONS.md",
                "CLIENT-PROTOCOL-FUNCTIONS.md",
                "CLIENT-CODEC-FUNCTIONS.md",
                "CRYPTO-FUNCTIONS.md",
                "SECURITY-FUNCTIONS.md",
                "REPLICATION-CONTRACT-FUNCTIONS.md",
                "REPLICATION-FORMAT-FUNCTIONS.md",
                "REPLICATED-STORE-FUNCTIONS.md",
                "RAFT-CORE-FUNCTIONS.md",
                "RAFT-STORAGE-FUNCTIONS.md",
                "CLUSTER-MEMBERSHIP-FUNCTIONS.md",
                "CLUSTER-CODEC-FUNCTIONS.md",
                "OBSERVABILITY-FUNCTIONS.md",
                "JAVA-BENCHMARK-RUNNER-FUNCTIONS.md",
                "JAVA-BENCHMARK-EVIDENCE-FUNCTIONS.md",
                "JAVA-BENCHMARK-COMPARISON-FUNCTIONS.md",
                "RELEASE-CERTIFICATION-FUNCTIONS.md",
                "GRADLE-PLUGIN-FUNCTIONS.md",
                "WORKBENCH-WORKSPACE-FUNCTIONS.md",
                "WORKBENCH-TYPED-VALUE-FUNCTIONS.md",
                "WORKBENCH-RECORD-DIALOG-FUNCTIONS.md",
                "WORKBENCH-WINDOW-FUNCTIONS.md",
                "REMOTE-ROUTING-FUNCTIONS.md", "REMOTE-FACADE-FUNCTIONS.md"}:
            token.attrSet("class", "docs-function-table")
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
  <link rel="stylesheet" href="../assets/contributors.css?v=20261008-1">
  <script src="../assets/site.js" defer></script>
  <script src="../assets/vendor/mermaid-10.9.5.min.js" defer></script>
  <script src="../assets/contributors.js?v=20261007-3" defer></script>
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
