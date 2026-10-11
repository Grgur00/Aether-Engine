"""Five H4 figures from archived paired-block analysis; no missing-value imputation."""
import argparse
import json
from pathlib import Path

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np

from .analyze_h4 import verify_inventory
from .common import digest, write_json
from .h4_runner import archive_checksums

COLORS = {"aether": "#187b64", "mmap": "#c85848"}
BACKENDS = ("aether", "mmap")
PATTERNS = ("sequential", "shuffled")


def cells(summary, pattern):
    return sorted([cell for cell in summary["conditions"] if cell["access_pattern"] == pattern],
                  key=lambda cell: cell["requested_ratio"])


def decorate(axis, selected, ylabel):
    axis.set_xticks(range(len(selected)), [f"{cell['requested_ratio']:g}x" for cell in selected])
    axis.set_xlabel("Logical Payload / Memory Limit")
    axis.set_ylabel(ylabel)
    axis.grid(axis="y", color="#e3e6e3", linewidth=.6)
    axis.set_axisbelow(True)
    axis.spines[["top", "right"]].set_visible(False)


def line(axis, values, *, label, color, style="-", counts=None):
    array = np.asarray([np.nan if value is None else value for value in values], dtype=float)
    axis.plot(range(len(values)), array, style, marker="o", markersize=4,
              color=color, linewidth=1.5, label=label)
    if not np.isfinite(array).any():
        return label + ": unavailable"
    if counts is not None and any(item.get("available_passes", item["available"]) < item.get("total_passes", item["total"]) for item in counts):
        return label + ": incomplete metric coverage"
    return None


