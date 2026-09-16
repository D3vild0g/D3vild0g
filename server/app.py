"""
NHL betting-sentiment backend.

Small FastAPI service that fills a gap in the sports-predictor project:
for NFL, real sportsbook lines are already free and structured
(http://www.habitatring.com/games.csv), so the Android app doesn't need a
backend for that sport at all. NHL has no equivalent free, structured
odds/injury-report feed, so this service acts as a fallback: it searches
the web (via a real search API, never HTML scraping) for public betting
sentiment / line-movement chatter and injury or starting-goalie buzz for a
given NHL matchup, and hands back a short summary plus source links.

Run locally:
    SEARCH_API_KEY=... uvicorn app:app --reload

See README.md for setup, deployment, and the exact response shapes.
"""

from __future__ import annotations

import os
from datetime import datetime, timezone

from fastapi import FastAPI, Query
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel

from cache import TTLCache
from search import SearchProviderError, SearchResult, get_provider

app = FastAPI(title="NHL Betting Sentiment Service")

# Permissive CORS. The Android app calls this directly (not from a
# browser), but this also makes it easy to test from a browser or curl.
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)

CACHE_TTL_SECONDS = 30 * 60  # 30 minutes
_cache = TTLCache(ttl_seconds=CACHE_TTL_SECONDS)


class SourceOut(BaseModel):
    title: str
    url: str
    snippet: str


class SentimentResponse(BaseModel):
    available: bool
    league: str
    home_team: str
    away_team: str
    summary: str
    sources: list[SourceOut]
    fetched_at: str


class UnavailableResponse(BaseModel):
    available: bool
    reason: str


@app.get("/health")
async def health() -> dict:
    return {"status": "ok"}


def _summarize(
    home: str,
    away: str,
    odds_results: list[SearchResult],
    injury_results: list[SearchResult],
) -> tuple[str, list[SearchResult]]:
    """Build an honest, non-fabricated summary from search snippets.

    If nothing useful was found, says so plainly instead of inventing
    sentiment or injury news.
    """
    all_sources: list[SearchResult] = []
    seen_urls: set[str] = set()
    for r in odds_results + injury_results:
        if r.url not in seen_urls:
            all_sources.append(r)
            seen_urls.add(r.url)

    if not all_sources:
        return (
            f"No clear betting sentiment or injury signal was found for "
            f"{away} at {home}.",
            [],
        )

    parts: list[str] = []
    if odds_results:
        snippet = odds_results[0].snippet.strip()
        if snippet:
            parts.append(f"Betting chatter: {snippet}")
    if injury_results:
        snippet = injury_results[0].snippet.strip()
        if snippet:
            parts.append(f"Injury/goalie buzz: {snippet}")

    if not parts:
        parts.append(
            f"Found {len(all_sources)} source(s) mentioning {away} at {home}, "
            "but no specific sentiment or injury detail stood out in the snippets — "
            "see sources below."
        )

    summary = " ".join(parts)
    return summary, all_sources[:6]


@app.get("/betting-sentiment")
async def betting_sentiment(
    league: str = Query(...),
    home: str = Query(...),
    away: str = Query(...),
) -> dict:
    api_key = os.environ.get("SEARCH_API_KEY", "").strip()
    if not api_key:
        return {
            "available": False,
            "reason": "SEARCH_API_KEY not configured — see README for setup",
        }

    league_norm = league.strip().upper()
    home_norm = home.strip().upper()
    away_norm = away.strip().upper()
    cache_key = (league_norm, home_norm, away_norm)

    cached = _cache.get(cache_key)
    if cached is not None:
        return cached

    provider_name = os.environ.get("SEARCH_PROVIDER")
    try:
        provider = get_provider(api_key, provider_name)
    except ValueError as exc:
        return {"available": False, "reason": str(exc)}

    odds_query = f"{away_norm} at {home_norm} NHL betting odds sentiment"
    injury_query = f"{home_norm} OR {away_norm} NHL injury report starting goalie"

    try:
        odds_results = await provider.search(odds_query, num_results=5)
        injury_results = await provider.search(injury_query, num_results=5)
    except SearchProviderError as exc:
        # Never 500 just because the upstream search API had a problem —
        # report an honest "not available" instead.
        return {"available": False, "reason": f"search provider error: {exc}"}

    summary, sources = _summarize(home_norm, away_norm, odds_results, injury_results)

    response = {
        "available": True,
        "league": league_norm,
        "home_team": home_norm,
        "away_team": away_norm,
        "summary": summary,
        "sources": [
            {"title": s.title, "url": s.url, "snippet": s.snippet} for s in sources
        ],
        "fetched_at": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
    }

    _cache.set(cache_key, response)
    return response
