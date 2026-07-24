from fastapi import APIRouter, Depends
from sqlalchemy.orm import Session

from ..config import Settings, get_settings
from ..db import get_db
from ..schemas import ScanResponse
from ..services.admin_auth import require_mobile_session
from ..services.shelf_scanner import scan_all_shelves


router = APIRouter(prefix="/library", tags=["library"])


@router.post("/scan", response_model=ScanResponse, dependencies=[Depends(require_mobile_session)])
def scan(
    session: Session = Depends(get_db),
    settings: Settings = Depends(get_settings),
) -> ScanResponse:
    result = scan_all_shelves(session, settings)
    return ScanResponse(
        discovered=result.discovered,
        imported=result.imported,
        updated=result.updated,
        unchanged=result.unchanged,
        removed=result.removed,
        failed=len(result.failures),
        failures=result.failures,
    )
