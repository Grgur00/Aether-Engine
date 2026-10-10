import ast
import importlib.util
import csv
import io
from pathlib import Path
import re
import shutil
import subprocess

import pytest


ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("contributor_docs", ROOT / "scripts/build-contributor-docs.py")
docs = importlib.util.module_from_spec(spec)
spec.loader.exec_module(docs)


def test_cli_inspection_reference_covers_scoped_compiler_inventory():
    java = shutil.which("java")
    if not java:
        pytest.skip("JDK needed for compiler-tree inventory")
    source = ROOT / "modules/aether-tools/src/main/java/io/aetherdb/tools/AetherCli.java"
    result = subprocess.run([java, str(ROOT / "scripts/InventoryJavaFunctions.java"), str(source)],
                            check=True, capture_output=True, text=True, encoding="utf-8")
    declarations = [line.split("\t") for line in result.stdout.splitlines()]
    expected = {f"AetherCli.{name}" for name in
                ("AetherCli", "main", "run", "inspect", "readReport", "inspectWals",
                 "verifyNoDuplicateInternalIdentities", "printText", "printJson")}
    expected.update(("AetherCli.Arguments.parse",
                     "AetherCli.LockUnavailableException.LockUnavailableException"))
    available = {f"{fields[1]}.{fields[2]}" for fields in declarations}
    assert len(expected) == 11
    assert expected <= available
    text = (docs.SOURCE / "CLI-INSPECTION-FUNCTIONS.md").read_text(encoding="utf-8")
    documented = re.findall(r"^\| `([\w.]+)\(", text, re.MULTILINE)
    assert sorted(documented) == sorted(expected)
    for boundary in ("not a cheap header-only", "does not obtain a coherent snapshot",
                     "not itself streaming", "always displays METADATA",
                     "not executed in this documentation batch"):
        assert boundary in " ".join(text.split())


def test_training_driver_reference_covers_complete_compiler_inventory():
    java = shutil.which("java")
    if not java:
        pytest.skip("JDK needed for compiler-tree inventory")
    root = ROOT / "modules/aether-training-cache/src/main/java/io/aetherdb/training/cache"
    files = [root / f"{name}.java" for name in
             ("HitPathBenchmark", "TrainingCacheBenchmark", "TrainingCacheCrashCampaign")]
    result = subprocess.run([java, str(ROOT / "scripts/InventoryJavaFunctions.java"), *map(str, files)],
                            check=True, capture_output=True, text=True, encoding="utf-8")
    declarations = [line.split("\t") for line in result.stdout.splitlines()]
    assert len(declarations) == 24
    text = (docs.SOURCE / "TRAINING-DRIVER-FUNCTIONS.md").read_text(encoding="utf-8")
    expected = [f"{fields[1]}.{fields[2]}" for fields in declarations]
    documented = re.findall(r"^\| `([\w.]+)\(", text, re.MULTILINE)
    assert sorted(documented) == sorted(expected)
    for boundary in ("accepts absence", "without timeout", "not atomic",
                     "without reading every byte", "not model-training"):
        assert boundary in " ".join(text.split())


def test_flush_diagnostic_reference_covers_complete_compiler_inventory():
    java = shutil.which("java")
    if not java:
        pytest.skip("JDK needed for compiler-tree inventory")
    source = ROOT / "modules/aether-engine/src/main/java/io/aetherdb/engine/FlushDiagnostics.java"
    result = subprocess.run([java, str(ROOT / "scripts/InventoryJavaFunctions.java"), str(source)],
                            check=True, capture_output=True, text=True, encoding="utf-8")
    declarations = [line.split("\t") for line in result.stdout.splitlines()]
    assert len(declarations) == 46
    text = (docs.SOURCE / "FLUSH-DIAGNOSTIC-FUNCTIONS.md").read_text(encoding="utf-8")
    expected = [f"{fields[1]}.{fields[2]}" for fields in declarations]
    documented = re.findall(r"^\| `([\w.]+)\(", text, re.MULTILINE)
    assert sorted(documented) == sorted(expected)


@pytest.mark.parametrize("relative,guide,count", [
    ("modules/aether-api/src/main/java/io/aetherdb/api/WriteResult.java", "ENGINE-FUNCTIONS.md", 2),
    ("modules/aether-api/src/main/java/io/aetherdb/api/AetherCursor.java", "ENGINE-FUNCTIONS.md", 5),
    ("modules/aether-api/src/main/java/io/aetherdb/api/exceptions/SnapshotLimitExceededException.java", "ENGINE-FUNCTIONS.md", 1),
    ("modules/aether-sstable/src/main/java/io/aetherdb/sstable/block/BlockKind.java", "SSTABLE-BLOCK-FUNCTIONS.md", 3),
    ("modules/aether-training-cache/src/main/java/io/aetherdb/training/cache/TrainingCacheFaultHooks.java", "TRAINING-CACHE-FUNCTIONS.md", 2),
])
def test_main_source_omission_followups_cover_compiler_declarations(relative, guide, count):
    java = shutil.which("java")
    if not java:
        pytest.skip("JDK needed for compiler-tree inventory")
    result = subprocess.run(
        [java, str(ROOT / "scripts/InventoryJavaFunctions.java"), str(ROOT / relative)],
        check=True, capture_output=True, text=True, encoding="utf-8")
    declarations = [line.split("\t") for line in result.stdout.splitlines() if "\t" in line]
    assert len(declarations) == count
    text = (docs.SOURCE / guide).read_text(encoding="utf-8")
    for _, owner, method, *_ in declarations:
        assert f"`{owner}.{method}(" in text


def test_example_reference_covers_every_explicit_compiler_declaration():
    java = shutil.which("java")
    if not java:
        pytest.skip("JDK needed for compiler-tree inventory")
    files = sorted((ROOT / "examples").glob("*/src/main/java/**/*.java"))
    assert len(files) == 9
    result = subprocess.run(
        [java, str(ROOT / "scripts/InventoryJavaFunctions.java"), *map(str, files)],
        check=True, capture_output=True, text=True, encoding="utf-8")
    declarations = []
    for line in result.stdout.splitlines():
        fields = line.split("\t")
        if len(fields) >= 5:
            declarations.append(f"{fields[1]}.{fields[2]}")
    assert len(declarations) == 44
    guide = (docs.SOURCE / "EXAMPLE-APPLICATION-FUNCTIONS.md").read_text(encoding="utf-8")
    entries = re.findall(r"^\| `([\w.]+)\(", guide, re.MULTILINE)
    assert sorted(entries) == sorted(declarations)
    for boundary in ("Implicit and Generated Behavior", "read-check-write",
                     "event thread", "Partial existing data", "not a snapshot join"):
        assert boundary.lower() in guide.lower()


def test_module_build_dependency_inventory_matches_every_project():
    guide = (docs.SOURCE / "MODULE-BUILD-ARCHITECTURE.md").read_text(encoding="utf-8")
    rows = dict(re.findall(r"^\| `(aether-[\w-]+)` \| ([^|]+) \|$", guide, re.MULTILINE))
    projects = list((ROOT / "modules").glob("*/build.gradle.kts")) + list(
        (ROOT / "examples").glob("*/build.gradle.kts"))
    assert len(projects) == 51
    assert set(rows) == {path.parent.name for path in projects}
    for path in projects:
        source = path.read_text(encoding="utf-8")
        actual = {}
        for scope, target in re.findall(r'(\w+)\(project\(":modules:aether-([\w-]+)"\)\)', source):
            actual.setdefault(scope, set()).add(target)
        documented = {}
        declaration = rows[path.parent.name].strip()
        if declaration != "none":
            for group in declaration.split(";"):
                scope, targets = group.strip().split(":", 1)
                documented[scope] = {target.strip() for target in targets.split(",")}
        assert documented == actual, path


def test_module_build_main_classes_and_specialized_actions_are_documented():
    guide = (docs.SOURCE / "MODULE-BUILD-ARCHITECTURE.md").read_text(encoding="utf-8")
    for root in (ROOT / "modules", ROOT / "examples"):
        for path in root.glob("*/build.gradle.kts"):
            source = path.read_text(encoding="utf-8")
            for main_class in re.findall(r'mainClass = "([^"]+)"', source):
                assert f"`{main_class}`" in guide
    for name in ("AETHER_JFR", "aether.benchmark.jfr.path", "Implementation-Version",
                 "org.hdrhistogram:HdrHistogram:2.2.2", "java-test-fixtures",
                 "-Xlint:-preview", "testAnnotationProcessor"):
        assert name in guide
    bom = (ROOT / "modules/aether-bom/build.gradle.kts").read_text(encoding="utf-8")
    assert len(re.findall(r'api\(project\(', bom)) == 14
    assert "14 API constraints" in guide


def test_module_build_conventions_and_publication_scope_match_root():
    projects = list((ROOT / "modules").glob("*/build.gradle.kts")) + list(
        (ROOT / "examples").glob("*/build.gradle.kts"))
    sources = {path.parent.name: path.read_text(encoding="utf-8") for path in projects}
    root = (ROOT / "build.gradle.kts").read_text(encoding="utf-8")
    public_block = root.split('tasks.register("stageMavenCentral")', 1)[0]
    public = set(re.findall(r'"(aether-[\w-]+)"', public_block))
    published = {name for name, source in sources.items() if 'id("aether.publishing")' in source}
    assert published == public
    assert sum('id("aether.java-library")' in source for source in sources.values()) == 44
    assert sum('id("aether.java-application")' in source for source in sources.values()) == 5
    assert sum('`java-platform`' in source for source in sources.values()) == 1
    assert sum('`java-gradle-plugin`' in source for source in sources.values()) == 1


def test_training_cache_build_actions_are_documented():
    source = (ROOT / "modules/aether-training-cache/build.gradle.kts").read_text(encoding="utf-8")
    guide = (docs.SOURCE / "ROOT-BUILD-ARCHITECTURE.md").read_text(encoding="utf-8")
    tasks = re.findall(r'tasks.register(?:<JavaExec>)?\("([^"]+)"\)', source)
    assert len(tasks) == 6
    for task in tasks:
        assert f"`{task}`" in guide
    helpers = re.findall(r"fun (\w+)\(", source)
    assert helpers == ["digest"]
    assert "`digest(file)`" in guide
    for field in ("aether-java-build-v1", "classpathSha256", "buildJavaVersion"):
        assert field in source and field in guide


def test_training_cache_task_defaults_are_documented():
    source = (ROOT / "modules/aether-training-cache/build.gradle.kts").read_text(encoding="utf-8")
    guide = (docs.SOURCE / "ROOT-BUILD-ARCHITECTURE.md").read_text(encoding="utf-8")
    defaults = re.findall(r'gradleProperty\("([^"]+)"\)\.orElse\("([^"]+)"\)', source)
    assert len(defaults) == 13
    for name, value in defaults:
        assert name in guide and value in guide


def test_onboarding_links_stay_on_website():
    source = docs.SOURCE / "CODE-TOUR.md"
    assert docs.link_target("MODULE-GUIDE.md", source) == "modules.html"
    assert docs.link_target("STORAGE-ENGINE.md#3-online-write-path", source) == "storage.html#3-online-write-path"
    assert docs.link_target("#local-section", source) == "#local-section"


ML_FUNCTION_REFERENCES = [
    (["transform_cache.py", "identity.py", "codecs.py"], "PYTHON-TRANSFORM-FUNCTIONS.md"),
    (["dataset.py"], "PYTHON-DATASET-FUNCTIONS.md"),
    (["config.py", "lifecycle.py", "namespace.py", "metrics.py", "cli.py"],
     "PYTHON-LIFECYCLE-FUNCTIONS.md"),
    (["torch/loader.py", "torch/worker.py", "monai/dataset.py", "monai/identity.py"],
     "PYTHON-FRAMEWORK-FUNCTIONS.md"),
]


@pytest.mark.parametrize("files, guide", ML_FUNCTION_REFERENCES)
def test_ml_references_have_qualified_entries_for_every_declared_function(files, guide):
    text = (docs.SOURCE / guide).read_text(encoding="utf-8")
    missing = []

    def inspect(node, parents, file):
        for child in ast.iter_child_nodes(node):
            if isinstance(child, (ast.ClassDef, ast.FunctionDef, ast.AsyncFunctionDef)):
                qualified = ".".join([*parents, child.name])
                if not isinstance(child, ast.ClassDef):
                    if not re.search(r"`" + re.escape(qualified) + r"\(", text):
                        missing.append(f"{file}:{child.lineno}: {qualified}")
                inspect(child, [*parents, child.name], file)
            else:
                inspect(child, parents, file)

    for name in files:
        file = ROOT / "clients/python/aether_ml" / name
        inspect(ast.parse(file.read_text(encoding="utf-8")), [], name)
    assert missing == [], "Missing qualified function entries:\n" + "\n".join(missing)


def test_ml_reference_inventory_includes_every_file_with_explicit_functions():
    covered = {name for files, _ in ML_FUNCTION_REFERENCES for name in files}
    root = ROOT / "clients/python/aether_ml"
    declared = set()
    for file in root.rglob("*.py"):
        tree = ast.parse(file.read_text(encoding="utf-8"))
        if any(isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)) for node in ast.walk(tree)):
            declared.add(file.relative_to(root).as_posix())
    assert declared == covered, "ML source coverage changed; expand the appropriate function reference"


PIPELINE_FUNCTION_REFERENCES = [
    (["dataset.py", "loader_workers.py", "prefetch.py"], "PYTHON-PIPELINE-FUNCTIONS.md", 25),
    (["java_store.py", "persistent_mmap.py", "resources.py"], "PYTHON-STORE-ADAPTER-FUNCTIONS.md", 29),
]


def _qualified_python_functions(tree, parents=()):
    for child in ast.iter_child_nodes(tree):
        if isinstance(child, (ast.ClassDef, ast.FunctionDef, ast.AsyncFunctionDef)):
            qualified = (*parents, child.name)
            if not isinstance(child, ast.ClassDef):
                yield ".".join(qualified), child.lineno
            yield from _qualified_python_functions(child, qualified)
        else:
            yield from _qualified_python_functions(child, parents)


def test_population_profile_reference_covers_every_declaration():
    tree = ast.parse((ROOT / "scripts/profile_population.py").read_text(encoding="utf-8"))
    declarations = dict(_qualified_python_functions(tree))
    text = (docs.SOURCE / "POPULATION-PROFILE-FUNCTIONS.md").read_text(encoding="utf-8")
    assert len(declarations) == 18
    assert all(re.search(r"`" + re.escape(name) + r"\(", text) for name in declarations)


def test_cache_profile_reference_covers_both_complete_modules():
    declarations = []
    for file in ("profile_cache_requests.py", "profile_cache_jfr.py"):
        tree = ast.parse((ROOT / "scripts" / file).read_text(encoding="utf-8"))
        declarations.extend(name for name, _ in _qualified_python_functions(tree))
    text = (docs.SOURCE / "CACHE-PROFILE-FUNCTIONS.md").read_text(encoding="utf-8")
    assert len(declarations) == 7
    assert all(re.search(r"`" + re.escape(name) + r"\(", text) for name in declarations)


def test_jfr_analysis_reference_covers_both_complete_modules():
    declarations = []
    for file in ("analyze_cache_jfr.py", "bulk_jfr_analyze.py"):
        tree = ast.parse((ROOT / "scripts" / file).read_text(encoding="utf-8"))
        declarations.extend(name for name, _ in _qualified_python_functions(tree))
    text = (docs.SOURCE / "JFR-ANALYSIS-FUNCTIONS.md").read_text(encoding="utf-8")
    assert len(declarations) == 6
    assert all(re.search(r"`" + re.escape(name) + r"\(", text) for name in declarations)


