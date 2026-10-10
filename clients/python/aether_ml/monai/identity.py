from pathlib import Path

from ..identity import hashed_identity


def monai_dict_identity(item: dict, index: int = 0, *, key: str = "id") -> str:
    if key not in item:
        raise KeyError(f"MONAI item has no {key!r} identity field")
    return hashed_identity(str(item[key]))


def dicom_study_identity(item: dict, index: int = 0) -> str:
    fields = ("StudyInstanceUID", "SeriesInstanceUID", "SOPInstanceUID")
    values = [str(item[field]) for field in fields if item.get(field)]
    if not values:
        raise KeyError("DICOM item requires a StudyInstanceUID, SeriesInstanceUID, or SOPInstanceUID")
    return hashed_identity(":".join(values))


def nifti_file_identity(path: str | Path, index: int = 0) -> str:
    path = Path(path)
    return hashed_identity(f"{path.resolve()}:{path.stat().st_size}:{path.stat().st_mtime_ns}")