def make_figures(summary, output):
    if summary.get("schema") != "aether-h4-analysis-v1" or not summary.get("conditions"):
        raise ValueError("nonempty H4 paired-block analysis required")
    output = Path(output)
    output.mkdir(parents=True, exist_ok=True)
    if any(output.iterdir()):
        raise ValueError("figure output must be empty")
    plt.rcParams.update({"font.family": "DejaVu Sans", "font.size": 10, "axes.titlesize": 12,
                         "axes.labelsize": 10, "legend.fontsize": 8, "figure.facecolor": "white"})
    files = []
    phase = summary["phase"].replace("-", " ").title()

    def save(figure, number, title, limitations=()):
        figure.suptitle(f"H4.{number}  {title}", fontsize=15, y=.98)
        footer = f"{phase} | Independent paired blocks, not individual passes"
        if limitations:
            footer += "\n" + "; ".join(dict.fromkeys(limitations))
        figure.text(.02, .015, footer, fontsize=8, color="#555c56", va="bottom")
        figure.tight_layout(rect=(0, .11 if limitations else .07, 1, .94))
        for extension in ("png", "pdf"):
            name = f"h4-{number}.{extension}"
            figure.savefig(output / name, dpi=180, metadata={"Title": title})
            files.append(name)
        plt.close(figure)

    figure, axes = plt.subplots(1, 2, figsize=(11, 4.8))
    for axis, pattern in zip(axes, PATTERNS):
        selected = cells(summary, pattern)
        values = [cell["paired_statistics"]["geometric_mean_ratio"] for cell in selected]
        axis.axhline(1, color="#657067", linestyle="--", linewidth=1)
        axis.plot(range(len(values)), values, "o-", color="#187b64", label="Aether / mmap")
        for index, cell in enumerate(selected):
            statistics = cell["paired_statistics"]
            if statistics["ci_lower"] is not None:
                axis.vlines(index, statistics["ci_lower"], statistics["ci_upper"], color="#187b64", linewidth=2)
            label_y = statistics["ci_upper"] if statistics["ci_upper"] is not None else values[index]
            axis.annotate(f"n={statistics['paired_blocks']}", (index, label_y), xytext=(0, 8),
                          textcoords="offset points", fontsize=8, ha="center")
        axis.set_title(pattern.title())
        decorate(axis, selected, "Relative Throughput")
    save(figure, 1, "Relative Throughput", ["Bars: descriptive 95% paired-log t intervals; n=1 has no interval"])

    figure, axes = plt.subplots(1, 2, figsize=(11, 4.8))
    for axis, pattern in zip(axes, PATTERNS):
        selected = cells(summary, pattern)
        for backend in BACKENDS:
            line(axis, [cell["backends"][backend]["throughput"]["mean"] for cell in selected],
                 label=backend.title(), color=COLORS[backend])
        axis.set_title(pattern.title())
        decorate(axis, selected, "Artifacts / Second")
        axis.legend()
    save(figure, 2, "Absolute Retrieval Throughput")

    figure, axes = plt.subplots(1, 2, figsize=(11, 4.8))
    for axis, pattern in zip(axes, PATTERNS):
        selected = cells(summary, pattern)
        for backend in BACKENDS:
            for quantile, style in (("p95", "-"), ("p99", "--")):
                values = [cell["backends"][backend]["latency"][quantile]["mean"] * 1000 for cell in selected]
                line(axis, values, label=backend.title() + " " + quantile, color=COLORS[backend], style=style)
        axis.set_title(pattern.title())
        decorate(axis, selected, "Sampled Lookup Latency (ms)")
        axis.legend(ncol=2)
    save(figure, 3, "Tail Lookup Latency", ["Separate single-artifact latency pass; means of block quantiles"])

    figure, axes = plt.subplots(1, 3, figsize=(13, 4.8))
    limitations = ["Cgroup-attributed mean per pass; unavailable measurements are not zero"]
    for axis, metric, label, divisor in zip(axes, ("physical_read_bytes", "file_refaults", "major_page_faults"),
                                          ("Physical Reads (MiB)", "File Cache Refaults", "Major Page Faults"),
                                          (1024 ** 2, 1, 1)):
        for pattern, style in (("sequential", "-"), ("shuffled", "--")):
            selected = cells(summary, pattern)
            for backend in BACKENDS:
                counts = [cell["backends"][backend]["resources"][metric] for cell in selected]
                values = [item["mean"] / divisor if item["mean"] is not None else None for item in counts]
                missing = line(axis, values, label=backend.title() + " " + pattern, color=COLORS[backend], style=style, counts=counts)
                if missing and "unavailable" in missing:
                    axis.text(.03, .95 - .06 * (len(axis.texts)), missing, transform=axis.transAxes,
                              fontsize=7, va="top", color=COLORS[backend])
                elif missing:
                    limitations.append("Some resource curves have incomplete metric coverage")
        decorate(axis, cells(summary, "shuffled"), label)
        axis.set_ylim(bottom=0)
        if not any(np.isfinite(item.get_ydata()).any() for item in axis.lines):
            axis.set_yticks([])
        axis.legend(fontsize=6)
    save(figure, 4, "Physical I/O And Memory Pressure", limitations)

    figure, axes = plt.subplots(1, 2, figsize=(11, 4.8))
    selected = cells(summary, "shuffled")
    # Use recorded logical payload, never derive it from rounded ratio labels.
    payload = [next(item["payload_bytes"] for item in summary["observations"] if item["condition"] == cell["condition"]) / 1024 ** 3 for cell in selected]
    limitations = ["Allocated bytes and file lengths are distinct; absent allocation data is not inferred"]
    for axis, metric, title in zip(axes, ("store_allocated_bytes", "store_file_bytes"),
                                   ("Allocated Storage", "Apparent File Size")):
        line(axis, payload, label="Logical Payload", color="#657067", style="--")
        for backend in BACKENDS:
            counts = [cell["backends"][backend][metric] for cell in selected]
            values = [item["mean"] / 1024 ** 3 if item["mean"] is not None else None for item in counts]
            missing = line(axis, values, label=backend.title(), color=COLORS[backend], counts=counts)
            if missing:
                axis.text(.03, .95 - .07 * len(axis.texts), missing, transform=axis.transAxes,
                          fontsize=8, va="top", color=COLORS[backend])
        decorate(axis, selected, "GiB")
        axis.set_title(title)
        axis.legend()
    save(figure, 5, "Storage Footprint", limitations)
    return files


def run(analysis, output):
    analysis = Path(analysis).resolve()
    output = Path(output).resolve()
    if output == analysis.parent or output.is_relative_to(analysis.parent):
        raise ValueError("figures must not modify archived analysis inputs")
    verify_inventory(analysis.parent)
    summary = json.loads(analysis.read_text())
    files = make_figures(summary, output)
    write_json(output / "figure-provenance.json", dict(analysis_sha256=digest(analysis),
               phase=summary["phase"], figures=files, source_archives=summary["input_archives"],
               primary_h4_eligible=summary["primary_h4_eligible"]))
    archive_checksums(output)
    return files


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--analysis", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    run(args.analysis, args.output)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