def test_bulk_diagnostic_driver_reference_covers_modules_in_own_sections():
    text = (docs.SOURCE / "BULK-DIAGNOSTIC-DRIVERS.md").read_text(encoding="utf-8")
    sections = {
        "profile_bulk_jfr.py": text.split("## Bulk JFR Driver Functions")[1].split("## Background Compaction")[0],
        "profile_background_compaction.py": text.split("## Background Compaction Diagnostic Function")[1],
    }
    count = 0
    for file, section in sections.items():
        tree = ast.parse((ROOT / "scripts" / file).read_text(encoding="utf-8"))
        declarations = dict(_qualified_python_functions(tree))
        count += len(declarations)
        assert all(re.search(r"`" + re.escape(name) + r"\(", section) for name in declarations)
    assert count == 3


def test_verification_campaign_reference_covers_complete_module():
    tree = ast.parse((ROOT / "scripts/profile_bulk_verification.py").read_text(encoding="utf-8"))
    declarations = dict(_qualified_python_functions(tree))
    text = (docs.SOURCE / "VERIFICATION-CAMPAIGN-FUNCTIONS.md").read_text(encoding="utf-8")
    assert len(declarations) == 8
    assert all(re.search(r"`" + re.escape(name) + r"\(", text) for name in declarations)


def test_hit_path_reference_covers_complete_module():
    tree = ast.parse((ROOT / "scripts/hit_path_profile.py").read_text(encoding="utf-8"))
    declarations = dict(_qualified_python_functions(tree))
    text = (docs.SOURCE / "HIT-PATH-FUNCTIONS.md").read_text(encoding="utf-8")
    assert len(declarations) == 19
    assert all(re.search(r"`" + re.escape(name) + r"\(", text) for name in declarations)


def test_experiment_ownership_reference_covers_both_modules():
    declarations = []
    for file in ("cache_workspace.py", "experiment_output.py"):
        tree = ast.parse((ROOT / "scripts" / file).read_text(encoding="utf-8"))
        declarations.extend(name for name, _ in _qualified_python_functions(tree))
    text = (docs.SOURCE / "EXPERIMENT-OWNERSHIP-FUNCTIONS.md").read_text(encoding="utf-8")
    assert len(declarations) == 4
    assert all(re.search(r"`" + re.escape(name) + r"\(", text) for name in declarations)


def test_rpc_server_reference_covers_selected_compiler_declarations():
    java = shutil.which("java")
    if not java:
        pytest.skip("JDK required for compiler-tree function coverage")
    file = ROOT / "modules/aether-rpc-transport/src/main/java/io/aetherdb/rpc/transport/PlaintextDevelopmentRpc.java"
    result = subprocess.run([java, str(ROOT / "scripts/InventoryJavaFunctions.java"), str(file)],
                            check=True, capture_output=True, text=True, encoding="utf-8", timeout=60)
    all_declarations = list(csv.reader(io.StringIO(result.stdout), delimiter="\t"))
    declarations = [row for row in all_declarations if
                    row[1].startswith("PlaintextDevelopmentRpc.DevelopmentServer") or
                    row[1] in {"PlaintextDevelopmentRpc.FrameInput", "PlaintextDevelopmentRpc.Cancellation",
                               "PlaintextDevelopmentRpc.SetOfConnections"} or
                    row[1] == "PlaintextDevelopmentRpc" and row[2] not in {"inboundAdmission", "outboundAdmission"}]
    text = (docs.SOURCE / "RPC-SERVER-FUNCTIONS.md").read_text(encoding="utf-8")
    assert len(all_declarations) == 54
    assert len(declarations) == 32
    assert all(re.search(r"`" + re.escape(owner + "." + name) + r"\(", text)
               for _, owner, name, _, _ in declarations)
    for signature in ("bind(identity, host, port)", "bind(identity, host, port, configuration)",
                      "client(identity)", "client(identity, configuration)"):
        assert "`PlaintextDevelopmentRpc." + signature + "`" in text


@pytest.mark.parametrize("module, package, file_count, declaration_count", [
    ("aether-security-api", "api", 14, 24),
    ("aether-security-core", "core", 8, 25),
])
def test_security_reference_covers_complete_modules(module, package, file_count, declaration_count):
    java = shutil.which("java")
    if not java:
        pytest.skip("JDK required for compiler-tree function coverage")
    base = ROOT / f"modules/{module}/src/main/java/io/aetherdb/security/{package}"
    files = sorted(set(base.glob("*.java")) - {base / "package-info.java"})
    assert len(files) == file_count
    result = subprocess.run([java, str(ROOT / "scripts/InventoryJavaFunctions.java"), *map(str, files)],
                            check=True, capture_output=True, text=True, encoding="utf-8", timeout=60)
    declarations = list(csv.reader(io.StringIO(result.stdout), delimiter="\t"))
    text = (docs.SOURCE / "SECURITY-FUNCTIONS.md").read_text(encoding="utf-8")
    assert len(declarations) == declaration_count
    assert all(re.search(r"`" + re.escape(owner + "." + name) + r"\(", text)
               for _, owner, name, _, _ in declarations)
    if package == "api":
        assert "`AuditUnavailableException.AuditUnavailableException(message, cause)`" in text
        assert "`AuditUnavailableException.AuditUnavailableException(message)`" in text
    else:
        assert "`NodeIdentityValidationException.NodeIdentityValidationException(message)`" in text
        assert "`NodeIdentityValidationException.NodeIdentityValidationException(message, cause)`" in text


def test_replication_contract_reference_covers_selected_compiler_declarations():
    java = shutil.which("java")
    if not java:
        pytest.skip("JDK required for compiler-tree function coverage")
    base = ROOT / "modules/aether-replication-api/src/main/java/io/aetherdb/replication/api"
    files = sorted(set(base.glob("*.java")) - {base / "package-info.java"})
    assert len(files) == 6
    files.extend([
        ROOT / "modules/aether-replicated-log/src/main/java/io/aetherdb/replication/log/StateSequencePlanner.java",
        ROOT / "modules/aether-state-machine/src/main/java/io/aetherdb/replication/state/AppliedState.java",
    ])
    result = subprocess.run([java, str(ROOT / "scripts/InventoryJavaFunctions.java"), *map(str, files)],
                            check=True, capture_output=True, text=True, encoding="utf-8", timeout=60)
    declarations = list(csv.reader(io.StringIO(result.stdout), delimiter="\t"))
    text = (docs.SOURCE / "REPLICATION-CONTRACT-FUNCTIONS.md").read_text(encoding="utf-8")
    assert len(declarations) == 34
    assert all(re.search(r"`" + re.escape(owner + "." + name) + r"\(", text)
               for _, owner, name, _, _ in declarations)


def test_replication_format_reference_covers_five_complete_files():
    java = shutil.which("java")
    if not java:
        pytest.skip("JDK required for compiler-tree function coverage")
    base = ROOT / "modules/aether-replicated-log/src/main/java/io/aetherdb/replication/log"
    files = [base / (name + ".java") for name in (
        "ReplicatedLogFormatV1", "ReplicatedLogIdentityV1", "ReplicatedLogSegmentHeaderV1",
        "ReplicatedWriteCommandV1", "ReplicatedLogEntryCodecV1",
    )]
    result = subprocess.run([java, str(ROOT / "scripts/InventoryJavaFunctions.java"), *map(str, files)],
                            check=True, capture_output=True, text=True, encoding="utf-8", timeout=60)
    declarations = list(csv.reader(io.StringIO(result.stdout), delimiter="\t"))
    text = (docs.SOURCE / "REPLICATION-FORMAT-FUNCTIONS.md").read_text(encoding="utf-8")
    assert len(declarations) == 43
    assert all(re.search(r"`" + re.escape(owner + "." + name) + r"\(", text)
               for _, owner, name, _, _ in declarations)


def test_replicated_store_reference_completes_module_inventory():
    java = shutil.which("java")
    if not java:
        pytest.skip("JDK required for compiler-tree function coverage")
    base = ROOT / "modules/aether-replicated-log/src/main/java/io/aetherdb/replication/log"
    files = sorted(set(base.glob("*.java")) - {base / "package-info.java"})
    assert len(files) == 7
    result = subprocess.run([java, str(ROOT / "scripts/InventoryJavaFunctions.java"), *map(str, files)],
                            check=True, capture_output=True, text=True, encoding="utf-8", timeout=60)
    declarations = list(csv.reader(io.StringIO(result.stdout), delimiter="\t"))
    assert len(declarations) == 89
    store = [row for row in declarations if Path(row[0]).name == "ReplicatedLogStoreV1.java"]
    assert len(store) == 43
    guides = {
        "ReplicatedLogStoreV1.java": "REPLICATED-STORE-FUNCTIONS.md",
        "StateSequencePlanner.java": "REPLICATION-CONTRACT-FUNCTIONS.md",
    }
    for file, owner, name, _, _ in declarations:
        guide = guides.get(Path(file).name, "REPLICATION-FORMAT-FUNCTIONS.md")
        text = (docs.SOURCE / guide).read_text(encoding="utf-8")
        assert re.search(r"`" + re.escape(owner + "." + name) + r"\(", text)


@pytest.mark.parametrize("module, file_count, declaration_count", [
    ("aether-raft-api", 6, 4),
    ("aether-raft-core", 5, 21),
])
def test_raft_core_reference_covers_complete_modules(module, file_count, declaration_count):
    java = shutil.which("java")
    if not java:
        pytest.skip("JDK required for compiler-tree function coverage")
    base = ROOT / f"modules/{module}/src/main/java"
    files = sorted(file for file in base.rglob("*.java") if file.name != "package-info.java")
    assert len(files) == file_count
    result = subprocess.run([java, str(ROOT / "scripts/InventoryJavaFunctions.java"), *map(str, files)],
                            check=True, capture_output=True, text=True, encoding="utf-8", timeout=60)
    declarations = list(csv.reader(io.StringIO(result.stdout), delimiter="\t"))
    text = (docs.SOURCE / "RAFT-CORE-FUNCTIONS.md").read_text(encoding="utf-8")
    assert len(declarations) == declaration_count
    assert all(re.search(r"`" + re.escape(owner + "." + name) + r"\(", text)
               for _, owner, name, _, _ in declarations)
    if module == "aether-raft-api":
        for name in ("VoteReason.java", "AppendEntriesReason.java", "RaftRole.java", "VoteKind.java"):
            source = next(file for file in files if file.name == name).read_text(encoding="utf-8")
            assert all(value in text for value in re.findall(r"^    ([A-Z][A-Z_]+)(?:[,(;])", source, re.M))


def test_raft_storage_reference_covers_complete_module():
    java = shutil.which("java")
    if not java:
        pytest.skip("JDK required for compiler-tree function coverage")
    base = ROOT / "modules/aether-raft-storage/src/main/java"
    files = sorted(file for file in base.rglob("*.java") if file.name != "package-info.java")
    assert len(files) == 3
    result = subprocess.run([java, str(ROOT / "scripts/InventoryJavaFunctions.java"), *map(str, files)],
                            check=True, capture_output=True, text=True, encoding="utf-8", timeout=60)
    declarations = list(csv.reader(io.StringIO(result.stdout), delimiter="\t"))
    text = (docs.SOURCE / "RAFT-STORAGE-FUNCTIONS.md").read_text(encoding="utf-8")
    assert len(declarations) == 17
    assert all(re.search(r"`" + re.escape(owner + "." + name) + r"\(", text)
               for _, owner, name, _, _ in declarations)


@pytest.mark.parametrize("module, file_count, declaration_count", [
    ("aether-cluster-api", 11, 68),
    ("aether-cluster-core", 1, 5),
])
def test_cluster_membership_reference_covers_complete_modules(module, file_count, declaration_count):
    java = shutil.which("java")
    if not java:
        pytest.skip("JDK required for compiler-tree function coverage")
    base = ROOT / f"modules/{module}/src/main/java"
    files = sorted(file for file in base.rglob("*.java") if file.name != "package-info.java")
    assert len(files) == file_count
    result = subprocess.run([java, str(ROOT / "scripts/InventoryJavaFunctions.java"), *map(str, files)],
                            check=True, capture_output=True, text=True, encoding="utf-8", timeout=60)
    declarations = list(csv.reader(io.StringIO(result.stdout), delimiter="\t"))
    text = (docs.SOURCE / "CLUSTER-MEMBERSHIP-FUNCTIONS.md").read_text(encoding="utf-8")
    assert len(declarations) == declaration_count
    assert all(re.search(r"`" + re.escape(owner + "." + name) + r"\(", text)
               for _, owner, name, _, _ in declarations)


def test_cluster_codec_reference_covers_complete_module():
    java = shutil.which("java")
    if not java:
        pytest.skip("JDK required for compiler-tree function coverage")
    base = ROOT / "modules/aether-cluster-codec/src/main/java"
    files = sorted(file for file in base.rglob("*.java") if file.name != "package-info.java")
    assert len(files) == 4
    result = subprocess.run([java, str(ROOT / "scripts/InventoryJavaFunctions.java"), *map(str, files)],
                            check=True, capture_output=True, text=True, encoding="utf-8", timeout=60)
    declarations = list(csv.reader(io.StringIO(result.stdout), delimiter="\t"))
    text = (docs.SOURCE / "CLUSTER-CODEC-FUNCTIONS.md").read_text(encoding="utf-8")
    assert len(declarations) == 53
    assert all(re.search(r"`" + re.escape(owner + "." + name) + r"\(", text)
               for _, owner, name, _, _ in declarations)


@pytest.mark.parametrize("section, file_count, declaration_count", [
    ("api", 8, 17),
    ("engine", 6, 33),
])
def test_observability_reference_covers_selected_files(section, file_count, declaration_count):
    java = shutil.which("java")
    if not java:
        pytest.skip("JDK required for compiler-tree function coverage")
    if section == "api":
        base = ROOT / "modules/aether-observability-api/src/main/java"
        files = sorted(file for file in base.rglob("*.java") if file.name != "package-info.java")
    else:
        base = ROOT / "modules/aether-engine/src/main/java/io/aetherdb/engine"
        files = [base / name for name in (
            "DefaultMeteredAetherDatabase.java", "MeteredAetherDatabase.java",
            "DatabaseMetrics.java", "OperationMetrics.java", "DatabaseOperation.java",
            "DatabaseMetricExporter.java",
        )]
    assert len(files) == file_count
    result = subprocess.run([java, str(ROOT / "scripts/InventoryJavaFunctions.java"), *map(str, files)],
                            check=True, capture_output=True, text=True, encoding="utf-8", timeout=60)
    declarations = list(csv.reader(io.StringIO(result.stdout), delimiter="\t"))
    text = (docs.SOURCE / "OBSERVABILITY-FUNCTIONS.md").read_text(encoding="utf-8")
    assert len(declarations) == declaration_count
    assert all(re.search(r"`" + re.escape(owner + "." + name) + r"\(", text)
               for _, owner, name, _, _ in declarations)


def test_java_benchmark_runner_reference_covers_selected_files():
    java = shutil.which("java")
    if not java:
        pytest.skip("JDK required for compiler-tree function coverage")
    base = ROOT / "modules/aether-benchmarks/src/main/java/io/aetherdb/benchmarks"
    files = [base / name for name in (
        "BenchmarkProfile.java", "BenchmarkProfileRegistry.java", "BenchmarkProfileRunPlan.java",
        "BenchmarkProfileRunner.java", "BenchmarkProfileScope.java", "CvBenchmark.java",
    )]
    result = subprocess.run([java, str(ROOT / "scripts/InventoryJavaFunctions.java"), *map(str, files)],
                            check=True, capture_output=True, text=True, encoding="utf-8", timeout=60)
    declarations = list(csv.reader(io.StringIO(result.stdout), delimiter="\t"))
    text = (docs.SOURCE / "JAVA-BENCHMARK-RUNNER-FUNCTIONS.md").read_text(encoding="utf-8")
    assert len(declarations) == 41
    assert all(re.search(r"`" + re.escape(owner + "." + name) + r"\(", text)
               for _, owner, name, _, _ in declarations)


