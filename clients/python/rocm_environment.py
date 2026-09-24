import platform
import socket
import sys


def inspect(device="cuda", require_rocm=False):
    import torch
    if not torch.cuda.is_available():
        raise RuntimeError("PyTorch cannot access a GPU; install a CUDA or ROCm build")
    properties = torch.cuda.get_device_properties(device)
    hip = getattr(torch.version, "hip", None)
    if require_rocm and not hip:
        raise RuntimeError("PyTorch is not a ROCm build; install the ROCm PyTorch wheel")
    return {
        "python": sys.version,
        "platform": platform.platform(),
        "hostname": socket.gethostname(),
        "pytorch": torch.__version__,
        "cudaVersion": torch.version.cuda,
        "rocmVersion": hip,
        "backend": "ROCm" if hip else "CUDA",
        "device": str(device),
        "gpu": properties.name,
        "vramBytes": properties.total_memory,
        "deviceCapability": getattr(properties, "major", None),
        "deviceIndex": torch.cuda.current_device(),
    }


def smoke_test(device="cuda"):
    import torch
    left = torch.ones((256, 256), device=device)
    right = torch.ones((256, 256), device=device)
    result = left @ right
    torch.cuda.synchronize(device)
    if result[0, 0].item() != 256:
        raise RuntimeError("GPU matmul smoke test returned an invalid result")