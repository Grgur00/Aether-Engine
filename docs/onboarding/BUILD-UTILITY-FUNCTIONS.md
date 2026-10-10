# Build and Documentation Utility Functions

[Function index](FUNCTION-INDEX.md) | [Gradle plugin](GRADLE-PLUGIN-FUNCTIONS.md) | [Research process ownership](RESEARCH-PROCESS-FUNCTIONS.md)

Sources: [build-contributor-docs.py](../../scripts/build-contributor-docs.py),
[check-maven-publication.py](../../scripts/check-maven-publication.py),
[build_paper.py](../../scripts/build_paper.py), and
[kaggle_studio_windows.py](../../scripts/kaggle_studio_windows.py).
This guide covers **13 explicit functions across four complete files**.
Table qualifiers normalize hyphens in script filenames to underscores; they are
reference labels, not importable module paths.

## Architecture

```text
onboarding Markdown + explicit PAGES registry
    -> MarkdownIt tokens -> headings/links/table classes -> static HTML shell
staged Maven repository -> expected artifacts + POM metadata -> error list
paper TeX/Bib sources -> Tectonic draft PDFs/logs -> optional page rendering
    -> build receipt, never submission certification
Kaggle Studio arguments -> Windows quote adaptation -> local Kaggle executable
```

These tools do not alter database storage behavior or run H2 as a side effect
of documentation generation. Publication validation is inspection, not signing
or upload. Paper compilation and remote CLI forwarding are separate commands
with separate dependencies and risks.

## Documentation Renderer

| Function | Behavior | Boundaries |
| --- | --- | --- |
| `build_contributor_docs.render_fence(tokens, index, options, env)` | Delegates non-Mermaid fences to the original renderer. For exact stripped info `mermaid`, HTML-escapes source and emits a pending figure, hidden focusable diagram view and open source details. | Does not render SVG itself; local browser JavaScript owns diagram rendering. Source fallback remains available. Additional info words prevent Mermaid recognition. |
| `build_contributor_docs.slug(text)` | Lowercases text, removes characters outside word/space/hyphen and replaces each space with hyphen. | Not a universal GitHub slug implementation; repeated spaces remain repeated hyphens, underscores/Unicode word characters survive. Collision suffixes are assigned by `render`, not here. |
| `build_contributor_docs.link_target(href, source)` | Leaves URLs with schemes and pathless anchors unchanged. Resolves other links relative to source; registered Markdown becomes local guide HTML, public API index gets a local relative link, and other repository paths become GitHub main-branch links. Preserves fragment. | Query strings on rewritten paths are not preserved. Unregistered Markdown links go to source on GitHub. Paths outside repo raise at `relative_to`; no filesystem-existence check. Remote main links are not frozen source-commit permalinks. |
| `build_contributor_docs.render(source)` | Parses UTF-8 Markdown with raw HTML disabled and tables enabled. Adds function-table class for an explicit filename allowlist, assigns unique heading IDs from text/code children, collects H2 table-of-contents entries and rewrites inline links. Returns HTML body and headings. | All headings get IDs, only H2 enters TOC. Images are not rewritten as links. Page allowlist controls table styling, not semantic source coverage. Token parsing/rendering is delegated to MarkdownIt; this function does not validate documented behavior. |
| `build_contributor_docs.page(source_name, output, title)` | Renders source, builds full PAGES navigation with current-page marker and H2 TOC, and returns the static HTML document with shared CSS/scripts, local Mermaid asset, search/mobile navigation hooks and source-edit link. | Does not write file. Source body is renderer output; labels/title are escaped. Navigation is shared across every page. Asset version query strings and registry are explicit constants, not auto-discovered dependencies. |
| `build_contributor_docs.main()` | Creates contributor output directory and writes every registered page as UTF-8/LF HTML, printing guide count. | Direct per-file overwrite; no atomic whole-site transaction, stale-page deletion or sitemap regeneration. Failure can leave mixed old/new pages. Source registration, link audit and browser verification are separate responsibilities. |

`PAGES` defines both generation and source-to-local-link mapping; simply creating
a Markdown file does not publish it. The renderer's global parser installs the
custom fence hook once at module import. Generated HTML should be regenerated
from Markdown, not manually edited. Source equality tests catch stale generated
pages, while browser tests catch layout/diagram failures that equality alone
cannot detect.