def test_java_benchmark_evidence_reference_covers_selected_files():
    java = shutil.which("java")
    if not java:
        pytest.skip("JDK required for compiler-tree function coverage")
    base = ROOT / "modules/aether-benchmarks/src/main/java/io/aetherdb/benchmarks"
    files = [base / name for name in (
        "BenchmarkResultV1.java", "BenchmarkResultJsonV1.java", "BenchmarkArtifact.java",
        "BenchmarkArtifacts.java", "BenchmarkCounters.java", "BenchmarkLatencyHistogram.java",
        "BenchmarkBaselineEntry.java", "BenchmarkBaselineStore.java",
    )]
    result = subprocess.run([java, str(ROOT / "scripts/InventoryJavaFunctions.java"), *map(str, files)],
                            check=True, capture_output=True, text=True, encoding="utf-8", timeout=60)
    declarations = list(csv.reader(io.StringIO(result.stdout), delimiter="\t"))
    text = (docs.SOURCE / "JAVA-BENCHMARK-EVIDENCE-FUNCTIONS.md").read_text(encoding="utf-8")
    assert len(declarations) == 27
    assert all(re.search(r"`" + re.escape(owner + "." + name) + r"\(", text)
               for _, owner, name, _, _ in declarations)


def test_java_benchmark_comparison_reference_covers_selected_files():
    java = shutil.which("java")
    if not java:
        pytest.skip("JDK required for compiler-tree function coverage")
    base = ROOT / "modules/aether-benchmarks/src/main/java/io/aetherdb/benchmarks"
    files = [base / name for name in (
        "RegressionGatePolicy.java", "RegressionComparison.java", "RegressionComparator.java",
        "BenchmarkGateEvaluator.java", "ExternalEngine.java", "ExternalBenchmarkComparison.java",
        "ExternalBenchmarkComparator.java", "BenchmarkSemanticsManifest.java",
    )]
    result = subprocess.run([java, str(ROOT / "scripts/InventoryJavaFunctions.java"), *map(str, files)],
                            check=True, capture_output=True, text=True, encoding="utf-8", timeout=60)
    declarations = list(csv.reader(io.StringIO(result.stdout), delimiter="\t"))
    text = (docs.SOURCE / "JAVA-BENCHMARK-COMPARISON-FUNCTIONS.md").read_text(encoding="utf-8")
    assert len(declarations) == 14
    assert all(re.search(r"`" + re.escape(owner + "." + name) + r"\(", text)
               for _, owner, name, _, _ in declarations)


def test_release_certification_reference_covers_complete_module():
    java = shutil.which("java")
    if not java:
        pytest.skip("JDK required for compiler-tree function coverage")
    base = ROOT / "modules/aether-release/src/main/java/io/aetherdb/release"
    files = sorted(set(base.glob("*.java")) - {base / "package-info.java"})
    assert len(files) == 9
    result = subprocess.run([java, str(ROOT / "scripts/InventoryJavaFunctions.java"), *map(str, files)],
                            check=True, capture_output=True, text=True, encoding="utf-8", timeout=60)
    declarations = list(csv.reader(io.StringIO(result.stdout), delimiter="\t"))
    text = (docs.SOURCE / "RELEASE-CERTIFICATION-FUNCTIONS.md").read_text(encoding="utf-8")
    assert len(declarations) == 22
    assert all(re.search(r"`" + re.escape(owner + "." + name) + r"\(", text)
               for _, owner, name, _, _ in declarations)


def test_gradle_plugin_reference_covers_complete_implementation():
    java = shutil.which("java")
    if not java:
        pytest.skip("JDK required for compiler-tree function coverage")
    base = ROOT / "modules/aether-gradle-plugin/src/main/java/io/aetherdb/gradle"
    files = sorted(base.glob("*.java"))
    assert len(files) == 1
    result = subprocess.run([java, str(ROOT / "scripts/InventoryJavaFunctions.java"), *map(str, files)],
                            check=True, capture_output=True, text=True, encoding="utf-8", timeout=60)
    declarations = list(csv.reader(io.StringIO(result.stdout), delimiter="\t"))
    text = (docs.SOURCE / "GRADLE-PLUGIN-FUNCTIONS.md").read_text(encoding="utf-8")
    assert len(declarations) == 5
    assert all(re.search(r"`" + re.escape(owner + "." + name) + r"\(", text)
               for _, owner, name, _, _ in declarations)
    scripts = sorted((ROOT / "build-logic/src/main/kotlin").glob("*.gradle.kts"))
    assert len(scripts) == 7
    assert all(path.name in text for path in scripts)
    assert "`MavenPublication.configurePom(" in text
    assert "`configureMavenPublication(" in text


def test_workbench_workspace_reference_covers_selected_files():
    java = shutil.which("java")
    if not java:
        pytest.skip("JDK required for compiler-tree function coverage")
    base = ROOT / "modules/aether-workbench/src/main/java/io/aetherdb/workbench"
    files = [base / name for name in (
        "DatabaseWorkspace.java", "WorkspaceTableModel.java",
        "RpcFrameInspector.java", "ReplicationInspector.java",
    )]
    result = subprocess.run([java, str(ROOT / "scripts/InventoryJavaFunctions.java"), *map(str, files)],
                            check=True, capture_output=True, text=True, encoding="utf-8", timeout=60)
    declarations = list(csv.reader(io.StringIO(result.stdout), delimiter="\t"))
    text = (docs.SOURCE / "WORKBENCH-WORKSPACE-FUNCTIONS.md").read_text(encoding="utf-8")
    assert len(declarations) == 38
    assert all(re.search(r"`" + re.escape(owner + "." + name) + r"\(", text)
               for _, owner, name, _, _ in declarations)


def test_workbench_typed_value_reference_covers_complete_file():
    java = shutil.which("java")
    if not java:
        pytest.skip("JDK required for compiler-tree function coverage")
    source = ROOT / "modules/aether-workbench/src/main/java/io/aetherdb/workbench/UniversalTypedValue.java"
    result = subprocess.run([java, str(ROOT / "scripts/InventoryJavaFunctions.java"), str(source)],
                            check=True, capture_output=True, text=True, encoding="utf-8", timeout=60)
    declarations = list(csv.reader(io.StringIO(result.stdout), delimiter="\t"))
    text = (docs.SOURCE / "WORKBENCH-TYPED-VALUE-FUNCTIONS.md").read_text(encoding="utf-8")
    assert len(declarations) == 20
    assert all(re.search(r"`" + re.escape(owner + "." + name) + r"\(", text)
               for _, owner, name, _, _ in declarations)
    assert "`UniversalTypedValue.display(value)`" in text
    assert "`UniversalTypedValue.display(value, descriptor)`" in text


def test_workbench_record_dialog_reference_covers_complete_file():
    java = shutil.which("java")
    if not java:
        pytest.skip("JDK required for compiler-tree function coverage")
    source = ROOT / "modules/aether-workbench/src/main/java/io/aetherdb/workbench/RecordDialog.java"
    result = subprocess.run([java, str(ROOT / "scripts/InventoryJavaFunctions.java"), str(source)],
                            check=True, capture_output=True, text=True, encoding="utf-8", timeout=60)
    declarations = list(csv.reader(io.StringIO(result.stdout), delimiter="\t"))
    text = (docs.SOURCE / "WORKBENCH-RECORD-DIALOG-FUNCTIONS.md").read_text(encoding="utf-8")
    assert len(declarations) == 14
    assert all(re.search(r"`" + re.escape(owner + "." + name) + r"\(", text)
               for _, owner, name, _, _ in declarations)
    assert "`RecordDialog.show(parent, title, key, value)`" in text
    assert "`RecordDialog.show(parent, title, key, value, keyEditable)`" in text


def test_workbench_window_reference_covers_complete_module_remainder():
    java = shutil.which("java")
    if not java:
        pytest.skip("JDK required for compiler-tree function coverage")
    base = ROOT / "modules/aether-workbench/src/main/java/io/aetherdb/workbench"
    files = list(base.glob("*.java"))
    result = subprocess.run([java, str(ROOT / "scripts/InventoryJavaFunctions.java"), *map(str, files)],
                            check=True, capture_output=True, text=True, encoding="utf-8", timeout=60)
    declarations = list(csv.reader(io.StringIO(result.stdout), delimiter="\t"))
    assert len(declarations) == 86
    references = {
        "AetherWorkbench.java": "WORKBENCH-WINDOW-FUNCTIONS.md",
        "RecordDialog.java": "WORKBENCH-RECORD-DIALOG-FUNCTIONS.md",
        "UniversalTypedValue.java": "WORKBENCH-TYPED-VALUE-FUNCTIONS.md",
        "DatabaseWorkspace.java": "WORKBENCH-WORKSPACE-FUNCTIONS.md",
        "WorkspaceTableModel.java": "WORKBENCH-WORKSPACE-FUNCTIONS.md",
        "RpcFrameInspector.java": "WORKBENCH-WORKSPACE-FUNCTIONS.md",
        "ReplicationInspector.java": "WORKBENCH-WORKSPACE-FUNCTIONS.md",
    }
    assert sum(Path(file).name == "AetherWorkbench.java" for file, *_ in declarations) == 14
    for file, owner, name, _, _ in declarations:
        text = (docs.SOURCE / references[Path(file).name]).read_text(encoding="utf-8")
        assert re.search(r"`" + re.escape(owner + "." + name) + r"\(", text), (file, owner, name)


def test_crypto_reference_covers_complete_module():
    java = shutil.which("java")
    if not java:
        pytest.skip("JDK required for compiler-tree function coverage")
    base = ROOT / "modules/aether-crypto/src/main/java/io/aetherdb/crypto"
    files = sorted(set(base.glob("*.java")) - {base / "package-info.java"})
    assert len(files) == 7
    result = subprocess.run([java, str(ROOT / "scripts/InventoryJavaFunctions.java"), *map(str, files)],
                            check=True, capture_output=True, text=True, encoding="utf-8", timeout=60)
    declarations = list(csv.reader(io.StringIO(result.stdout), delimiter="\t"))
    text = (docs.SOURCE / "CRYPTO-FUNCTIONS.md").read_text(encoding="utf-8")
    assert len(declarations) == 25
    assert all(re.search(r"`" + re.escape(owner + "." + name) + r"\(", text)
               for _, owner, name, _, _ in declarations)


def test_client_codec_reference_covers_complete_module():
    java = shutil.which("java")
    if not java:
        pytest.skip("JDK required for compiler-tree function coverage")
    base = ROOT / "modules/aether-client-codec/src/main/java/io/aetherdb/client/codec"
    files = sorted(base.glob("*.java"))
    assert len(files) == 4
    result = subprocess.run([java, str(ROOT / "scripts/InventoryJavaFunctions.java"), *map(str, files)],
                            check=True, capture_output=True, text=True, encoding="utf-8", timeout=60)
    declarations = list(csv.reader(io.StringIO(result.stdout), delimiter="\t"))
    text = (docs.SOURCE / "CLIENT-CODEC-FUNCTIONS.md").read_text(encoding="utf-8")
    assert len(declarations) == 27
    assert all(re.search(r"`" + re.escape(owner + "." + name) + r"\(", text)
               for _, owner, name, _, _ in declarations)


def test_client_protocol_reference_covers_complete_api_module():
    java = shutil.which("java")
    if not java:
        pytest.skip("JDK required for compiler-tree function coverage")
    base = ROOT / "modules/aether-client-api/src/main/java/io/aetherdb/client/api"
    files = sorted(base.glob("*.java"))
    assert len(files) == 11
    result = subprocess.run([java, str(ROOT / "scripts/InventoryJavaFunctions.java"), *map(str, files)],
                            check=True, capture_output=True, text=True, encoding="utf-8", timeout=60)
    declarations = list(csv.reader(io.StringIO(result.stdout), delimiter="\t"))
    text = (docs.SOURCE / "CLIENT-PROTOCOL-FUNCTIONS.md").read_text(encoding="utf-8")
    assert len(declarations) == 22
    assert all(re.search(r"`" + re.escape(owner + "." + name) + r"\(", text)
               for _, owner, name, _, _ in declarations)
    assert "`ClientWriteRequest.ClientWriteRequest(configurationVersion, operations)`" in text
    assert "`ClientWriteRequest.ClientWriteRequest(configurationVersion, commandId, deduplicated, operations)`" in text


def test_remote_facade_and_routing_cover_complete_client_module():
    java = shutil.which("java")
    if not java:
        pytest.skip("JDK required for compiler-tree function coverage")
    base = ROOT / "modules/aether-client/src/main/java/io/aetherdb/client"
    files = sorted(set(base.glob("*.java")) - {base / "package-info.java"})
    assert len(files) == 11
    result = subprocess.run([java, str(ROOT / "scripts/InventoryJavaFunctions.java"), *map(str, files)],
                            check=True, capture_output=True, text=True, encoding="utf-8", timeout=60)
    declarations = list(csv.reader(io.StringIO(result.stdout), delimiter="\t"))
    facade = (docs.SOURCE / "REMOTE-FACADE-FUNCTIONS.md").read_text(encoding="utf-8")
    routing = (docs.SOURCE / "REMOTE-ROUTING-FUNCTIONS.md").read_text(encoding="utf-8")
    assert len(declarations) == 46
    facade_count = 0
    for _, owner, name, _, _ in declarations:
        pattern = r"`" + re.escape(owner + "." + name) + r"\("
        in_facade = bool(re.search(pattern, facade))
        in_routing = bool(re.search(pattern, routing))
        assert in_facade != in_routing, f"Missing or duplicate partition: {owner}.{name}"
        facade_count += in_facade
    assert facade_count == 27


def test_remote_routing_reference_covers_selected_compiler_declarations():
    java = shutil.which("java")
    if not java:
        pytest.skip("JDK required for compiler-tree function coverage")
    base = ROOT / "modules/aether-client/src/main/java/io/aetherdb/client"
    files = [base / name for name in ("RemoteEndpointResolver.java", "RemoteConnectionPool.java",
                                    "RemoteRetryPolicy.java", "RemoteRetryDecision.java",
                                    "RemoteClientIdentity.java", "RetryAction.java")]
    result = subprocess.run([java, str(ROOT / "scripts/InventoryJavaFunctions.java"), *map(str, files)],
                            check=True, capture_output=True, text=True, encoding="utf-8", timeout=60)
    declarations = list(csv.reader(io.StringIO(result.stdout), delimiter="\t"))
    text = (docs.SOURCE / "REMOTE-ROUTING-FUNCTIONS.md").read_text(encoding="utf-8")
    assert len(declarations) == 19
    assert all(re.search(r"`" + re.escape(owner + "." + name) + r"\(", text)
               for _, owner, name, _, _ in declarations)
    assert "`RemoteConnectionPool.reserveEndpoint()`" in text
    assert "`RemoteConnectionPool.reserveEndpoint(endpoint)`" in text


