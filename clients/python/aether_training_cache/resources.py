"""Linux process counters; unsupported platforms report missing values explicitly."""
import os
from pathlib import Path


COUNTERS = ("cpuSeconds", "minorFaults", "majorFaults", "diskReadBytes", "diskWriteBytes")


def snapshot(pid=None):
    pid = os.getpid() if pid is None else int(pid)
    result = {"pid": pid, "available": False}
    try:
        root = Path("/proc") / str(pid)
        # comm can contain whitespace and parentheses; fields start after its final ')'.
        fields = (root / "stat").read_text().rsplit(")", 1)[1].split()
        result.update(cpuSeconds=(int(fields[11]) + int(fields[12])) / os.sysconf("SC_CLK_TCK"),
                      minorFaults=int(fields[7]), majorFaults=int(fields[9]), available=True)
        status = dict(line.split(":", 1) for line in (root / "status").read_text().splitlines())
        result["rssBytes"] = int(status["VmRSS"].split()[0]) * 1024
        result["lifetimePeakRssBytes"] = int(status["VmHWM"].split()[0]) * 1024
        try:
            io = dict(line.split(":", 1) for line in (root / "io").read_text().splitlines())
            result.update(diskReadBytes=int(io["read_bytes"]), diskWriteBytes=int(io["write_bytes"]))
        except OSError:
            pass
    except (OSError, ValueError, KeyError, IndexError, AttributeError) as error:
        result["unavailableReason"] = str(error)
    return result


def delta(before, after):
    same = before["pid"] == after["pid"] and before["available"] and after["available"]
    return {"pid": after["pid"], "available": bool(same),
        **{key: max(0, after[key] - before[key]) if same and key in before and key in after else None for key in COUNTERS},
        "rssBytesAtEnd": after.get("rssBytes"), "lifetimePeakRssBytes": after.get("lifetimePeakRssBytes"),
        "scope": "one Linux process; disk bytes exclude page-cache hits; RSS high-water mark is process-lifetime"}
