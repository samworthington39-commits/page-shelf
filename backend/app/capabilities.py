from typing import Final


FORMAT_CAPABILITIES: Final[dict[str, dict[str, bool]]] = {
    "pdf": {
        "chapters": False,
        "reflowable_text": False,
        "font_settings": False,
        "page_navigation": True,
        "zoom": True,
        "offline_download": True,
        "progress_sync": True,
    },
    "txt": {
        "chapters": True,
        "reflowable_text": True,
        "font_settings": True,
        "page_navigation": False,
        "zoom": False,
        "offline_download": True,
        "progress_sync": True,
    },
    "epub": {
        "chapters": True,
        "reflowable_text": True,
        "font_settings": True,
        "page_navigation": False,
        "zoom": False,
        "offline_download": True,
        "progress_sync": True,
    },
}


def capabilities_for(format_name: str) -> dict[str, bool]:
    return FORMAT_CAPABILITIES.get(format_name.lower(), {}).copy()