def test_rpc_client_and_server_partition_covers_entire_transport_source():
    java = shutil.which("java")
    if not java:
        pytest.skip("JDK required for compiler-tree function coverage")
    base = ROOT / "modules/aether-rpc-transport/src/main/java/io/aetherdb/rpc/transport"
    files = sorted(set(base.glob("*.java")) - {base / "package-info.java"})
    assert len(files) == 5
    result = subprocess.run([java, str(ROOT / "scripts/InventoryJavaFunctions.java"), *map(str, files)],
                            check=True, capture_output=True, text=True, encoding="utf-8", timeout=60)
    declarations = list(csv.reader(io.StringIO(result.stdout), delimiter="\t"))
    assert len(declarations) == 65
    client = (docs.SOURCE / "RPC-CLIENT-FUNCTIONS.md").read_text(encoding="utf-8")
    server = (docs.SOURCE / "RPC-SERVER-FUNCTIONS.md").read_text(encoding="utf-8")
    support = (docs.SOURCE / "RPC-TRANSPORT-LIMITS.md").read_text(encoding="utf-8")
    client_count = 0
    for _, owner, name, _, _ in declarations:
        pattern = r"`" + re.escape(owner + "." + name) + r"\("
        if owner.startswith("PlaintextDevelopmentRpc"):
            in_client = bool(re.search(pattern, client))
            in_server = bool(re.search(pattern, server))
            assert in_client != in_server, f"Missing or duplicate partition: {owner}.{name}"
            client_count += in_client
        else:
            assert re.search(pattern, support), f"Missing support declaration: {owner}.{name}"
    assert client_count == 22
    for name in ("inboundAdmission", "outboundAdmission"):
        assert len(re.findall(r"`PlaintextDevelopmentRpc\." + name + r"\(", client)) == 2


def test_rpc_transport_limits_reference_covers_selected_compiler_declarations():
    java = shutil.which("java")
    if not java:
        pytest.skip("JDK required for compiler-tree function coverage")
    base = ROOT / "modules/aether-rpc-transport/src/main/java/io/aetherdb/rpc/transport"
    files = [base / name for name in ("RpcTransportConfiguration.java", "RpcIdentity.java",
                                    "RpcFlowController.java", "RpcConnectionState.java")]
    result = subprocess.run([java, str(ROOT / "scripts/InventoryJavaFunctions.java"), *map(str, files)],
                            check=True, capture_output=True, text=True, encoding="utf-8", timeout=60)
    declarations = list(csv.reader(io.StringIO(result.stdout), delimiter="\t"))
    text = (docs.SOURCE / "RPC-TRANSPORT-LIMITS.md").read_text(encoding="utf-8")
    assert len(declarations) == 11
    assert all(re.search(r"`" + re.escape(owner + "." + name) + r"\(", text)
               for _, owner, name, _, _ in declarations)


def test_rpc_api_reference_covers_every_explicit_compiler_declaration():
    java = shutil.which("java")
    if not java:
        pytest.skip("JDK required for compiler-tree function coverage")
    base = ROOT / "modules/aether-rpc-api/src/main/java/io/aetherdb/rpc/api"
    files = sorted(set(base.glob("*.java")) - {base / "package-info.java"})
    assert len(files) == 17
    result = subprocess.run([java, str(ROOT / "scripts/InventoryJavaFunctions.java"), *map(str, files)],
                            check=True, capture_output=True, text=True, encoding="utf-8", timeout=60)
    declarations = list(csv.reader(io.StringIO(result.stdout), delimiter="\t"))
    text = (docs.SOURCE / "RPC-API-FUNCTIONS.md").read_text(encoding="utf-8")
    assert len(declarations) == 33
    assert all(re.search(r"`" + re.escape(owner + "." + name) + r"\(", text)
               for _, owner, name, _, _ in declarations)
    assert "`RpcAdmission.inboundRequest(operation, bodyBytes, draining)`" in text
    assert "`RpcAdmission.inboundRequest(bodyBytes, draining)`" in text


def test_rpc_frame_reference_covers_every_explicit_compiler_declaration():
    java = shutil.which("java")
    if not java:
        pytest.skip("JDK required for compiler-tree function coverage")
    base = ROOT / "modules/aether-rpc-codec/src/main/java/io/aetherdb/rpc/codec"
    files = [base / name for name in ("RpcFrame.java", "RpcFrameType.java", "RpcFrameHeaderV1.java",
                                    "RpcFrameCodecV1.java", "RpcFrameDecoder.java", "RpcHelloV1.java",
                                    "RpcMessageAssembler.java", "RpcMessageFragmenter.java",
                                    "RpcStreamIdAllocator.java", "RpcProtocolException.java")]
    assert set(files) == set(base.glob("*.java")) - {base / "package-info.java"}
    result = subprocess.run([java, str(ROOT / "scripts/InventoryJavaFunctions.java"), *map(str, files)],
                            check=True, capture_output=True, text=True, encoding="utf-8", timeout=60)
    declarations = list(csv.reader(io.StringIO(result.stdout), delimiter="\t"))
    text = (docs.SOURCE / "RPC-FRAME-FUNCTIONS.md").read_text(encoding="utf-8")
    assert len(declarations) == 38
    assert all(re.search(r"`" + re.escape(owner + "." + name) + r"\(", text)
               for _, owner, name, _, _ in declarations)


@pytest.mark.parametrize("files, guide, minimum", PIPELINE_FUNCTION_REFERENCES)
def test_pipeline_references_have_qualified_entries_for_every_function(files, guide, minimum):
    text = (docs.SOURCE / guide).read_text(encoding="utf-8")
    declarations = []
    for name in files:
        file = ROOT / "clients/python/aether_training_cache" / name
        tree = ast.parse(file.read_text(encoding="utf-8"))
        declarations.extend((name, qualified, line) for qualified, line in _qualified_python_functions(tree))
    assert len(declarations) >= minimum
    missing = [f"{name}:{line}: {qualified}" for name, qualified, line in declarations
               if not re.search(r"`" + re.escape(qualified) + r"\(", text)]
    assert missing == [], "Missing pipeline function entries:\n" + "\n".join(missing)


def test_pipeline_reference_covers_only_the_selected_benchmark_routing_subtree():
    tree = ast.parse((ROOT / "clients/python/benchmark_gpu_segmentation.py").read_text(encoding="utf-8"))
    selected = next(node for node in tree.body if isinstance(node, ast.FunctionDef) and node.name == "prepared_batches")
    declarations = [(selected.name, selected.lineno), *_qualified_python_functions(selected, (selected.name,))]
    text = (docs.SOURCE / "PYTHON-PIPELINE-FUNCTIONS.md").read_text(encoding="utf-8")
    assert len(declarations) >= 2
    assert all(re.search(r"`" + re.escape(name) + r"\(", text) for name, _ in declarations)
    assert "rest of that large" in text


def test_provenance_references_partition_every_qualified_function_declaration():
    tree = ast.parse((ROOT / "clients/python/aether_training_cache/ml.py").read_text(encoding="utf-8"))
    declarations = dict(_qualified_python_functions(tree))
    assert len(declarations) >= 79
    guides = {
        "PYTHON-PROVENANCE-STORE-FUNCTIONS.md": 31,
        "PYTHON-PROVENANCE-WORKFLOW-FUNCTIONS.md": 29,
        "PYTHON-PROVENANCE-DIAGNOSTIC-FUNCTIONS.md": 19,
    }
    covered = []
    for guide, minimum in guides.items():
        text = (docs.SOURCE / guide).read_text(encoding="utf-8")
        entries = re.findall(r"^\| `([A-Za-z_][\w.]*)\(", text, re.MULTILINE)
        assert len(entries) >= minimum, f"Reference coverage shrank: {guide}"
        assert all(entry in declarations for entry in entries), f"Unknown declaration in {guide}"
        covered.extend(entries)
    assert len(covered) == len(set(covered)), "A declaration is listed in more than one provenance reference"
    assert set(covered) == set(declarations), f"Missing provenance functions: {set(declarations) - set(covered)}"


def test_source_links_keep_repository_and_fragment():
    source = docs.SOURCE / "CODE-TOUR.md"
    assert docs.link_target("../../settings.gradle.kts", source) == docs.REPO + "settings.gradle.kts"
    assert docs.link_target("https://example.com/page#test", source) == "https://example.com/page#test"


@pytest.mark.parametrize("files, guide, minimum", [
    (["h2_protocol.py"], "H2-PROTOCOL-FUNCTIONS.md", 13),
    (["h2_confirmatory.py", "h2_worker.py", "h2_input_paths.py"], "H2-EXECUTION-FUNCTIONS.md", 24),
    (["h2_analysis.py"], "H2-ANALYSIS-FUNCTIONS.md", 5),
    (["h2_sessions.py"], "H2-SESSION-FUNCTIONS.md", 8),
])
def test_h2_references_partition_complete_qualified_function_inventories(files, guide, minimum):
    declarations = []
    for name in files:
        file = ROOT / "scripts" / name
        tree = ast.parse(file.read_text(encoding="utf-8"))
        for qualified, _ in _qualified_python_functions(tree):
            if qualified == "main":
                qualified = f"{file.stem}.main"
            declarations.append(qualified)
    text = (docs.SOURCE / guide).read_text(encoding="utf-8")
    entries = re.findall(r"^\| `([A-Za-z_][\w.]*)\(", text, re.MULTILINE)
    assert len(declarations) >= minimum, f"H2 source inventory shrank: {files}"
    assert len(entries) == len(set(entries)), f"Duplicate H2 entry in {guide}"
    assert set(entries) == set(declarations), (
        f"H2 coverage mismatch: missing={set(declarations) - set(entries)}, "
        f"unknown={set(entries) - set(declarations)}")


def test_root_build_reference_tracks_tasks_helper_and_project_count():
    source = (ROOT / "build.gradle.kts").read_text(encoding="utf-8")
    guide = (docs.SOURCE / "ROOT-BUILD-ARCHITECTURE.md").read_text(encoding="utf-8")
    tasks = set(re.findall(r'tasks.register\("([^"]+)"\)', source))
    assert tasks == {"stageMavenCentral", "verifyReleaseVersion", "integrationTest", "crashTestSmoke", "crashTest",
                     "aetherSchemaInit", "aetherSchemaUpdate", "aetherSchemaAccept", "aetherSchemaCheck"}
    assert all(f"`{task}`" in guide for task in tasks)
    assert re.findall(r"fun (\w+)\(", source) == ["proposalTask"]
    assert "| `proposalTask(" in guide
    settings = (ROOT / "settings.gradle.kts").read_text(encoding="utf-8")
    assert len(re.findall(r'":modules:[^"]+"', settings)) == 49
    assert len(re.findall(r'":examples:[^"]+"', settings)) == 2


def test_root_publication_scope_matches_maven_checker():
    source = (ROOT / "build.gradle.kts").read_text(encoding="utf-8")
    published = set(re.findall(r'"(aether-[^"]+)"', source.split('tasks.register("stageMavenCentral")')[0]))
    tree = ast.parse((ROOT / "scripts/check-maven-publication.py").read_text(encoding="utf-8"))
    assignment = next(node for node in tree.body if isinstance(node, ast.Assign)
                      and any(isinstance(target, ast.Name) and target.id == "MODULES" for target in node.targets))
    assert published == ast.literal_eval(assignment.value)
    assert len(published) == 15


def test_build_utility_reference_covers_four_complete_sources():
    declarations = []
    for name in ("build-contributor-docs.py", "check-maven-publication.py", "build_paper.py", "kaggle_studio_windows.py"):
        file = ROOT / "scripts" / name
        tree = ast.parse(file.read_text(encoding="utf-8"))
        prefix = file.stem.replace("-", "_")
        declarations.extend(f"{prefix}.{qualified}" for qualified, _ in _qualified_python_functions(tree))
    text = (docs.SOURCE / "BUILD-UTILITY-FUNCTIONS.md").read_text(encoding="utf-8")
    entries = re.findall(r"^\| `([A-Za-z_][\w.]*)\(", text, re.MULTILINE)
    assert len(declarations) == 13
    assert len(entries) == len(set(entries))
    assert set(entries) == set(declarations)


def test_campaign_audit_reference_covers_three_complete_sources():
    declarations = []
    for name in ("submission_gate.py", "preserve_campaign.py", "confirmatory_v1.py"):
        file = ROOT / "scripts" / name
        tree = ast.parse(file.read_text(encoding="utf-8"))
        declarations.extend(f"{file.stem}.{qualified}" for qualified, _ in _qualified_python_functions(tree))
    text = (docs.SOURCE / "CAMPAIGN-AUDIT-FUNCTIONS.md").read_text(encoding="utf-8")
    entries = re.findall(r"^\| `([A-Za-z_][\w.]*)\(", text, re.MULTILINE)
    assert len(declarations) == 5
    assert len(entries) == len(set(entries))
    assert set(entries) == set(declarations)


def test_system_analysis_reference_covers_both_complete_sources():
    declarations = []
    for name in ("compare_cache_hotspots.py", "systems_figures.py"):
        file = ROOT / "scripts" / name
        tree = ast.parse(file.read_text(encoding="utf-8"))
        declarations.extend(f"{file.stem}.{qualified}" for qualified, _ in _qualified_python_functions(tree))
    text = (docs.SOURCE / "SYSTEM-ANALYSIS-FUNCTIONS.md").read_text(encoding="utf-8")
    entries = re.findall(r"^\| `([A-Za-z_][\w.]*)\(", text, re.MULTILINE)
    assert len(declarations) == 9
    assert len(entries) == len(set(entries))
    assert set(entries) == set(declarations)


def test_system_driver_reference_covers_both_complete_sources():
    declarations = []
    for name in ("concurrency_matrix.py", "fault_injection.py"):
        file = ROOT / "scripts" / name
        tree = ast.parse(file.read_text(encoding="utf-8"))
        declarations.extend(f"{file.stem}.{qualified}" for qualified, _ in _qualified_python_functions(tree))
    text = (docs.SOURCE / "SYSTEM-DRIVER-FUNCTIONS.md").read_text(encoding="utf-8")
    entries = re.findall(r"^\| `([A-Za-z_][\w.]*)\(", text, re.MULTILINE)
    assert len(declarations) == 10
    assert len(entries) == len(set(entries))
    assert set(entries) == set(declarations)


def test_preflight_utility_reference_covers_three_complete_sources():
    declarations = []
    for name in ("checksums.py", "validate_gpu.py", "artifact_smoke.py"):
        file = ROOT / "scripts" / name
        tree = ast.parse(file.read_text(encoding="utf-8"))
        declarations.extend(f"{file.stem}.{qualified}" for qualified, _ in _qualified_python_functions(tree))
    text = (docs.SOURCE / "PREFLIGHT-UTILITY-FUNCTIONS.md").read_text(encoding="utf-8")
    entries = re.findall(r"^\| `([A-Za-z_][\w.]*)\(", text, re.MULTILINE)
    assert len(declarations) == 4
    assert len(entries) == len(set(entries))
    assert set(entries) == set(declarations)


def test_transform_evolution_reference_covers_complete_source():
    tree = ast.parse((ROOT / "scripts/transform_evolution.py").read_text(encoding="utf-8"))
    declarations = [qualified for qualified, _ in _qualified_python_functions(tree)]
    text = (docs.SOURCE / "TRANSFORM-EVOLUTION-FUNCTIONS.md").read_text(encoding="utf-8")
    entries = re.findall(r"^\| `([A-Za-z_][\w.]*)\(", text, re.MULTILINE)
    assert len(declarations) == 3
    assert len(entries) == len(set(entries))
    assert set(entries) == set(declarations)


