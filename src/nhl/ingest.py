"""
Pull historical NHL game results from the free public NHL API
(api-web.nhle.com -- no key required). Requires internet access -- run
from your local Claude Code environment, not a network-isolated sandbox.
"""
from __future__ import annotations

import time
import requests
import pandas as pd

BASE = "https://api-web.nhle.com/v1"


def load_season_games(season: str) -> pd.DataFrame:
    """season format: '20242025'. Pulls the full regular-season schedule
    with final scores via the club schedule-season endpoint, team by team,
    then dedupes -- the NHL API doesn't expose a single league-wide game
    dump, so this is the practical way to assemble one."""
    teams = _team_abbreviations()
    rows = []
    seen_game_ids = set()

    for team in teams:
        url = f"{BASE}/club-schedule-season/{team}/{season}"
        resp = requests.get(url, timeout=15)
        if resp.status_code != 200:
            continue
        for g in resp.json().get("games", []):
            if g.get("gameType") != 2:  # 2 = regular season
                continue
            if g["id"] in seen_game_ids:
                continue
            if g.get("gameState") not in ("OFF", "FINAL"):
                continue
            seen_game_ids.add(g["id"])
            rows.append({
                "game_id": g["id"],
                "date": g["gameDate"],
                "home_team": g["homeTeam"]["abbrev"],
                "away_team": g["awayTeam"]["abbrev"],
                "home_score": g["homeTeam"].get("score"),
                "away_score": g["awayTeam"].get("score"),
            })
        time.sleep(0.2)  # be polite to the free API

    df = pd.DataFrame(rows).dropna(subset=["home_score", "away_score"])
    df["date"] = pd.to_datetime(df["date"])
    return df.sort_values("date").reset_index(drop=True)


def _team_abbreviations() -> list[str]:
    resp = requests.get(f"{BASE}/standings/now", timeout=15)
    data = resp.json()
    return [t["teamAbbrev"]["default"] for t in data.get("standings", [])]


if __name__ == "__main__":
    games = load_season_games("20252026")
    games.to_csv("data/nhl_games.csv", index=False)
    print(f"Saved {len(games)} games to data/nhl_games.csv")
