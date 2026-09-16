"""
Pull historical NFL schedules/results via nfl_data_py (wraps nflverse's
free public data, no API key needed). Requires internet access -- run this
from your local Claude Code environment, not a network-isolated sandbox.

    pip install nfl_data_py
"""
from __future__ import annotations

import pandas as pd


def load_schedules(years: list[int]) -> pd.DataFrame:
    """Returns one row per game: teams, scores, date, rest days, etc."""
    import nfl_data_py as nfl
    df = nfl.import_schedules(years)
    keep = [
        "game_id", "season", "week", "gameday", "home_team", "away_team",
        "home_score", "away_score", "home_rest", "away_rest",
        "div_game", "roof", "surface",
    ]
    df = df[[c for c in keep if c in df.columns]].copy()
    df = df.dropna(subset=["home_score", "away_score"])  # drop unplayed games
    df["gameday"] = pd.to_datetime(df["gameday"])
    return df.sort_values("gameday").reset_index(drop=True)


def load_team_week_stats(years: list[int]) -> pd.DataFrame:
    """Weekly team-level EPA and efficiency stats, useful as extra features
    beyond Elo (offensive/defensive EPA per play, success rate, etc.)."""
    import nfl_data_py as nfl
    weekly = nfl.import_weekly_data(years)
    team_week = (
        weekly.groupby(["season", "week", "recent_team"])
        .agg(
            pass_epa=("passing_epa", "mean"),
            rush_epa=("rushing_epa", "mean"),
        )
        .reset_index()
        .rename(columns={"recent_team": "team"})
    )
    return team_week


if __name__ == "__main__":
    games = load_schedules([2023, 2024, 2025])
    games.to_csv("data/nfl_games.csv", index=False)
    print(f"Saved {len(games)} games to data/nfl_games.csv")