def test_dataset_preparation_reference_covers_four_complete_sources():
    declarations = []
    for name in ("fetch_coco.py", "prepare_vision.py", "prepare_evolution.py", "validate_manifests.py"):
        file = ROOT / "scripts" / name
        tree = ast.parse(file.read_text(encoding="utf-8"))
        declarations.extend(f"{file.stem}.{qualified}" for qualified, _ in _qualified_python_functions(tree))
    text = (docs.SOURCE / "DATASET-PREPARATION-FUNCTIONS.md").read_text(encoding="utf-8")
    entries = re.findall(r"^\| `([A-Za-z_][\w.]*)\(", text, re.MULTILINE)
    assert len(declarations) == 8
    assert len(entries) == len(set(entries))
    assert set(entries) == set(declarations)


def test_bulk_layout_reference_covers_complete_source():
    tree = ast.parse((ROOT / "scripts/bulk_layout.py").read_text(encoding="utf-8"))
    declarations = [qualified for qualified, _ in _qualified_python_functions(tree)]
    text = (docs.SOURCE / "BULK-LAYOUT-FUNCTIONS.md").read_text(encoding="utf-8")
    entries = re.findall(r"^\| `([A-Za-z_][\w.]*)\(", text, re.MULTILINE)
    assert len(declarations) == 6
    assert len(entries) == len(set(entries))
    assert set(entries) == set(declarations)


def test_dali_reference_covers_all_four_files():
    files = [ROOT / "clients/python/aether_training_cache/dali_workload.py"]
    files += [ROOT / "scripts" / name for name in ("dali_comparison.py", "dali_analyze.py", "dali_smoke.py")]
    declarations = []
    for file in files:
        tree = ast.parse(file.read_text(encoding="utf-8"))
        for qualified, _ in _qualified_python_functions(tree):
            declarations.append(f"{file.stem}.main" if qualified == "main" else qualified)
    text = (docs.SOURCE / "DALI-COMPARISON-FUNCTIONS.md").read_text(encoding="utf-8")
    entries = re.findall(r"^\| `([A-Za-z_][\w.]*)\(", text, re.MULTILINE)
    assert len(declarations) == 26
    assert len(entries) == len(set(entries))
    assert set(entries) == set(declarations), (set(declarations) - set(entries), set(entries) - set(declarations))


@pytest.mark.parametrize("files, guide, minimum", [
    (["system_campaign.py", "evidence.py"], "RESEARCH-CAMPAIGN-FUNCTIONS.md", 9),
    (["paper_common.py"], "RESEARCH-PROCESS-FUNCTIONS.md", 9),
    (["longitudinal_state.py", "persistent_service.py"], "RESEARCH-LIFECYCLE-FUNCTIONS.md", 16),
    (["longitudinal_manifests.py"], "RESEARCH-MANIFEST-FUNCTIONS.md", 4),
    (["longitudinal_worker.py"], "LONGITUDINAL-WORKER-FUNCTIONS.md", 8),
    (["longitudinal_comparison.py", "longitudinal_analyze.py"], "LONGITUDINAL-RUNNER-FUNCTIONS.md", 11),
    (["monai_comparison.py"], "RESEARCH-ADAPTER-FUNCTIONS.md", 16),
])
def test_research_references_partition_complete_qualified_function_inventories(files, guide, minimum):
    declarations = []
    for name in files:
        tree = ast.parse((ROOT / "scripts" / name).read_text(encoding="utf-8"))
        declarations.extend(qualified for qualified, _ in _qualified_python_functions(tree))
    text = (docs.SOURCE / guide).read_text(encoding="utf-8")
    entries = re.findall(r"^\| `([A-Za-z_][\w.]*)\(", text, re.MULTILINE)
    assert len(declarations) >= minimum, f"Research source inventory shrank: {files}"
    assert len(entries) == len(set(entries)), f"Duplicate research entry in {guide}"
    assert set(entries) == set(declarations), (
        f"Research coverage mismatch: missing={set(declarations) - set(entries)}, "
        f"unknown={set(entries) - set(declarations)}")


def test_monai_adapter_reference_includes_source_identity_lambda():
    tree = ast.parse((ROOT / "scripts/monai_comparison.py").read_text(encoding="utf-8"))
    dataset = next(node for node in tree.body if isinstance(node, ast.FunctionDef) and node.name == "dataset")
    assert sum(isinstance(node, ast.Lambda) for node in ast.walk(dataset)) == 1
    text = (docs.SOURCE / "RESEARCH-ADAPTER-FUNCTIONS.md").read_text(encoding="utf-8")
    assert "| `dataset.<lambda>(item, index)` |" in text


def test_benchmark_data_reference_matches_selected_function_partition():
    tree = ast.parse((ROOT / "clients/python/benchmark_gpu_segmentation.py").read_text(encoding="utf-8"))
    declarations = dict(_qualified_python_functions(tree))
    selected = {
        "load_sources", "load_oct5k_sources", "resolve_manifest_path", "file_sha256",
        "preprocess_sample", "preprocess_sample_with_timing", "_preprocess_sample_with_timing",
        "preprocess_oct5k_sample", "validate_semantic_mask_mode", "nearest_resize",
        "deterministic_denoise_pass", "stack_values", "pack_payload", "unpack_payload",
        "artifact_to_tensor_sample", "element_count", "dataset_checksums", "assert_equivalent_inputs",
        "dataset_integrity_summary", "batch_plan", "effective_measured_steps",
        "deterministic_parameters", "_deterministic_parameters", "configuration",
        "storage_locations", "filesystem_name", "dataset_version",
    }
    assert len(declarations) >= 138 and len(selected) == 27
    assert selected <= declarations.keys()
    text = (docs.SOURCE / "BENCHMARK-DATA-FUNCTIONS.md").read_text(encoding="utf-8")
    entries = re.findall(r"^\| `([A-Za-z_][\w.]*)\(", text, re.MULTILINE)
    assert len(entries) == len(set(entries)) and set(entries) == selected
    assert "not a complete benchmark reference" in text


def test_benchmark_training_reference_matches_selected_function_partition():
    tree = ast.parse((ROOT / "clients/python/benchmark_gpu_segmentation.py").read_text(encoding="utf-8"))
    declarations = dict(_qualified_python_functions(tree))
    selected = {
        "import_numpy_status", "accelerator_report", "unsupported_reason",
        "accelerator_backend_matches", "gpu_name_matches", "synchronize_device",
        "gpu_smoke_test", "set_seed", "create_workload_model", "create_model",
        "create_model.Block.__init__", "create_model.Block.forward",
        "create_model.SmallUNet.__init__", "create_model.SmallUNet.forward",
        "model_metadata", "validate_device_events", "run_model_warmup", "run_backend",
        "scheduled_batches", "segmentation_sanity_metrics", "train_step",
        "normalize_cpu_batch", "augment_batch", "expected_contiguous_stride",
        "tensor_layout_metadata",
    }
    assert len(selected) == 25 and selected <= declarations.keys()
    text = (docs.SOURCE / "BENCHMARK-TRAINING-FUNCTIONS.md").read_text(encoding="utf-8")
    entries = re.findall(r"^\| `([A-Za-z_][\w.]*)\(", text, re.MULTILINE)
    assert len(entries) == len(set(entries)) and set(entries) == selected
    assert "not a complete benchmark reference" in text


def test_benchmark_backend_reference_matches_complete_class_and_helpers():
    tree = ast.parse((ROOT / "clients/python/benchmark_gpu_segmentation.py").read_text(encoding="utf-8"))
    declarations = dict(_qualified_python_functions(tree))
    selected = {name for name in declarations if name.startswith("BackendContext.")}
    selected.update({"aether_cold_start_gate", "warm_backend"})
    assert len(selected) == 26 and selected <= declarations.keys()
    text = (docs.SOURCE / "BENCHMARK-BACKEND-FUNCTIONS.md").read_text(encoding="utf-8")
    entries = re.findall(r"^\| `([A-Za-z_][\w.]*)\(", text, re.MULTILINE)
    assert len(entries) == len(set(entries)) and set(entries) == selected
    assert "not a complete benchmark" in text


def test_benchmark_metrics_reference_matches_complete_sampler_and_helpers():
    tree = ast.parse((ROOT / "clients/python/benchmark_gpu_segmentation.py").read_text(encoding="utf-8"))
    declarations = dict(_qualified_python_functions(tree))
    selected = {name for name in declarations if name.startswith("GpuUtilizationSampler.")}
    selected.update({"flatten_json", "numeric_value", "is_gpu_utilization_key",
        "is_memory_utilization_key", "process_metrics_snapshot", "process_metrics_delta",
        "rss_peak_bytes", "proc_self_io"})
    assert len(selected) == 15 and selected <= declarations.keys()
    text = (docs.SOURCE / "BENCHMARK-METRICS-FUNCTIONS.md").read_text(encoding="utf-8")
    entries = re.findall(r"^\| `([A-Za-z_][\w.]*)\(", text, re.MULTILINE)
    assert len(entries) == len(set(entries)) and set(entries) == selected
    assert "not a complete benchmark" in text


def test_benchmark_runner_reference_matches_selected_partition():
    tree = ast.parse((ROOT / "clients/python/benchmark_gpu_segmentation.py").read_text(encoding="utf-8"))
    declarations = dict(_qualified_python_functions(tree))
    selected = {"main", "parse_args", "validate_args", "run_benchmark", "make_reference",
        "run_training_once", "validity_checks", "cache_invalidation_self_test", "test_cache_key",
        "validate_backend_equivalence", "LazyReferenceSequence.__init__",
        "LazyReferenceSequence.__len__", "LazyReferenceSequence.__getitem__"}
    assert len(selected) == 13 and selected <= declarations.keys()
    text = (docs.SOURCE / "BENCHMARK-RUNNER-FUNCTIONS.md").read_text(encoding="utf-8")
    entries = re.findall(r"^\| `([A-Za-z_][\w.]*)\(", text, re.MULTILINE)
    assert len(entries) == len(set(entries)) and set(entries) == selected
    assert "not a complete benchmark reference" in text


@pytest.mark.parametrize("source,selected", [
    ("BENCHMARK-ACCOUNTING-FUNCTIONS.md", {
        "summarize_backend", "summarize_steps", "cumulative_lifecycle_by_epoch",
        "summarize_tensor_layout", "batch_checksums", "epoch_checksums", "elapsed_ms",
        "cache_dynamics", "cache_invariants", "expected_cache_counts", "compare_backends",
        "compare_pair", "training_outcome", "outcome_interpretation",
        "training_break_even_epoch", "admission_model", "predicted_break_even_epoch", "ratio"}),
    ("BENCHMARK-AGGREGATION-FUNCTIONS.md", {
        "aggregate_runs", "aggregate_backend_runs", "aggregate_protocol", "aggregate_cache_dynamics",
        "aggregate_operation_metrics", "aggregate_comparisons", "aggregate_break_even",
        "aggregate_admission_models", "aggregate_outcomes", "distribution", "percentile", "mean"}),
])
def test_benchmark_accounting_partitions_match_source(source, selected):
    tree = ast.parse((ROOT / "clients/python/benchmark_gpu_segmentation.py").read_text(encoding="utf-8"))
    declarations = dict(_qualified_python_functions(tree))
    text = (docs.SOURCE / source).read_text(encoding="utf-8")
    entries = re.findall(r"^\| `([A-Za-z_][\w.]*)\(", text, re.MULTILINE)
    assert selected <= declarations.keys()
    assert len(entries) == len(set(entries)) and set(entries) == selected


def test_complete_benchmark_function_inventory_has_no_omissions_or_duplicates():
    tree = ast.parse((ROOT / "clients/python/benchmark_gpu_segmentation.py").read_text(encoding="utf-8"))
    declarations = dict(_qualified_python_functions(tree))
    entries = []
    for source in docs.SOURCE.glob("BENCHMARK-*-FUNCTIONS.md"):
        entries.extend(re.findall(r"^\| `([A-Za-z_][\w.]*)\(", source.read_text(encoding="utf-8"), re.MULTILINE))
    pipeline = (docs.SOURCE / "PYTHON-PIPELINE-FUNCTIONS.md").read_text(encoding="utf-8")
    entries.extend(name for name in re.findall(r"^\| `([A-Za-z_][\w.]*)\(", pipeline, re.MULTILINE)
                   if name == "prepared_batches" or name.startswith("prepared_batches."))
    assert len(entries) == len(set(entries)) == len(declarations) == 138
    assert set(entries) == declarations.keys()


def test_every_generated_guide_matches_current_markdown():
    for source, target, title in docs.PAGES:
        assert (docs.OUTPUT / target).read_text(encoding="utf-8") == docs.page(source, target, title)


def test_module_guide_contains_all_49_modules():
    body, headings = docs.render(docs.SOURCE / "MODULE-GUIDE.md")
    assert body.count("<tr>") == 52  # Three table headers plus 49 module rows.
    assert any(anchor == "remote-and-distributed-foundations" for anchor, _ in headings)


def test_function_index_links_every_detailed_reference():
    text = (docs.SOURCE / "FUNCTION-INDEX.md").read_text(encoding="utf-8")
    for source, _, _ in docs.PAGES:
        if source.endswith("-FUNCTIONS.md") or source == "PERSISTENT-INTERNALS.md":
            assert f"]({source})" in text, f"Reference absent from index: {source}"
    assert "](FUNCTION-INDEX.md)" in (docs.SOURCE / "README.md").read_text(encoding="utf-8")


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


def test_engine_reference_mentions_every_declared_function():
    java = shutil.which("java")
    if not java:
        pytest.skip("JDK required for compiler-tree function coverage")
    engine = ROOT / "modules/aether-engine/src/main/java/io/aetherdb/engine"
    files = [engine / name for name in [
        "Aether.java", "InMemoryAetherDatabase.java", "PersistentAetherDatabase.java",
        "SnapshotHandle.java", "ListCursor.java", "PersistentListCursor.java", "CompactionCoordinator.java",
    ]]
    files += [ROOT / "modules/aether-api/src/main/java/io/aetherdb/api" / name
              for name in ["WriteBatch.java", "result/LookupResult.java"]]
    result = subprocess.run([java, str(ROOT / "scripts/InventoryJavaFunctions.java"),
                             *map(str, files)], check=True, capture_output=True, text=True,
                            encoding="utf-8", timeout=60)
    methods = list(csv.reader(io.StringIO(result.stdout), delimiter="\t"))
    assert len(methods) >= 170
    missing = []
    for file, owner, name, line, parameters in methods:
        guide = "PERSISTENT-INTERNALS.md" if owner.startswith("CompactionCoordinator") else "ENGINE-FUNCTIONS.md"
        text = (docs.SOURCE / guide).read_text(encoding="utf-8")
        if owner.startswith("PersistentAetherDatabase"):
            text += (docs.SOURCE / "PERSISTENT-INTERNALS.md").read_text(encoding="utf-8")
        if not re.search(r"\b" + re.escape(name) + r"\b", text):
            missing.append(f"{owner}.{name}({parameters}) at {file}:{line}")
    assert missing == [], "Undocumented function names:\n" + "\n".join(missing)


