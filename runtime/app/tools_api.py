from typing import Annotated

from fastapi import APIRouter, Depends, HTTPException, Request, status

from app.tools.catalog import ServerTools, ToolCatalog

router = APIRouter(prefix="/runtime/tools", tags=["tools"])


def get_catalog(request: Request) -> ToolCatalog:
    return request.app.state.catalog


Catalog = Annotated[ToolCatalog, Depends(get_catalog)]


@router.get("")
def list_servers(catalog: Catalog, refresh: bool = False) -> list[ServerTools]:
    """Every configured MCP server with its tools (or the error listing them)."""
    return catalog.all(refresh)


@router.get("/{server}")
def get_server(server: str, catalog: Catalog, refresh: bool = False) -> ServerTools:
    try:
        return catalog.server(server, refresh)
    except KeyError:
        raise HTTPException(status.HTTP_404_NOT_FOUND, f"Unknown MCP server '{server}'") from None