## Maven Publication Checker

| Function | Behavior | Boundaries |
| --- | --- | --- |
| `check_maven_publication.text(root, path)` | Uses namespaced XML `findtext` and returns stripped text or empty string. | Only the Maven POM namespace is recognized. Missing/empty/whitespace content becomes empty; no inheritance/property expansion. |
| `check_maven_publication.validate_module(repository, module, version)` | Checks nonempty POM and Gradle module metadata plus binary/sources/javadoc JARs for non-BOM modules. Parses existing POM; requires coordinates/name/description/URL/license/developer/SCM, exact group/artifact/version and BOM `pom` packaging. Rejects dependencies in the project group whose artifact is outside the explicit publishable module set. Returns accumulated errors. | Missing POM returns artifact errors; malformed XML raises rather than accumulating a parse error. Does not inspect JAR/module contents, signatures/checksums, dependency versions/scopes or external dependency resolvability. Parent-inherited fields do not satisfy direct field checks. No upload. |
| `check_maven_publication.validate_plugin_marker(repository, version)` | Requires nonempty plugin marker POM, `pom` packaging, license and first implementation dependency matching group, `aether-gradle-plugin` and supplied version. Returns errors. | Does not validate marker's own coordinates/name/SCM or extra dependencies. Does not require marker JAR/module files. Malformed XML propagates. |
| `check_maven_publication.main()` | Parses version and staging repository (default `build/staging-deploy`), validates sorted explicit module set and marker, prints all errors to stderr and returns 1, or prints coordinate count/version and returns 0. | Module scope is the hardcoded publishable set, not every repo module. Script wrapper converts return code to process exit. Does not build, sign, publish or verify Maven Central acceptance. |

## Paper and Remote CLI

| Function | Behavior | Boundaries |
| --- | --- | --- |
| `build_paper.main()` | Chooses explicit Tectonic, PATH executable or workspace toolchain; obtains compiler version. Compiles manuscript then supplement with untrusted mode, retained logs/intermediates and workspace TeX cache; optional offline mode uses cached resources. Requires PDF header, collects selected warning lines, optionally renders 110-dpi pages/extracted text with PyMuPDF, and records PDF/source hashes and compiler/options in build JSON. | Error advises Tectonic 0.17.0 but does not enforce that version. Warnings do not fail build. No subprocess timeout. Source inventory covers top-level `.tex`/`.bib`, not all figures/transitive inputs. Existing PDFs/reports/pages may remain or be overwritten. Partial outputs survive failure; final receipt needs both documents. Always records `submissionReady=False`. |
| `kaggle_studio_windows.decode_arguments(arguments)` | If the first argument starts with apostrophe, joins tokens and POSIX-shlex splits them to undo Studio quoting; otherwise returns original arguments. | Extension-specific heuristic, not a general Windows command parser. Malformed quoting raises. Availability `--version` passes through. No shell execution or command authorization. |
| `kaggle_studio_windows.main()` | Resolves workspace `build/kaggle-venv/Scripts/kaggle.exe`, decodes arguments only on Windows, invokes executable with argument array and `shell=False`, and returns child exit code. | No executable existence check, timeout, credential validation or upload confirmation. Non-Windows skips decoding but still targets Windows executable path. Use as extension `cliPath`, not the project's general task runner. |

Paper subprocess output goes to each document's command log. Optional rendering
imports PyMuPDF only when requested and is inspection output, not automated visual
approval. A compiler's success/PDF prefix does not certify citations, measurements,
page limits or author approval. The build tool copies its child environment before
setting cache path, rather than changing the parent's environment.

## Verification

AST inventory tests match all 13 normalized file-qualified declarations. Existing
renderer tests cover generated-source equality, links, headings and fence output;
browser checks cover guide containment/contrast/navigation. Offline utility
contracts check POM metadata/missing artifacts and extension quote decoding. No
Maven upload, TeX compilation or Kaggle command is invoked by those tests. Build
system configuration and whole-repo semantic completeness still require their
own audit beyond this function inventory.
