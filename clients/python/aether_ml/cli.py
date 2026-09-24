import argparse
import importlib.util
import json
from pathlib import Path


def load_dataset(path: str):
    config = Path(path).resolve()
    spec = importlib.util.spec_from_file_location("aether_ml_user_config", config)
    if spec is None or spec.loader is None:
        raise ValueError(f"cannot import {config}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    dataset = getattr(module, "dataset", None)
    if dataset is None or not all(hasattr(dataset, name) for name in ("plan", "populate", "stats")):
        raise ValueError("configuration must expose an AetherDataset named 'dataset'")
    return dataset


def main(argv=None) -> None:
    parser = argparse.ArgumentParser(description="Operate an Aether ML dataset configured by a Python file.")
    parser.add_argument("command", choices=("plan", "populate", "stats"))
    parser.add_argument("config", help="Python configuration exposing `dataset`")
    parser.add_argument("--workers", type=int, default=1)
    args = parser.parse_args(argv)
    dataset = load_dataset(args.config)
    try:
        if args.command == "plan":
            result = dataset.plan()
        elif args.command == "populate":
            result = dataset.populate(workers=args.workers)
        else:
            result = dataset.stats()
        print(json.dumps(result, sort_keys=True, indent=2))
    finally:
        dataset.close()


if __name__ == "__main__":
    main()