def test_bulk_reference_has_signature_entries_for_every_declared_function():
    java = shutil.which("java")
    if not java:
        pytest.skip("JDK required for compiler-tree function coverage")
    files = [
        ROOT / "modules/aether-training-cache/src/main/java/io/aetherdb/training/cache/BulkArtifactWriter.java",
        ROOT / "modules/aether-engine/src/main/java/io/aetherdb/engine/EmptyStoreBulkLoader.java",
    ]
    result = subprocess.run([java, str(ROOT / "scripts/InventoryJavaFunctions.java"),
                             *map(str, files)], check=True, capture_output=True, text=True,
                            encoding="utf-8", timeout=60)
    methods = list(csv.reader(io.StringIO(result.stdout), delimiter="\t"))
    assert len(methods) >= 15
    text = (docs.SOURCE / "BULK-PUBLICATION-FUNCTIONS.md").read_text(encoding="utf-8")
    signatures = {re.sub(r"\s+", "", signature)
                  for signature in re.findall(r"`([^`\n]+\([^`\n]*\))`", text)}
    missing = []
    for file, owner, name, line, parameters in methods:
        qualified = owner if name == owner else f"{owner}.{name}"
        signature = f"{qualified}({parameters})"
        if re.sub(r"\s+", "", signature) not in signatures:
            missing.append(f"{signature} at {file}:{line}")
    assert missing == [], "Missing bulk function signature entries:\n" + "\n".join(missing)


def test_bulk_python_reference_has_qualified_entries_for_every_function():
    tree = ast.parse((ROOT / "scripts/bulk_population.py").read_text(encoding="utf-8"))
    text = (docs.SOURCE / "BULK-PUBLICATION-FUNCTIONS.md").read_text(encoding="utf-8")
    missing = []
    declarations = []

    def inspect(node, parents):
        for child in ast.iter_child_nodes(node):
            if isinstance(child, (ast.ClassDef, ast.FunctionDef, ast.AsyncFunctionDef)):
                qualified = ".".join([*parents, child.name])
                if not isinstance(child, ast.ClassDef):
                    declarations.append(qualified)
                    if not re.search(r"`" + re.escape(qualified) + r"\(", text):
                        missing.append(f"{qualified} at scripts/bulk_population.py:{child.lineno}")
                inspect(child, [*parents, child.name])
            else:
                inspect(child, parents)

    inspect(tree, [])
    assert len(declarations) >= 12
    assert missing == [], "Missing bulk Python function entries:\n" + "\n".join(missing)


def test_sstable_block_reference_mentions_every_declared_function():
    java = shutil.which("java")
    if not java:
        pytest.skip("JDK required for compiler-tree function coverage")
    base = ROOT / "modules/aether-sstable/src/main/java/io/aetherdb/sstable"
    files = [base / name for name in [
        "InternalKey.java", "block/Varint32.java", "block/RestartBlock.java",
        "block/RestartBlockScanner.java", "block/BlockEnvelope.java",
        "block/BlockHandle.java", "filter/BloomFilterV1.java",
    ]]
    result = subprocess.run([java, str(ROOT / "scripts/InventoryJavaFunctions.java"),
                             *map(str, files)], check=True, capture_output=True, text=True,
                            encoding="utf-8", timeout=60)
    methods = list(csv.reader(io.StringIO(result.stdout), delimiter="\t"))
    assert len(methods) >= 60
    text = (docs.SOURCE / "SSTABLE-BLOCK-FUNCTIONS.md").read_text(encoding="utf-8")
    missing = [f"{owner}.{name}({parameters}) at {file}:{line}"
               for file, owner, name, line, parameters in methods
               if not re.search(r"\b" + re.escape(name) + r"\b", text)]
    assert missing == [], "Undocumented function names:\n" + "\n".join(missing)


def test_sstable_read_reference_mentions_every_declared_function():
    java = shutil.which("java")
    if not java:
        pytest.skip("JDK required for compiler-tree function coverage")
    base = ROOT / "modules/aether-sstable/src/main/java/io/aetherdb/sstable"
    files = [base / name for name in [
        "SSTableReader.java", "SSTableVerifier.java", "SSTableLookup.java",
        "SSTableEntry.java", "TableFileMetadata.java", "SSTableHeaderV1.java",
        "SSTableFooterV1.java",
    ]]
    result = subprocess.run([java, str(ROOT / "scripts/InventoryJavaFunctions.java"),
                             *map(str, files)], check=True, capture_output=True, text=True,
                            encoding="utf-8", timeout=60)
    methods = list(csv.reader(io.StringIO(result.stdout), delimiter="\t"))
    assert len(methods) >= 50
    text = (docs.SOURCE / "SSTABLE-READ-FUNCTIONS.md").read_text(encoding="utf-8")
    missing = [f"{owner}.{name}({parameters}) at {file}:{line}"
               for file, owner, name, line, parameters in methods
               if not re.search(r"\b" + re.escape(name) + r"\b", text)]
    assert missing == [], "Undocumented function names:\n" + "\n".join(missing)


def test_sstable_build_reference_mentions_every_declared_function():
    java = shutil.which("java")
    if not java:
        pytest.skip("JDK required for compiler-tree function coverage")
    base = ROOT / "modules/aether-sstable/src/main/java/io/aetherdb/sstable"
    files = [base / name for name in [
        "SSTableBuilder.java", "BulkInstallSupport.java", "SSTableFinishTrace.java",
        "SSTableVerificationTrace.java", "jfr/BulkPhaseEvent.java",
    ]]
    result = subprocess.run([java, str(ROOT / "scripts/InventoryJavaFunctions.java"),
                             *map(str, files)], check=True, capture_output=True, text=True,
                            encoding="utf-8", timeout=60)
    methods = list(csv.reader(io.StringIO(result.stdout), delimiter="\t"))
    assert len(methods) >= 35
    text = (docs.SOURCE / "SSTABLE-BUILD-FUNCTIONS.md").read_text(encoding="utf-8")
    missing = [f"{owner}.{name}({parameters}) at {file}:{line}"
               for file, owner, name, line, parameters in methods
               if not re.search(r"\b" + re.escape(name) + r"\b", text)]
    assert missing == [], "Undocumented function names:\n" + "\n".join(missing)


def test_manifest_version_reference_mentions_every_declared_function():
    java = shutil.which("java")
    if not java:
        pytest.skip("JDK required for compiler-tree function coverage")
    base = ROOT / "modules/aether-sstable/src/main/java/io/aetherdb/sstable/manifest"
    files = [base / name for name in [
        "VersionSet.java", "Version.java", "ManifestEdit.java",
        "ManifestFileMetadata.java", "ManifestDeletion.java", "ManifestInspection.java",
    ]]
    result = subprocess.run([java, str(ROOT / "scripts/InventoryJavaFunctions.java"),
                             *map(str, files)], check=True, capture_output=True, text=True,
                            encoding="utf-8", timeout=60)
    methods = list(csv.reader(io.StringIO(result.stdout), delimiter="\t"))
    assert len(methods) >= 40
    text = (docs.SOURCE / "MANIFEST-VERSION-FUNCTIONS.md").read_text(encoding="utf-8")
    missing = [f"{owner}.{name}({parameters}) at {file}:{line}"
               for file, owner, name, line, parameters in methods
               if not re.search(r"\b" + re.escape(name) + r"\b", text)]
    assert missing == [], "Undocumented function names:\n" + "\n".join(missing)


def test_manifest_codec_reference_mentions_every_declared_function():
    java = shutil.which("java")
    if not java:
        pytest.skip("JDK required for compiler-tree function coverage")
    base = ROOT / "modules/aether-sstable/src/main/java/io/aetherdb/sstable/manifest"
    files = [base / name for name in [
        "ManifestCodecV1.java", "CurrentFileV1.java", "ManifestHeaderV1.java",
        "ManifestCorruptionException.java",
    ]]
    files.append(ROOT / "modules/aether-format/src/main/java/io/aetherdb/format/checksum/MaskedCrc32c.java")
    result = subprocess.run([java, str(ROOT / "scripts/InventoryJavaFunctions.java"),
                             *map(str, files)], check=True, capture_output=True, text=True,
                            encoding="utf-8", timeout=60)
    methods = list(csv.reader(io.StringIO(result.stdout), delimiter="\t"))
    assert len(methods) >= 20
    text = (docs.SOURCE / "MANIFEST-CODEC-FUNCTIONS.md").read_text(encoding="utf-8")
    missing = [f"{owner}.{name}({parameters}) at {file}:{line}"
               for file, owner, name, line, parameters in methods
               if not re.search(r"\b" + re.escape(name) + r"\b", text)]
    assert missing == [], "Undocumented function names:\n" + "\n".join(missing)


def test_wal_reference_mentions_every_declared_function():
    java = shutil.which("java")
    if not java:
        pytest.skip("JDK required for compiler-tree function coverage")
    base = ROOT / "modules/aether-wal/src/main/java/io/aetherdb/wal/format"
    files = [base / name for name in [
        "WalFormatV1.java", "WalSegmentHeader.java", "WalLogicalGroupCodec.java",
        "WalFragmentCodec.java", "WalCorruptionException.java",
    ]]
    result = subprocess.run([java, str(ROOT / "scripts/InventoryJavaFunctions.java"),
                             *map(str, files)], check=True, capture_output=True, text=True,
                            encoding="utf-8", timeout=60)
    methods = list(csv.reader(io.StringIO(result.stdout), delimiter="\t"))
    assert len(methods) >= 20
    text = (docs.SOURCE / "WAL-FUNCTIONS.md").read_text(encoding="utf-8")
    missing = [f"{owner}.{name}({parameters}) at {file}:{line}"
               for file, owner, name, line, parameters in methods
               if not re.search(r"\b" + re.escape(name) + r"\b", text)]
    assert missing == [], "Undocumented function names:\n" + "\n".join(missing)


def test_heap_mvcc_reference_mentions_every_declared_function():
    java = shutil.which("java")
    if not java:
        pytest.skip("JDK required for compiler-tree function coverage")
    base = ROOT / "modules/aether-memtable/src/main/java/io/aetherdb/memtable/reference"
    files = [base / name for name in [
        "VersionedKeyValueStore.java", "VersionedRecord.java", "ByteKey.java",
        "SequenceSource.java", "UnsignedBytes.java",
    ]]
    result = subprocess.run([java, str(ROOT / "scripts/InventoryJavaFunctions.java"),
                             *map(str, files)], check=True, capture_output=True, text=True,
                            encoding="utf-8", timeout=60)
    methods = list(csv.reader(io.StringIO(result.stdout), delimiter="\t"))
    assert len(methods) >= 20
    text = (docs.SOURCE / "MVCC-REFERENCE-FUNCTIONS.md").read_text(encoding="utf-8")
    missing = [f"{owner}.{name}({parameters}) at {file}:{line}"
               for file, owner, name, line, parameters in methods
               if not re.search(r"\b" + re.escape(name) + r"\b", text)]
    assert missing == [], "Undocumented function names:\n" + "\n".join(missing)


def test_collection_schema_reference_mentions_every_declared_function():
    java = shutil.which("java")
    if not java:
        pytest.skip("JDK required for compiler-tree function coverage")
    files = [ROOT / "modules/aether-codec/src/main/java/io/aetherdb/codec" / name
             for name in ["CollectionMetadata.java", "SchemaCompatibilityChecker.java"]]
    files += [ROOT / "modules/aether-api/src/main/java/io/aetherdb/api/typed" / name
              for name in ["CollectionDefinition.java", "CollectionCapability.java"]]
    result = subprocess.run([java, str(ROOT / "scripts/InventoryJavaFunctions.java"),
                             *map(str, files)], check=True, capture_output=True, text=True,
                            encoding="utf-8", timeout=60)
    methods = list(csv.reader(io.StringIO(result.stdout), delimiter="\t"))
    assert len(methods) >= 15
    text = (docs.SOURCE / "COLLECTION-SCHEMA-FUNCTIONS.md").read_text(encoding="utf-8")
    missing = [f"{owner}.{name}({parameters}) at {file}:{line}"
               for file, owner, name, line, parameters in methods
               if not re.search(r"\b" + re.escape(name) + r"\b", text)]
    assert missing == [], "Undocumented function names:\n" + "\n".join(missing)


def test_typed_value_reference_mentions_every_declared_function():
    java = shutil.which("java")
    if not java:
        pytest.skip("JDK required for compiler-tree function coverage")
    files = [ROOT / "modules/aether-codec/src/main/java/io/aetherdb/codec" / name
             for name in ["BuiltInValueCodecs.java", "TypedValueEnvelope.java"]]
    files.append(ROOT / "modules/aether-api/src/main/java/io/aetherdb/api/typed/ValueCodec.java")
    result = subprocess.run([java, str(ROOT / "scripts/InventoryJavaFunctions.java"),
                             *map(str, files)], check=True, capture_output=True, text=True,
                            encoding="utf-8", timeout=60)
    methods = list(csv.reader(io.StringIO(result.stdout), delimiter="\t"))
    assert len(methods) >= 15
    text = (docs.SOURCE / "TYPED-VALUE-FUNCTIONS.md").read_text(encoding="utf-8")
    missing = [f"{owner}.{name}({parameters}) at {file}:{line}"
               for file, owner, name, line, parameters in methods
               if not re.search(r"\b" + re.escape(name) + r"\b", text)]
    assert missing == [], "Undocumented function names:\n" + "\n".join(missing)


def test_typed_key_reference_mentions_every_declared_function():
    java = shutil.which("java")
    if not java:
        pytest.skip("JDK required for compiler-tree function coverage")
    files = [ROOT / "modules/aether-codec/src/main/java/io/aetherdb/codec" / name
             for name in ["BuiltInKeyCodecs.java", "TypedKeyEnvelope.java"]]
    files += [ROOT / "modules/aether-api/src/main/java/io/aetherdb/api/typed" / name
              for name in ["KeyCodec.java", "OrderedKeyCodec.java", "CollectionId.java"]]
    result = subprocess.run([java, str(ROOT / "scripts/InventoryJavaFunctions.java"),
                             *map(str, files)], check=True, capture_output=True, text=True,
                            encoding="utf-8", timeout=60)
    methods = list(csv.reader(io.StringIO(result.stdout), delimiter="\t"))
    assert len(methods) >= 20
    text = (docs.SOURCE / "TYPED-KEY-FUNCTIONS.md").read_text(encoding="utf-8")
    missing = [f"{owner}.{name}({parameters}) at {file}:{line}"
               for file, owner, name, line, parameters in methods
               if not re.search(r"\b" + re.escape(name) + r"\b", text)]
    assert missing == [], "Undocumented function names:\n" + "\n".join(missing)


def test_format_catalog_reference_mentions_every_declared_function():
    java = shutil.which("java")
    if not java:
        pytest.skip("JDK required for compiler-tree function coverage")
    files = list((ROOT / "modules/aether-format/src/main/java/io/aetherdb/format/catalog").glob("*.java"))
    result = subprocess.run([java, str(ROOT / "scripts/InventoryJavaFunctions.java"),
                             *map(str, files)], check=True, capture_output=True, text=True,
                            encoding="utf-8", timeout=60)
    methods = list(csv.reader(io.StringIO(result.stdout), delimiter="\t"))
    assert len(methods) >= 10
    text = (docs.SOURCE / "FORMAT-CATALOG-FUNCTIONS.md").read_text(encoding="utf-8")
    missing = [f"{owner}.{name}({parameters}) at {file}:{line}"
               for file, owner, name, line, parameters in methods
               if not re.search(r"\b" + re.escape(name) + r"\b", text)]
    assert missing == [], "Undocumented function names:\n" + "\n".join(missing)


def test_block_cache_reference_mentions_every_declared_function():
    java = shutil.which("java")
    if not java:
        pytest.skip("JDK required for compiler-tree function coverage")
    files = list((ROOT / "modules/aether-cache/src/main/java/io/aetherdb/cache").glob("*.java"))
    result = subprocess.run([java, str(ROOT / "scripts/InventoryJavaFunctions.java"),
                             *map(str, files)], check=True, capture_output=True, text=True,
                            encoding="utf-8", timeout=60)
    methods = list(csv.reader(io.StringIO(result.stdout), delimiter="\t"))
    assert len(methods) >= 25
    text = (docs.SOURCE / "BLOCK-CACHE-FUNCTIONS.md").read_text(encoding="utf-8")
    missing = [f"{owner}.{name}({parameters}) at {file}:{line}"
               for file, owner, name, line, parameters in methods
               if not re.search(r"\b" + re.escape(name) + r"\b", text)]
    assert missing == [], "Undocumented function names:\n" + "\n".join(missing)


