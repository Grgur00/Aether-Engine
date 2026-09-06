"""Fail fast when a CUDA host cannot execute Aether GPU workloads."""
import argparse
import json
import subprocess


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--require-dali", action="store_true")
    args = parser.parse_args(argv)

    try:
        import torch
    except ImportError as error:
        raise SystemExit("PyTorch is required; install the target CUDA PyTorch wheel first") from error
    if not torch.cuda.is_available() or not torch.version.cuda:
        raise SystemExit("CUDA PyTorch cannot access an NVIDIA GPU")
    device = torch.cuda.current_device()
    properties = torch.cuda.get_device_properties(device)
    if properties.major < 7:
        raise SystemExit("Aether GPU workloads require CUDA compute capability 7.0 or newer")
    try:
        result = torch.ones((256, 256), device="cuda") @ torch.ones((256, 256), device="cuda")
        torch.cuda.synchronize()
    except RuntimeError as error:
        raise SystemExit(f"CUDA smoke kernel failed: {error}") from error
    report = {"torch": torch.__version__, "cuda": torch.version.cuda, "device": device,
              "name": torch.cuda.get_device_name(device), "vramBytes": properties.total_memory,
              "computeCapability": f"{properties.major}.{properties.minor}", "matmulSum": float(result.sum())}
    if args.require_dali:
        try:
            import nvidia.dali
        except ImportError as error:
            raise SystemExit("DALI is required; install with: python -m pip install -r env/requirements-dali.lock") from error
        report["dali"] = nvidia.dali.__version__
    nvidia_smi = subprocess.run(["nvidia-smi", "--query-gpu=name,driver_version,memory.total", "--format=csv,noheader"],
                                capture_output=True, text=True)
    report["nvidiaSmi"] = nvidia_smi.stdout.strip() if nvidia_smi.returncode == 0 else "unavailable"
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
