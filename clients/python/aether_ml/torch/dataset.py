from ..dataset import AetherDataset


class AetherTorchDataset(AetherDataset):
    """AetherDataset alias suitable for torch.utils.data.DataLoader."""