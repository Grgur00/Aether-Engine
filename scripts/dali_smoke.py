"""CUDA correctness smoke with generated RGB images; never dataset performance evidence."""
import argparse
from pathlib import Path

from paper_common import write_json
from prepare_vision import prepare
from dali_comparison import main as compare


def run(output):
    import numpy as np
    from PIL import Image
    output = Path(output).resolve()
    output.mkdir(parents=True, exist_ok=False)
    images = output / "fixtures"
    rng = np.random.default_rng(74)
    for i in range(5):
        folder = images / ("class-a" if i < 3 else "class-b")
        folder.mkdir(parents=True, exist_ok=True)
        data = rng.integers(0, 256, size=(11 + i, 13 + i, 3), dtype=np.uint8)
        Image.fromarray(data).save(folder / f"sample-{i}.{'png' if i % 2 else 'jpg'}")
    manifest = output / "fixture-manifest.csv"
    prepare("imagenet", images, manifest)
    config = output / "fixture-config.json"
    write_json(config, {"imagenet": {"manifestV2": str(manifest), "samplesV2": 5,
                                    "numClasses": 2, "imageSize": 8, "split": "train"}})
    compare(["--config", str(config), "--datasets", "imagenet", "--repeats", "1", "--epochs", "2",
             "--batch-size", "2", "--dali-threads", "2", "--reuse-percent", "40", "--fixture-smoke",
             "--output", str(output / "measurements")])
    print("Generated-fixture DALI/Java/mmap CUDA correctness passed; not a paper result")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=Path("build/dali-smoke"))
    run(parser.parse_args().output)
