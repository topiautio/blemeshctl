"""Control legacy Telink BleMesh lights from a command line."""

from .protocol import (
    DEFAULT_MESH_NAME,
    DEFAULT_PASSWORD,
    TELINK_COMPANY_ID,
    AdvertisementInfo,
    TelinkProtocolError,
)

__all__ = [
    "AdvertisementInfo",
    "DEFAULT_MESH_NAME",
    "DEFAULT_PASSWORD",
    "TELINK_COMPANY_ID",
    "TelinkProtocolError",
]
