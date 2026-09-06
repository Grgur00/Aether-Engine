from pathlib import Path

from build_oct5k_manifest import pair_key


def test_pair_key_uses_oct5k_grading_relative_path():
    boundary = Path("OCT5k/Boundaries/Boundaries_Automatic/Grading/AMD Part1/AMD (19).E2E/date/Image 13.PNG")
    matching_mask = Path("OCT5k/Masks/Masks_Automatic/Grading/AMD Part1/AMD (19).E2E/date/Image 13.PNG")
    other_scan = Path("OCT5k/Boundaries/Boundaries_Automatic/Grading/AMD Part1/AMD (20).E2E/date/Image 13.PNG")

    assert pair_key(boundary) == pair_key(matching_mask)
    assert pair_key(boundary) != pair_key(other_scan)