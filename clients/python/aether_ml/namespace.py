import re

from .exceptions import IdentityError


def normalize_namespace(namespace: str) -> str:
    normalized = "/".join(part for part in namespace.strip().split("/") if part)
    if not normalized or not re.fullmatch(r"[A-Za-z0-9._-]+(?:/[A-Za-z0-9._-]+)*", normalized):
        raise IdentityError("namespace must contain slash-separated letters, digits, dot, underscore, or hyphen")
    return normalized