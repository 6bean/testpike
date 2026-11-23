"""Machine-unique container-name prefix resolution for one installed instance.

Docker container names are global on a host: with a fixed name, a second
install (clean-machine matrix cell, parallel slot stack) collides with the
first stack the moment it reaches ``compose up``. Every reference that needs
to address a container by name — compose interpolation is fed the same env
key — derives its name here from one source, so an instance stays addressable
without hardcoding the shipped default anywhere.

Resolution order: environment key ``TP_CONTAINER_PREFIX`` when present and
non-blank, otherwise the shipped default ``testpike`` (zero drift for an
ordinary single-instance install).
"""
import os

PREFIX_ENV = "TP_CONTAINER_PREFIX"
DEFAULT_PREFIX = "testpike"


def prefix() -> str:
    raw = os.environ.get(PREFIX_ENV, "")
    return raw.strip() or DEFAULT_PREFIX


def name(service: str) -> str:
    """Container name of one service under the resolved instance prefix."""
    return "%s-%s" % (prefix(), service)
