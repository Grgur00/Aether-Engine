from pathlib import Path

from build_oct5k_manifest import is_boundary_table_path, is_rgb_visualization, pair_key


def test_pair_key_uses_oct5k_grading_relative_path():
    boundary = Path("OCT5k/Boundaries/Boundaries_Automatic/Grading/AMD Part1/AMD (19).E2E/date/Image 13.PNG")
    matching_mask = Path("OCT5k/Masks/Masks_Automatic/Grading/AMD Part1/AMD (19).E2E/date/Image 13.PNG")
    other_scan = Path("OCT5k/Boundaries/Boundaries_Automatic/Grading/AMD Part1/AMD (20).E2E/date/Image 13.PNG")

    assert pair_key(boundary) == pair_key(matching_mask)
    assert pair_key(boundary) != pair_key(other_scan)


def test_rgb_mask_visualizations_are_not_semantic_masks():
    semantic = Path("OCT5k/Masks/Masks_Automatic/Grading/AMD Part1/scan/Image 1.PNG")
    visualization = Path("OCT5k/Masks/Masks_Automatic_RGB/Grading/AMD Part1/scan/Image 1.PNG")

    assert not is_rgb_visualization(semantic)
    assert is_rgb_visualization(visualization)


def test_boundary_coordinate_tables_are_not_training_images():
    boundary = Path("OCT5k/Boundaries/Boundaries_Automatic/Grading/AMD Part1/scan/Image 1.PNG")
    mask = Path("OCT5k/Masks/Masks_Automatic/Grading/AMD Part1/scan/Image 1.PNG")

    assert is_boundary_table_path(boundary)
    assert not is_boundary_table_path(mask)