def test_reliability_reference_mentions_every_declared_function():
    java = shutil.which("java")
    if not java:
        pytest.skip("JDK required for compiler-tree function coverage")
    files = list((ROOT / "modules/aether-reliability/src/main/java/io/aetherdb/reliability").glob("*.java"))
    result = subprocess.run([java, str(ROOT / "scripts/InventoryJavaFunctions.java"),
                             *map(str, files)], check=True, capture_output=True, text=True,
                            encoding="utf-8", timeout=60)
    methods = list(csv.reader(io.StringIO(result.stdout), delimiter="\t"))
    assert len(methods) >= 25
    text = (docs.SOURCE / "RELIABILITY-FUNCTIONS.md").read_text(encoding="utf-8")
    missing = [f"{owner}.{name}({parameters}) at {file}:{line}"
               for file, owner, name, line, parameters in methods
               if not re.search(r"\b" + re.escape(name) + r"\b", text)]
    assert missing == [], "Undocumented function names:\n" + "\n".join(missing)


def test_admission_reference_mentions_every_declared_function():
    java = shutil.which("java")
    if not java:
        pytest.skip("JDK required for compiler-tree function coverage")
    files = list((ROOT / "modules/aether-admission/src/main/java/io/aetherdb/admission").glob("*.java"))
    result = subprocess.run([java, str(ROOT / "scripts/InventoryJavaFunctions.java"),
                             *map(str, files)], check=True, capture_output=True, text=True,
                            encoding="utf-8", timeout=60)
    methods = list(csv.reader(io.StringIO(result.stdout), delimiter="\t"))
    assert len(methods) >= 10
    text = (docs.SOURCE / "ADMISSION-FUNCTIONS.md").read_text(encoding="utf-8")
    missing = [f"{owner}.{name}({parameters}) at {file}:{line}"
               for file, owner, name, line, parameters in methods
               if not re.search(r"\b" + re.escape(name) + r"\b", text)]
    assert missing == [], "Undocumented function names:\n" + "\n".join(missing)


def test_write_pressure_reference_mentions_every_declared_function():
    java = shutil.which("java")
    if not java:
        pytest.skip("JDK required for compiler-tree function coverage")
    files = list((ROOT / "modules/aether-lsm/src/main/java/io/aetherdb/lsm/pressure").glob("*.java"))
    result = subprocess.run([java, str(ROOT / "scripts/InventoryJavaFunctions.java"),
                             *map(str, files)], check=True, capture_output=True, text=True,
                            encoding="utf-8", timeout=60)
    methods = list(csv.reader(io.StringIO(result.stdout), delimiter="\t"))
    assert len(methods) >= 10
    text = (docs.SOURCE / "WRITE-PRESSURE-FUNCTIONS.md").read_text(encoding="utf-8")
    missing = [f"{owner}.{name}({parameters}) at {file}:{line}"
               for file, owner, name, line, parameters in methods
               if not re.search(r"\b" + re.escape(name) + r"\b", text)]
    assert missing == [], "Undocumented function names:\n" + "\n".join(missing)


def test_read_view_reference_mentions_every_declared_function():
    java = shutil.which("java")
    if not java:
        pytest.skip("JDK required for compiler-tree function coverage")
    base = ROOT / "modules/aether-lsm/src/main/java/io/aetherdb/lsm/read"
    files = list(base.glob("*.java"))
    result = subprocess.run([java, str(ROOT / "scripts/InventoryJavaFunctions.java"),
                             *map(str, files)], check=True, capture_output=True, text=True,
                            encoding="utf-8", timeout=60)
    methods = list(csv.reader(io.StringIO(result.stdout), delimiter="\t"))
    assert len(methods) >= 30
    text = (docs.SOURCE / "READ-VIEW-FUNCTIONS.md").read_text(encoding="utf-8")
    missing = [f"{owner}.{name}({parameters}) at {file}:{line}"
               for file, owner, name, line, parameters in methods
               if not re.search(r"\b" + re.escape(name) + r"\b", text)]
    assert missing == [], "Undocumented function names:\n" + "\n".join(missing)


def test_lsm_iterator_reference_mentions_every_declared_function():
    java = shutil.which("java")
    if not java:
        pytest.skip("JDK required for compiler-tree function coverage")
    base = ROOT / "modules/aether-lsm/src/main/java/io/aetherdb/lsm"
    files = list((base / "iterator").glob("*.java"))
    files += [base / "compaction" / name for name in [
        "CompactionDroppingIterator.java", "BaseLevelKeyChecker.java",
    ]]
    result = subprocess.run([java, str(ROOT / "scripts/InventoryJavaFunctions.java"),
                             *map(str, files)], check=True, capture_output=True, text=True,
                            encoding="utf-8", timeout=60)
    methods = list(csv.reader(io.StringIO(result.stdout), delimiter="\t"))
    assert len(methods) >= 35
    text = (docs.SOURCE / "LSM-ITERATOR-FUNCTIONS.md").read_text(encoding="utf-8")
    missing = [f"{owner}.{name}({parameters}) at {file}:{line}"
               for file, owner, name, line, parameters in methods
               if not re.search(r"\b" + re.escape(name) + r"\b", text)]
    assert missing == [], "Undocumented function names:\n" + "\n".join(missing)


def test_compaction_planning_reference_mentions_every_declared_function():
    java = shutil.which("java")
    if not java:
        pytest.skip("JDK required for compiler-tree function coverage")
    base = ROOT / "modules/aether-lsm/src/main/java/io/aetherdb/lsm/compaction"
    files = [base / name for name in [
        "LevelCompactionConfig.java", "CompactionFile.java", "VersionInventory.java",
        "CompactionScoreCalculator.java", "CompactionScores.java", "CompactionPickerV1.java",
        "CompactionPlan.java", "CompactionRangeRegistry.java", "CompactionReason.java",
    ]]
    result = subprocess.run([java, str(ROOT / "scripts/InventoryJavaFunctions.java"),
                             *map(str, files)], check=True, capture_output=True, text=True,
                            encoding="utf-8", timeout=60)
    methods = list(csv.reader(io.StringIO(result.stdout), delimiter="\t"))
    assert len(methods) >= 40
    text = (docs.SOURCE / "COMPACTION-PLANNING-FUNCTIONS.md").read_text(encoding="utf-8")
    missing = [f"{owner}.{name}({parameters}) at {file}:{line}"
               for file, owner, name, line, parameters in methods
               if not re.search(r"\b" + re.escape(name) + r"\b", text)]
    assert missing == [], "Undocumented function names:\n" + "\n".join(missing)


def test_native_record_reference_mentions_every_declared_function():
    java = shutil.which("java")
    if not java:
        pytest.skip("JDK required for compiler-tree function coverage")
    base = ROOT / "modules/aether-memory/src/main/java/io/aetherdb/memory"
    files = [base / name for name in [
        "NativeAccess.java", "NativeRecordFormatV1.java", "NativeRecordWriter.java",
        "NativeRecordReader.java", "NativeRecordView.java", "NativeRecordCorruptionException.java",
    ]]
    result = subprocess.run([java, str(ROOT / "scripts/InventoryJavaFunctions.java"),
                             *map(str, files)], check=True, capture_output=True, text=True,
                            encoding="utf-8", timeout=60)
    methods = list(csv.reader(io.StringIO(result.stdout), delimiter="\t"))
    assert len(methods) >= 35
    text = (docs.SOURCE / "NATIVE-RECORD-FUNCTIONS.md").read_text(encoding="utf-8")
    missing = [f"{owner}.{name}({parameters}) at {file}:{line}"
               for file, owner, name, line, parameters in methods
               if not re.search(r"\b" + re.escape(name) + r"\b", text)]
    assert missing == [], "Undocumented function names:\n" + "\n".join(missing)


def test_native_region_reference_mentions_every_declared_function():
    java = shutil.which("java")
    if not java:
        pytest.skip("JDK required for compiler-tree function coverage")
    base = ROOT / "modules/aether-memory/src/main/java/io/aetherdb/memory"
    files = [base / name for name in [
        "RegionConfig.java", "NativeMemoryBudget.java", "NativeRegionFactory.java",
        "DefaultNativeRegionFactory.java", "NativeRegion.java", "FfmNativeRegion.java",
        "NativeAllocator.java", "MonotonicNativeAllocator.java", "NativeAllocationException.java",
    ]]
    result = subprocess.run([java, str(ROOT / "scripts/InventoryJavaFunctions.java"),
                             *map(str, files)], check=True, capture_output=True, text=True,
                            encoding="utf-8", timeout=60)
    methods = list(csv.reader(io.StringIO(result.stdout), delimiter="\t"))
    assert len(methods) >= 40
    text = (docs.SOURCE / "NATIVE-REGION-FUNCTIONS.md").read_text(encoding="utf-8")
    missing = [f"{owner}.{name}({parameters}) at {file}:{line}"
               for file, owner, name, line, parameters in methods
               if not re.search(r"\b" + re.escape(name) + r"\b", text)]
    assert missing == [], "Undocumented function names:\n" + "\n".join(missing)


def test_backup_restore_reference_mentions_every_declared_function():
    java = shutil.which("java")
    if not java:
        pytest.skip("JDK required for compiler-tree function coverage")
    base = ROOT / "modules/aether-io/src/main/java/io/aetherdb/io"
    files = [base / name for name in [
        "BackupRestorePreflight.java", "BackupRestorePreflightOptions.java",
        "BackupRestorePreflightReport.java", "BackupRestoreWriter.java",
        "BackupRestoreResult.java", "BackupRestoreMode.java",
    ]]
    result = subprocess.run([java, str(ROOT / "scripts/InventoryJavaFunctions.java"),
                             *map(str, files)], check=True, capture_output=True, text=True,
                            encoding="utf-8", timeout=60)
    methods = list(csv.reader(io.StringIO(result.stdout), delimiter="\t"))
    assert len(methods) >= 15
    text = (docs.SOURCE / "BACKUP-RESTORE-FUNCTIONS.md").read_text(encoding="utf-8")
    missing = [f"{owner}.{name}({parameters}) at {file}:{line}"
               for file, owner, name, line, parameters in methods
               if not re.search(r"\b" + re.escape(name) + r"\b", text)]
    assert missing == [], "Undocumented function names:\n" + "\n".join(missing)


def test_backup_archive_reference_mentions_every_declared_function():
    java = shutil.which("java")
    if not java:
        pytest.skip("JDK required for compiler-tree function coverage")
    base = ROOT / "modules/aether-io/src/main/java/io/aetherdb/io"
    files = [base / name for name in [
        "BackupManifestV1.java", "BackupManifestObject.java", "BackupObjectKind.java",
        "BackupArchiveV1.java", "BackupArchiveContents.java",
    ]]
    result = subprocess.run([java, str(ROOT / "scripts/InventoryJavaFunctions.java"),
                             *map(str, files)], check=True, capture_output=True, text=True,
                            encoding="utf-8", timeout=60)
    methods = list(csv.reader(io.StringIO(result.stdout), delimiter="\t"))
    assert len(methods) >= 30
    text = (docs.SOURCE / "BACKUP-ARCHIVE-FUNCTIONS.md").read_text(encoding="utf-8")
    missing = [f"{owner}.{name}({parameters}) at {file}:{line}"
               for file, owner, name, line, parameters in methods
               if not re.search(r"\b" + re.escape(name) + r"\b", text)]
    assert missing == [], "Undocumented function names:\n" + "\n".join(missing)


def _python_declarations(files):
    declarations = []
    for file in files:
        tree = ast.parse(file.read_text(encoding="utf-8"), filename=str(file))
        for node in ast.walk(tree):
            if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)):
                declarations.append((file, node.name, node.lineno))
    return declarations


def test_python_client_references_collectively_mention_every_declared_function():
    source = ROOT / "clients/python/aether_training_cache/client.py"
    methods = _python_declarations([source])
    assert len(methods) >= 45
    text = "\n".join((docs.SOURCE / name).read_text(encoding="utf-8") for name in [
        "PYTHON-CLIENT-FUNCTIONS.md", "PYTHON-CACHE-VALUE-FUNCTIONS.md",
        "PYTHON-MAPPING-FUNCTIONS.md",
    ])
    missing = [f"{name} at {file}:{line}" for file, name, line in methods
               if not re.search(r"\b" + re.escape(name) + r"\b", text)]
    assert missing == [], "Undocumented function names:\n" + "\n".join(missing)


def test_python_batch_reference_mentions_every_declared_function():
    base = ROOT / "clients/python/aether_training_cache"
    methods = _python_declarations([base / "batch_values.py", base / "batch.py"])
    assert len(methods) == 4
    text = (docs.SOURCE / "PYTHON-CACHE-VALUE-FUNCTIONS.md").read_text(encoding="utf-8")
    missing = [f"{name} at {file}:{line}" for file, name, line in methods
               if not re.search(r"\b" + re.escape(name) + r"\b", text)]
    assert missing == [], "Undocumented function names:\n" + "\n".join(missing)


def test_python_mapping_reference_mentions_every_declared_function():
    base = ROOT / "clients/python/aether_training_cache"
    methods = _python_declarations([base / "mapping.py", base / "tensor.py"])
    assert len(methods) >= 25
    text = (docs.SOURCE / "PYTHON-MAPPING-FUNCTIONS.md").read_text(encoding="utf-8")
    missing = [f"{name} at {file}:{line}" for file, name, line in methods
               if not re.search(r"\b" + re.escape(name) + r"\b", text)]
    assert missing == [], "Undocumented function names:\n" + "\n".join(missing)


def test_training_daemon_reference_mentions_every_declared_function():
    java = shutil.which("java")
    if not java:
        pytest.skip("JDK required for compiler-tree function coverage")
    base = ROOT / "modules/aether-training-cache/src/main/java/io/aetherdb/training/cache"
    files = [base / name for name in [
        "TrainingCacheDaemon.java", "TrainingCacheUnixDaemon.java", "TrainingCacheTlsDaemon.java",
    ]]
    result = subprocess.run([java, str(ROOT / "scripts/InventoryJavaFunctions.java"),
                             *map(str, files)], check=True, capture_output=True, text=True,
                            encoding="utf-8", timeout=60)
    methods = list(csv.reader(io.StringIO(result.stdout), delimiter="\t"))
    assert len(methods) >= 21
    text = (docs.SOURCE / "TRAINING-DAEMON-FUNCTIONS.md").read_text(encoding="utf-8")
    missing = [f"{owner}.{name}({parameters}) at {file}:{line}"
               for file, owner, name, line, parameters in methods
               if not re.search(r"\b" + re.escape(name) + r"\b", text)]
    assert missing == [], "Undocumented function names:\n" + "\n".join(missing)


