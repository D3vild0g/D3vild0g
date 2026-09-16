"""
Pluggable web-search providers for the NHL betting-sentiment endpoint.

Only real, documented search APIs are used here (never HTML scraping of
Google/Reddit/sportsbooks/etc). Currently implemented:

- SerpApiProvider: wraps SerpApi's Google Search API (https://serpapi.com/).
  Chosen over the Bing Web Search API because Microsoft has been retiring
  Bing Search API resources on Azure (new resource creation was cut off in
  2025), which makes it a poor choice for a project meant to keep working.
  SerpApi has a stable free tier (100 searches/month at the time of
  writing), a simple single-API-key setup, and a plain REST/JSON response
  that's easy to depend on without an extra SDK.

To add a different provider (e.g. swap in Bing, or a different aggregator),
implement the `SearchProvider` interface below and register it in
`get_provider()`.
"""

from __future__ import annotations

import abc
import os
from dataclasses import dataclass

import httpx


@dataclass(frozen=True)
class SearchResult:
    title: str
    url: str
    snippet: str


class SearchProvider(abc.ABC):
    """Interface every search backend must implement."""

    name: str = "base"

    @abc.abstractmethod
    async def search(self, query: str, num_results: int = 5) -> list[SearchResult]:
        """Run a web search and return up to `num_results` results.

        Implementations should raise `SearchProviderError` on failure
        (bad key, network error, non-2xx response) rather than letting
        arbitrary exceptions escape, so callers can turn that into a
        clean "not available" response instead of a 500.
        """
        raise NotImplementedError


class SearchProviderError(RuntimeError):
    """Raised when a configured search provider fails to return results."""


class SerpApiProvider(SearchProvider):
    """Search provider backed by SerpApi's Google Search API.

    Docs: https://serpapi.com/search-api
    Auth: single `api_key` query param.
    """

    name = "serpapi"
    BASE_URL = "https://serpapi.com/search"

    def __init__(self, api_key: str):
        if not api_key:
            raise ValueError("SerpApiProvider requires a non-empty api_key")
        self.api_key = api_key

    async def search(self, query: str, num_results: int = 5) -> list[SearchResult]:
        params = {
            "engine": "google",
            "q": query,
            "api_key": self.api_key,
            "num": num_results,
        }
        try:
            async with httpx.AsyncClient(timeout=10.0) as client:
                resp = await client.get(self.BASE_URL, params=params)
        except httpx.HTTPError as exc:
            raise SearchProviderError(f"SerpApi request failed: {exc}") from exc

        if resp.status_code != 200:
            raise SearchProviderError(
                f"SerpApi returned HTTP {resp.status_code}: {resp.text[:200]}"
            )

        try:
            data = resp.json()
        except ValueError as exc:
            raise SearchProviderError("SerpApi returned non-JSON response") from exc

        if "error" in data:
            raise SearchProviderError(f"SerpApi error: {data['error']}")

        results: list[SearchResult] = []
        for item in (data.get("organic_results") or [])[:num_results]:
            title = item.get("title") or ""
            url = item.get("link") or ""
            snippet = item.get("snippet") or ""
            if title and url:
                results.append(SearchResult(title=title, url=url, snippet=snippet))
        return results


_PROVIDERS = {
    "serpapi": SerpApiProvider,
}


def get_provider(api_key: str, provider_name: str | None = None) -> SearchProvider:
    """Instantiate the configured search provider.

    `provider_name` comes from the `SEARCH_PROVIDER` env var; defaults to
    "serpapi" (the only implementation shipped here) when unset.
    """
    name = (provider_name or "serpapi").strip().lower()
    provider_cls = _PROVIDERS.get(name)
    if provider_cls is None:
        raise ValueError(
            f"Unknown SEARCH_PROVIDER '{name}'. Supported: {', '.join(_PROVIDERS)}"
        )
    return provider_cls(api_key)
