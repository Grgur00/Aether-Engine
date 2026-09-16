import argparse
import html
import json
from pathlib import Path


PAGE = """<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Aether Cache Benchmark Report</title>
<style>
:root { --ink:#17201a; --muted:#647067; --paper:#f4f5ef; --panel:#fff; --line:#d7ddd5; --orange:#df4b25; --green:#147d57; --blue:#3778aa; }
* { box-sizing:border-box; } body { margin:0; color:var(--ink); background:var(--paper); font:15px/1.5 "Segoe UI", sans-serif; }
main { width:min(1400px,100%); margin:auto; padding:36px clamp(18px,4vw,64px) 80px; }
h1 { margin:0; font-size:clamp(32px,5vw,64px); line-height:1; letter-spacing:-.04em; } h2 { margin:0 0 18px; font-size:22px; }
.lede { max-width:780px; color:var(--muted); font-size:17px; } .meta { color:var(--muted); font-family:monospace; font-size:12px; }
.grid { display:grid; grid-template-columns:repeat(3,1fr); gap:16px; margin-top:30px; } .panel { padding:22px; border:1px solid var(--line); background:var(--panel); }
.metric strong { display:block; margin:10px 0 2px; font-size:36px; line-height:1; } .metric span { color:var(--muted); }
section { margin-top:48px; } .chart { display:flex; min-height:270px; padding:18px 10px 8px; border:1px solid var(--line); background:var(--panel); align-items:end; gap:8px; overflow-x:auto; }
.bar-group { display:flex; min-width:68px; height:235px; align-items:end; justify-content:center; gap:4px; position:relative; } .bar { width:25px; min-height:2px; border-radius:3px 3px 0 0; position:relative; } .bar:hover { opacity:.72; } .bar::after { content:attr(data-value); position:absolute; bottom:calc(100% + 4px); left:50%; color:var(--ink); font:10px monospace; transform:translateX(-50%); white-space:nowrap; }
.cold { background:var(--orange); } .warm { background:var(--green); } .mapped { background:var(--blue); } .label { position:absolute; bottom:-25px; color:var(--muted); font:10px monospace; white-space:nowrap; }
.legend { display:flex; gap:18px; margin:12px 0; color:var(--muted); font-size:12px; } .legend i { display:inline-block; width:10px; height:10px; margin-right:5px; border-radius:2px; }
table { width:100%; border-collapse:collapse; background:var(--panel); font-family:monospace; font-size:12px; } th,td { padding:10px 12px; border:1px solid var(--line); text-align:right; } th:first-child,td:first-child { text-align:left; } th { color:var(--muted); background:#edf0e9; }
.note { padding:14px 18px; border-left:4px solid var(--orange); color:var(--muted); background:#fff8f2; } .ok { color:var(--green); font-weight:700; } .bad { color:var(--orange); font-weight:700; }
@media (max-width:800px) { .grid { grid-template-columns:1fr; } .panel { overflow:auto; } }
</style>
</head><body><main>
<h1>Aether Cache<br>benchmark report</h1>
<p class="lede">Interactive view of payload throughput, full-consumption paths, latency, storage, checksums, and preprocessing reuse.</p>
<div class="meta">Generated from machine-readable benchmark artifacts. Values are measurements, not service-level objectives.</div>
<div id="app"></div>
<script>
const report = Array.isArray(__REPORT__) ? __REPORT__ : [__REPORT__];
const prep = __PREP__;
const lifecycle = __LIFECYCLE__;
const app = document.querySelector('#app');
const fmt = (n) => n == null ? 'n/a' : Number(n).toLocaleString(undefined,{maximumFractionDigits:3});
const mib = (n) => n == null ? 'n/a' : `${fmt(n / 1048576)} MiB/s`;
const card = (label, value, detail) => `<div class="panel metric"><span>${label}</span><strong>${value}</strong><span>${detail}</span></div>`;
const maxWarm = Math.max(...report.map(x => x.warmSamplesPerSecond));
const bars = report.map(x => `<div class="bar-group"><div class="bar cold" style="height:${Math.max(2,x.coldSamplesPerSecond/maxWarm*220)}px" data-value="${fmt(x.coldSamplesPerSecond)}"></div><div class="bar warm" style="height:${Math.max(2,x.warmSamplesPerSecond/maxWarm*220)}px" data-value="${fmt(x.warmSamplesPerSecond)}"></div><div class="bar mapped" style="height:${Math.max(2,x.mappedSamplesPerSecond/maxWarm*220)}px" data-value="${fmt(x.mappedSamplesPerSecond)}"></div><div class="label">${fmtBytes(x.payloadBytes)}</div></div>`).join('');
const rows = report.map(x => `<tr><td>${fmtBytes(x.payloadBytes)}</td><td>${fmt(x.coldSamplesPerSecond)}</td><td>${fmt(x.warmSamplesPerSecond)}</td><td>${fmt(x.mappedSamplesPerSecond)}</td><td>${fmt(x.warmSamplesPerSecond/x.coldSamplesPerSecond)}x</td><td>${fmt(x.warm.getP99Nanos/1000)} us</td></tr>`).join('');
function fmtBytes(n) { return n >= 1048576 ? `${n/1048576} MB` : n >= 1024 ? `${n/1024} KB` : `${n} B`; }
let html = `<div class="grid">${card('Payload sizes', report.length, 'matrix points')}${card('Best warm throughput', fmt(maxWarm), 'samples/sec')}${card('Largest warm payload', fmtBytes(Math.max(...report.map(x=>x.payloadBytes))), 'included in matrix')}</div>`;
html += `<section><h2>Throughput by payload size</h2><div class="legend"><span><i class="cold"></i>cold population</span><span><i class="warm"></i>warm materialized</span><span><i class="mapped"></i>warm mapped view</span></div><div class="chart">${bars}</div></section>`;
html += `<section><h2>Payload matrix</h2><table><thead><tr><th>Payload</th><th>Cold/s</th><th>Warm/s</th><th>Mapped/s</th><th>Warm speedup</th><th>Warm get p99</th></tr></thead><tbody>${rows}</tbody></table></section>`;
if (prep) {
 const prepRows = prep.results.map(x => `<tr><td>${x.preprocessCostTargetMs} ms</td><td>${fmt(x.recomputeEpochNanos/1e6)} ms</td><td>${fmt(x.reuseEpochMedianNanos/1e6)} ms</td><td>${x.breakEvenEpoch ?? 'n/a'}</td><td class="${x.checksumMatches?'ok':'bad'}">${x.checksumMatches?'match':'FAIL'}</td></tr>`).join('');
 html += `<section><h2>Preprocessing break-even</h2><div class="note">A cached result is counted as a practical win only when cumulative cached time is at least 1% lower than recomputation. Current target range: ${prep.targetsMs.join(', ')} ms/sample.</div><table><thead><tr><th>Preprocess target</th><th>Recompute/epoch</th><th>Reuse/epoch</th><th>Break-even epoch</th><th>Checksum</th></tr></thead><tbody>${prepRows}</tbody></table></section>`;
}
if (lifecycle) {
 const lifecycleRows = lifecycle.results.map(x => `<tr><td>${x.targetTokens}</td><td>${x.actualTokenP50}</td><td>${x.breakEvenEpoch ?? 'n/a'}</td><td>${x.results.AETHER_POPULATE_REUSE ? fmt(x.results.AETHER_POPULATE_REUSE.populateNanos / 1e6) + ' ms' : 'n/a'}</td></tr>`).join('');
 html += `<section><h2>Lifecycle break-even by token length</h2><div class="note">The cache population pass is included. Break-even uses cumulative Aether population plus reuse time against repeated tokenization.</div><table><thead><tr><th>Target tokens</th><th>Actual p50</th><th>Break-even epoch</th><th>Population</th></tr></thead><tbody>${lifecycleRows}</tbody></table></section>`;
}
app.innerHTML = html;
</script></main></body></html>"""


def main():
    parser = argparse.ArgumentParser(description="Create a self-contained HTML view of Aether benchmark JSON.")
    parser.add_argument("--matrix", required=True, help="Payload matrix JSON file")
    parser.add_argument("--preprocessing", help="Preprocessing break-even JSON file")
    parser.add_argument("--lifecycle", help="Tokenizer lifecycle bucket JSON file")
    parser.add_argument("--output", default="build/aether-benchmark-report.html")
    args = parser.parse_args()
    matrix = json.loads(Path(args.matrix).read_text(encoding="utf-8"))
    preprocessing = json.loads(Path(args.preprocessing).read_text(encoding="utf-8")) if args.preprocessing else None
    lifecycle = json.loads(Path(args.lifecycle).read_text(encoding="utf-8")) if args.lifecycle else None
    page = PAGE.replace("__REPORT__", json.dumps(matrix)).replace("__PREP__", json.dumps(preprocessing)).replace("__LIFECYCLE__", json.dumps(lifecycle))
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(page, encoding="utf-8")
    print(f"Report: {output.resolve()}")


if __name__ == "__main__":
    main()