def test_training_protocol_reference_mentions_every_declared_function():
    java = shutil.which("java")
    if not java:
        pytest.skip("JDK required for compiler-tree function coverage")
    base = ROOT / "modules/aether-training-cache/src/main/java/io/aetherdb/training/cache"
    files = [base / name for name in [
        "TrainingCacheProtocol.java", "TrainingCacheProtocolMetrics.java",
        "TrainingCacheRequestTrace.java", "DiagnosticJson.java",
    ]]
    result = subprocess.run([java, str(ROOT / "scripts/InventoryJavaFunctions.java"),
                             *map(str, files)], check=True, capture_output=True, text=True,
                            encoding="utf-8", timeout=60)
    methods = list(csv.reader(io.StringIO(result.stdout), delimiter="\t"))
    assert len(methods) >= 28
    text = (docs.SOURCE / "TRAINING-PROTOCOL-FUNCTIONS.md").read_text(encoding="utf-8")
    missing = [f"{owner}.{name}({parameters}) at {file}:{line}"
               for file, owner, name, line, parameters in methods
               if not re.search(r"\b" + re.escape(name) + r"\b", text)]
    assert missing == [], "Undocumented function names:\n" + "\n".join(missing)


def test_training_identity_reference_mentions_every_declared_function():
    java = shutil.which("java")
    if not java:
        pytest.skip("JDK required for compiler-tree function coverage")
    base = ROOT / "modules/aether-training-cache/src/main/java/io/aetherdb/training/cache"
    files = [base / name for name in [
        "TransformationFingerprint.java", "CacheKey.java", "CacheEntry.java",
        "TrainingCacheStoragePolicy.java", "TrainingCacheDurability.java",
    ]]
    result = subprocess.run([java, str(ROOT / "scripts/InventoryJavaFunctions.java"),
                             *map(str, files)], check=True, capture_output=True, text=True,
                            encoding="utf-8", timeout=60)
    methods = list(csv.reader(io.StringIO(result.stdout), delimiter="\t"))
    assert len(methods) >= 20
    text = (docs.SOURCE / "TRAINING-IDENTITY-FUNCTIONS.md").read_text(encoding="utf-8")
    missing = [f"{owner}.{name}({parameters}) at {file}:{line}"
               for file, owner, name, line, parameters in methods
               if not re.search(r"\b" + re.escape(name) + r"\b", text)]
    assert missing == [], "Undocumented function names:\n" + "\n".join(missing)


def test_training_cache_reference_mentions_every_declared_function():
    java = shutil.which("java")
    if not java:
        pytest.skip("JDK required for compiler-tree function coverage")
    base = ROOT / "modules/aether-training-cache/src/main/java/io/aetherdb/training/cache"
    files = [base / name for name in [
        "TrainingCache.java", "TrainingCacheSegmentStore.java", "SegmentReference.java",
        "BatchValueResult.java", "TrainingCacheLatency.java", "TrainingCacheMetrics.java",
    ]]
    result = subprocess.run([java, str(ROOT / "scripts/InventoryJavaFunctions.java"),
                             *map(str, files)], check=True, capture_output=True, text=True,
                            encoding="utf-8", timeout=60)
    methods = list(csv.reader(io.StringIO(result.stdout), delimiter="\t"))
    assert len(methods) >= 60
    text = (docs.SOURCE / "TRAINING-CACHE-FUNCTIONS.md").read_text(encoding="utf-8")
    missing = [f"{owner}.{name}({parameters}) at {file}:{line}"
               for file, owner, name, line, parameters in methods
               if not re.search(r"\b" + re.escape(name) + r"\b", text)]
    assert missing == [], "Undocumented function names:\n" + "\n".join(missing)


def test_config_change_reference_mentions_every_declared_function():
    java = shutil.which("java")
    if not java:
        pytest.skip("JDK required for compiler-tree function coverage")
    base = ROOT / "modules/aether-config/src/main/java/io/aetherdb/config"
    files = [base / name for name in [
        "AetherConfigHotReloadManager.java", "AetherConfigState.java",
        "ConfigReloadChange.java", "ConfigReloadDecision.java",
        "ClusterConfigCompatibilityChecker.java", "ClusterConfigMember.java",
        "ClusterConfigSettingChange.java", "ClusterConfigChangeProposal.java",
        "ClusterConfigCompatibilityIssue.java", "ClusterConfigCompatibilityReport.java",
    ]]
    result = subprocess.run([java, str(ROOT / "scripts/InventoryJavaFunctions.java"),
                             *map(str, files)], check=True, capture_output=True, text=True,
                            encoding="utf-8", timeout=60)
    methods = list(csv.reader(io.StringIO(result.stdout), delimiter="\t"))
    assert len(methods) >= 20
    text = (docs.SOURCE / "CONFIG-CHANGE-FUNCTIONS.md").read_text(encoding="utf-8")
    missing = [f"{owner}.{name}({parameters}) at {file}:{line}"
               for file, owner, name, line, parameters in methods
               if not re.search(r"\b" + re.escape(name) + r"\b", text)]
    assert missing == [], "Undocumented function names:\n" + "\n".join(missing)


def test_config_loading_reference_mentions_every_declared_function():
    java = shutil.which("java")
    if not java:
        pytest.skip("JDK required for compiler-tree function coverage")
    base = ROOT / "modules/aether-config/src/main/java/io/aetherdb/config"
    files = [base / name for name in [
        "AetherConfiguration.java", "ConfigSetting.java", "ConfigType.java",
        "ConfigScope.java", "ConfigSource.java", "ConfigValidationException.java",
        "AetherConfigRegistry.java", "AetherConfigLoader.java", "AetherConfigValidator.java",
    ]]
    result = subprocess.run([java, str(ROOT / "scripts/InventoryJavaFunctions.java"),
                             *map(str, files)], check=True, capture_output=True, text=True,
                            encoding="utf-8", timeout=60)
    methods = list(csv.reader(io.StringIO(result.stdout), delimiter="\t"))
    assert len(methods) >= 25
    text = (docs.SOURCE / "CONFIG-LOADING-FUNCTIONS.md").read_text(encoding="utf-8")
    missing = [f"{owner}.{name}({parameters}) at {file}:{line}"
               for file, owner, name, line, parameters in methods
               if not re.search(r"\b" + re.escape(name) + r"\b", text)]
    assert missing == [], "Undocumented function names:\n" + "\n".join(missing)


def test_filesystem_identity_reference_mentions_every_declared_function():
    java = shutil.which("java")
    if not java:
        pytest.skip("JDK required for compiler-tree function coverage")
    base = ROOT / "modules/aether-io/src/main/java/io/aetherdb/io"
    files = [base / name for name in [
        "DatabaseLock.java", "PathSecurityValidator.java", "DatabaseIdentityV1.java",
        "FormatOptionsV1.java", "CheckpointMetadataV1.java",
    ]]
    result = subprocess.run([java, str(ROOT / "scripts/InventoryJavaFunctions.java"),
                             *map(str, files)], check=True, capture_output=True, text=True,
                            encoding="utf-8", timeout=60)
    methods = list(csv.reader(io.StringIO(result.stdout), delimiter="\t"))
    assert len(methods) >= 20
    text = (docs.SOURCE / "FILESYSTEM-IDENTITY-FUNCTIONS.md").read_text(encoding="utf-8")
    missing = [f"{owner}.{name}({parameters}) at {file}:{line}"
               for file, owner, name, line, parameters in methods
               if not re.search(r"\b" + re.escape(name) + r"\b", text)]
    assert missing == [], "Undocumented function names:\n" + "\n".join(missing)


def test_processor_references_collectively_mention_every_declared_function():
    java = shutil.which("java")
    if not java:
        pytest.skip("JDK required for compiler-tree function coverage")
    source = ROOT / "modules/aether-codec-processor/src/main/java/io/aetherdb/codec/processor/AetherRecordProcessor.java"
    result = subprocess.run([java, str(ROOT / "scripts/InventoryJavaFunctions.java"), str(source)],
                            check=True, capture_output=True, text=True, encoding="utf-8", timeout=60)
    methods = list(csv.reader(io.StringIO(result.stdout), delimiter="\t"))
    assert len(methods) >= 35
    text = "\n".join((docs.SOURCE / name).read_text(encoding="utf-8") for name in [
        "SCHEMA-ANNOTATION-FUNCTIONS.md", "SCHEMA-RESOURCE-FUNCTIONS.md",
        "CODEC-GENERATION-FUNCTIONS.md",
    ])
    missing = [f"{owner}.{name}({parameters}) at {file}:{line}"
               for file, owner, name, line, parameters in methods
               if not re.search(r"\b" + re.escape(name) + r"\b", text)]
    assert missing == [], "Undocumented function names:\n" + "\n".join(missing)


def test_schema_resource_reference_mentions_selected_processor_functions():
    java = shutil.which("java")
    if not java:
        pytest.skip("JDK required for compiler-tree function coverage")
    source = ROOT / "modules/aether-codec-processor/src/main/java/io/aetherdb/codec/processor/AetherRecordProcessor.java"
    result = subprocess.run([java, str(ROOT / "scripts/InventoryJavaFunctions.java"), str(source)],
                            check=True, capture_output=True, text=True, encoding="utf-8", timeout=60)
    declared = {row[2] for row in csv.reader(io.StringIO(result.stdout), delimiter="\t")}
    selected = {
        "removedAndReservedIds", "retiredFields", "loadLock", "writeProvider",
        "writeDescriptor", "writeRegistrationResources", "writeSchemaProposals",
        "proposalJson", "wireName", "descriptor", "maximumBytes", "sha256",
        "byteLiterals", "hex",
    }
    assert selected <= declared
    text = (docs.SOURCE / "SCHEMA-RESOURCE-FUNCTIONS.md").read_text(encoding="utf-8")
    missing = [name for name in selected if not re.search(r"\b" + re.escape(name) + r"\b", text)]
    assert missing == [], "Undocumented function names: " + ", ".join(missing)


def test_schema_annotation_reference_mentions_members_and_selected_processor_functions():
    java = shutil.which("java")
    if not java:
        pytest.skip("JDK required for compiler-tree function coverage")
    base = ROOT / "modules/aether-codec-annotations/src/main/java/io/aetherdb/codec/annotation"
    files = list(base.glob("*.java"))
    assert len(files) == 6
    result = subprocess.run([java, str(ROOT / "scripts/InventoryJavaFunctions.java"),
                             *map(str, files)], check=True, capture_output=True, text=True,
                            encoding="utf-8", timeout=60)
    methods = list(csv.reader(io.StringIO(result.stdout), delimiter="\t"))
    assert len(methods) == 9
    text = (docs.SOURCE / "SCHEMA-ANNOTATION-FUNCTIONS.md").read_text(encoding="utf-8")
    names = [method[2] for method in methods] + [
        "AetherRecordProcessor", "getSupportedSourceVersion", "process",
        "validateAndGenerate", "isProposalMode", "nextFieldId", "resolveType",
        "resolved", "supportedContainerType", "supportedElementType", "genericArguments",
    ]
    missing = [name for name in names if not re.search(r"\b" + re.escape(name) + r"\b", text)]
    assert missing == [], "Undocumented function names: " + ", ".join(missing)


def test_typed_adapter_reference_mentions_every_declared_function():
    java = shutil.which("java")
    if not java:
        pytest.skip("JDK required for compiler-tree function coverage")
    base = ROOT / "modules/aether-api/src/main/java/io/aetherdb/api/typed"
    files = [base / name for name in [
        "TypedAetherDatabase.java", "TypedAetherCollection.java", "TypedAetherSnapshot.java",
        "TypedWriteBatch.java", "ReadResult.java", "TypedWriteResult.java", "TypedKeyValue.java",
    ]]
    embedded = ROOT / "modules/aether-embedded-typed/src/main/java/io/aetherdb/embedded/typed"
    files += [embedded / "AetherEmbedded.java", embedded / "EmbeddedTypedDatabase.java"]
    result = subprocess.run([java, str(ROOT / "scripts/InventoryJavaFunctions.java"),
                             *map(str, files)], check=True, capture_output=True, text=True,
                            encoding="utf-8", timeout=60)
    methods = list(csv.reader(io.StringIO(result.stdout), delimiter="\t"))
    assert len(methods) >= 45
    text = (docs.SOURCE / "TYPED-ADAPTER-FUNCTIONS.md").read_text(encoding="utf-8")
    missing = [f"{owner}.{name}({parameters}) at {file}:{line}"
               for file, owner, name, line, parameters in methods
               if not re.search(r"\b" + re.escape(name) + r"\b", text)]
    assert missing == [], "Undocumented function names:\n" + "\n".join(missing)


def test_generated_container_reference_mentions_every_declared_function():
    java = shutil.which("java")
    if not java:
        pytest.skip("JDK required for compiler-tree function coverage")
    base = ROOT / "modules/aether-codec/src/main/java/io/aetherdb/codec/generated"
    files = [base / name for name in [
        "CanonicalContainerCodec.java", "CanonicalElementCodecs.java",
        "GeneratedCodecs.java", "GeneratedCodecProvider.java",
    ]]
    result = subprocess.run([java, str(ROOT / "scripts/InventoryJavaFunctions.java"),
                             *map(str, files)], check=True, capture_output=True, text=True,
                            encoding="utf-8", timeout=60)
    methods = list(csv.reader(io.StringIO(result.stdout), delimiter="\t"))
    assert len(methods) >= 45
    text = (docs.SOURCE / "GENERATED-CONTAINER-FUNCTIONS.md").read_text(encoding="utf-8")
    missing = [f"{owner}.{name}({parameters}) at {file}:{line}"
               for file, owner, name, line, parameters in methods
               if not re.search(r"\b" + re.escape(name) + r"\b", text)]
    assert missing == [], "Undocumented function names:\n" + "\n".join(missing)


def test_canonical_record_reference_mentions_every_declared_function():
    java = shutil.which("java")
    if not java:
        pytest.skip("JDK required for compiler-tree function coverage")
    base = ROOT / "modules/aether-codec/src/main/java/io/aetherdb/codec/generated"
    files = [base / name for name in [
        "CanonicalRecordReader.java", "CanonicalRecordWriter.java", "WireType.java",
    ]]
    result = subprocess.run([java, str(ROOT / "scripts/InventoryJavaFunctions.java"),
                             *map(str, files)], check=True, capture_output=True, text=True,
                            encoding="utf-8", timeout=60)
    methods = list(csv.reader(io.StringIO(result.stdout), delimiter="\t"))
    assert len(methods) >= 45
    text = (docs.SOURCE / "CANONICAL-RECORD-FUNCTIONS.md").read_text(encoding="utf-8")
    missing = [f"{owner}.{name}({parameters}) at {file}:{line}"
               for file, owner, name, line, parameters in methods
               if not re.search(r"\b" + re.escape(name) + r"\b", text)]
    assert missing == [], "Undocumented function names:\n" + "\n".join(missing)


def test_native_memtable_reference_mentions_every_declared_function():
    java = shutil.which("java")
    if not java:
        pytest.skip("JDK required for compiler-tree function coverage")
    base = ROOT / "modules/aether-memtable/src/main/java/io/aetherdb/memtable/skiplist"
    files = [base / name for name in [
        "NativeSkipListMemTable.java", "NativeSkipListNodeFormat.java",
        "SkipListHeightGenerator.java", "MemTableLookupResult.java",
    ]]
    result = subprocess.run([java, str(ROOT / "scripts/InventoryJavaFunctions.java"),
                             *map(str, files)], check=True, capture_output=True, text=True,
                            encoding="utf-8", timeout=60)
    methods = list(csv.reader(io.StringIO(result.stdout), delimiter="\t"))
    assert len(methods) >= 35
    text = (docs.SOURCE / "NATIVE-MEMTABLE-FUNCTIONS.md").read_text(encoding="utf-8")
    missing = [f"{owner}.{name}({parameters}) at {file}:{line}"
               for file, owner, name, line, parameters in methods
               if not re.search(r"\b" + re.escape(name) + r"\b", text)]
    assert missing == [], "Undocumented function names:\n" + "\n".join(missing)
