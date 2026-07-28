from pathlib import Path

from fastapi.responses import FileResponse


reader_directory = Path(__file__).parents[1] / "reader"


def reader_page() -> FileResponse:
    response = FileResponse(reader_directory / "index.html", media_type="text/html")
    response.headers["Cache-Control"] = "no-store"
    response.headers["Content-Security-Policy"] = (
        "default-src 'self'; script-src 'self'; style-src 'self'; "
        "img-src 'self' data: blob:; connect-src 'self'; frame-src blob:; "
        "object-src blob:; frame-ancestors 'none'; base-uri 'none'; form-action 'self'"
    )
    response.headers["X-Content-Type-Options"] = "nosniff"
    response.headers["X-Frame-Options"] = "DENY"
    return response
