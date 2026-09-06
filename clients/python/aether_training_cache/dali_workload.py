"""Canonical DALI RGB workload shared by direct execution and persistent caches.

This is a separate workload from the Pillow pipeline. It requires CUDA and DALI;
there is no CPU fallback or assumption that different decoders are bit-identical.
"""
import hashlib
import json


def descriptor(args, dali_version):
    return {"implementation": "dali-rgb-v1", "daliVersion": dali_version,
            "dataset": args.dataset_kind, "decode": "DALI mixed RGB",
            "resize": args.resize, "interpolation": "linear", "antialias": True,
            "normalization": "mean=0,std=255", "layout": "CHW", "output": "float16",
            "trainingDtype": "float32", "numClasses": args.num_classes,
            "artifactEncoding": "nchw-float16-uint8-v1", "artifactCodec": "none"}


def artifact_key(source, parameters):
    encoded = json.dumps({"sampleId": source["sample_id"], "sourceHash": source["source_hash"],
                          "parameters": parameters}, sort_keys=True, separators=(",", ":")).encode()
    return hashlib.sha256(encoded).hexdigest()


class EncodedSource:
    """Stateless callback; speculative requests beyond the schedule always stop."""
    def __init__(self, sources, schedule):
        self.sources, self.schedule = sources, schedule

    def __call__(self, iteration):
        import numpy as np
        if iteration >= len(self.schedule):
            raise StopIteration
        return [np.fromfile(self.sources[index]["image_path"], dtype=np.uint8) for index in self.schedule[iteration]]


class DaliBatches:
    def __init__(self, args, sources, schedule=None):
        import torch
        from nvidia.dali import fn, types, pipeline_def
        if not torch.cuda.is_available() or not torch.version.cuda:
            raise RuntimeError("DALI comparison requires CUDA")
        self.args, self.sources, self.schedule = args, sources, schedule
        source = EncodedSource(sources, schedule) if schedule is not None else None
        @pipeline_def
        def graph():
            encoded = fn.external_source(source=source, name="encoded", batch=True, dtype=types.UINT8, ndim=1)
            decoded = fn.decoders.image(encoded, device="mixed", output_type=types.RGB)
            resized = fn.resize(decoded, device="gpu", resize_x=args.resize, resize_y=args.resize,
                                interp_type=types.INTERP_LINEAR, antialias=True)
            return fn.crop_mirror_normalize(resized, device="gpu", dtype=types.FLOAT16,
                                           output_layout="CHW", mean=[0.0], std=[255.0])
        automatic = schedule is not None
        self.pipe = graph(batch_size=args.batch_size, num_threads=args.dali_threads, device_id=0,
            seed=args.seed, exec_async=automatic, exec_pipelined=automatic,
            exec_dynamic=automatic, prefetch_queue_depth=args.dali_prefetch if automatic else 1)
        self.pipe.build()

    def batch(self, indices=None):
        import numpy as np
        import torch
        if self.schedule is None:
            if not indices or len(indices) > self.args.batch_size:
                raise ValueError("manual DALI batch must fit the declared batch size")
            self.pipe.feed_input("encoded", [np.fromfile(self.sources[i]["image_path"], dtype=np.uint8) for i in indices])
        elif indices is not None:
            raise ValueError("scheduled DALI pipeline owns its source order")
        output = self.pipe.run()[0].as_tensor()
        # FP16 -> FP32 allocates the model input on-device. No host round trip on
        # the direct DALI path. DLPack synchronizes the producer/consumer streams.
        return torch.from_dlpack(output).to(dtype=torch.float32)

    def close(self):
        import torch
        torch.cuda.synchronize()
        self.pipe = None


def targets(sources, indices, classes, np):
    result = np.zeros((len(indices), classes), dtype=np.float32)
    for row, index in enumerate(indices):
        result[row, sources[index]["labels"]] = 1
    return result


def payloads(images, sources, indices, classes, np):
    from benchmark_gpu_segmentation import pack_payload
    labels = targets(sources, indices, classes, np)
    return {index: pack_payload({"sample_id": sources[index]["sample_id"], "image": images[row], "mask": labels[row]})
            for row, index in enumerate(indices)}
