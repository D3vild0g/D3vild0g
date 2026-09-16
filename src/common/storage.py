"""
SQLite-backed prediction log. Every prediction the model makes gets stored
here so you can grade it against the real outcome later and track whether
the model is actually any good over time -- not just accurate in backtests.
"""
from __future__ import annotations

import sqlite3
from datetime import datetime, timezone
from pathlib import Path

DB_PATH = Path(__file__).resolve().parents[2] / "data" / "predictions.db"


def _connect() -> sqlite3.Connection:
    DB_PATH.parent.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(DB_PATH)
    conn.execute("""
        CREATE TABLE IF NOT EXISTS predictions (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            league TEXT NOT NULL,
            game_id TEXT NOT NULL,
            predicted_at TEXT NOT NULL,
            home_team TEXT NOT NULL,
            away_team TEXT NOT NULL,
            home_win_prob REAL NOT NULL,
            predicted_home_score REAL,
            predicted_away_score REAL,
            model_version TEXT,
            actual_home_score REAL,
            actual_away_score REAL,
            graded INTEGER DEFAULT 0
        )
    """)
    conn.commit()
    return conn


def log_prediction(
    league: str,
    game_id: str,
    home_team: str,
    away_team: str,
    home_win_prob: float,
    predicted_home_score: float | None = None,
    predicted_away_score: float | None = None,
    model_version: str = "v1",
) -> int:
    conn = _connect()
    cur = conn.execute(
        """INSERT INTO predictions
           (league, game_id, predicted_at, home_team, away_team, home_win_prob,
            predicted_home_score, predicted_away_score, model_version)
           VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
        (
            league, game_id, datetime.now(timezone.utc).isoformat(),
            home_team, away_team, home_win_prob,
            predicted_home_score, predicted_away_score, model_version,
        ),
    )
    conn.commit()
    row_id = cur.lastrowid
    conn.close()
    return row_id


def grade_prediction(game_id: str, actual_home_score: float, actual_away_score: float) -> None:
    conn = _connect()
    conn.execute(
        """UPDATE predictions SET actual_home_score = ?, actual_away_score = ?, graded = 1
           WHERE game_id = ?""",
        (actual_home_score, actual_away_score, game_id),
    )
    conn.commit()
    conn.close()


def get_graded_predictions(league: str) -> list[tuple[float, int]]:
    """Returns (predicted_home_win_prob, actual_home_won) pairs for backtest.summarize()."""
    conn = _connect()
    rows = conn.execute(
        """SELECT home_win_prob, actual_home_score, actual_away_score
           FROM predictions WHERE league = ? AND graded = 1""",
        (league,),
    ).fetchall()
    conn.close()
    return [(p, 1 if hs > aws else 0) for p, hs, aws in rows]


def track_record(league: str) -> list[dict]:
    conn = _connect()
    conn.row_factory = sqlite3.Row
    rows = conn.execute(
        "SELECT * FROM predictions WHERE league = ? ORDER BY predicted_at DESC",
        (league,),
    ).fetchall()
    conn.close()
    return [dict(r) for r in rows]
