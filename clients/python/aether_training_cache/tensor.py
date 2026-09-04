class AetherArray:
    def __init__(self, array, lease):
        self.array = array
        self.lease = lease

    def close(self):
        self.lease.close()

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc_value, traceback):
        self.close()


class AetherTensor:
    def __init__(self, tensor, lease):
        self.tensor = tensor
        self.lease = lease

    def close(self):
        self.lease.close()

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc_value, traceback):
        self.close()

    def to(self, device="cuda", non_blocking=False):
        return self.tensor.to(device=device, non_blocking=non_blocking)

    def checksum(self):
        import torch
        return int(self.tensor.view(torch.uint8).to(torch.int64).sum()) & 0xFFFFFFFF


def numpy_array(view, dtype="int32", shape=None):
    import numpy as np
    array = np.frombuffer(view.buffer if hasattr(view, "buffer") else view, dtype=dtype)
    return array.reshape(shape) if shape is not None else array


def torch_tensor(view, dtype="int32", shape=None):
    import torch
    return torch.from_numpy(numpy_array(view, dtype=dtype, shape=shape))


def torch_tensor_from_view(view, dtype="int32", shape=None):
    """Build a CPU tensor while retaining the mapped view lease."""
    return AetherTensor(torch_tensor(view, dtype=dtype, shape=shape), view)


def pinned_tensor(tensor):
    """Copy a CPU tensor into pinned host memory for asynchronous H2D transfer."""
    import torch
    if not tensor.is_cpu:
        raise ValueError("pinned staging requires a CPU tensor")
    pinned = torch.empty_like(tensor, device="cpu", pin_memory=True)
    pinned.copy_(tensor)
    return pinned


def gpu_transfer(tensor, device="cuda", non_blocking=False):
    import torch
    if not torch.cuda.is_available():
        return None
    import time
    started = time.perf_counter_ns()
    result = tensor.to(device=device, non_blocking=non_blocking)
    torch.cuda.synchronize()
    return result, time.perf_counter_ns() - started
