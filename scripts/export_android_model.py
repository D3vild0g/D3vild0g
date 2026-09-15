"""
Trains the NFL and NHL models on data/*.csv and exports the state needed
for on-device inference (Elo ratings, recent-form/goal-history windows,
and fitted regression coefficients) as JSON assets for the Android app.

No training happens on-device -- the Android app only does the forward
pass (sigmoid / linear / Poisson+Skellam) using these bundled numbers.

    python scripts/export_android_model.py
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import pandas as pd

sys.path.append(str(Path(__file__).resolve().parents[1]))
from src.nfl.model import NFLModel, RECENT_FORM_WINDOW
from src.nhl.model import NHLModel, GOAL_FORM_WINDOW, ELO_BLEND_WEIGHT

OUT_DIR = Path(__file__).resolve().parents[1] / "android" / "app" / "src" / "main" / "assets"


def export_nfl() -> None:
    games = pd.read_csv("data/nfl_games.csv", parse_dates=["gameday"])
    model = NFLModel.train(games)

    payload = {
        "homeAdvantage": model.elo.config.home_advantage,
        "eloRatings": {t: round(r, 2) for t, r in model.elo.ratings.items()},
        "recentFormWindow": RECENT_FORM_WINDOW,
        "teamForm": {
            t: [round(x, 1) for x in form[-RECENT_FORM_WINDOW:]]
            for t, form in model.team_form.items()
        },
        "winClf": {
            "coef": model.win_clf.coef_[0].round(6).tolist(),
            "intercept": round(float(model.win_clf.intercept_[0]), 6),
        },
        "marginReg": {
            "coef": model.margin_reg.coef_.round(6).tolist(),
            "intercept": round(float(model.margin_reg.intercept_), 6),
        },
        "featureOrder": ["elo_diff", "rest_diff", "form_diff"],
    }
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    (OUT_DIR / "nfl_model.json").write_text(json.dumps(payload, indent=1, sort_keys=True))
    print(f"NFL: {len(payload['eloRatings'])} teams -> {OUT_DIR / 'nfl_model.json'}")


def export_nhl() -> None:
    games = pd.read_csv("data/nhl_games.csv", parse_dates=["date"])
    model = NHLModel.train(games)

    params = model.poisson_model.params
    payload = {
        "homeAdvantage": model.elo.config.home_advantage,
        "eloBlendWeight": ELO_BLEND_WEIGHT,
        "leagueAvgGoals": round(float(model.league_avg_goals), 4),
        "goalFormWindow": GOAL_FORM_WINDOW,
        "eloRatings": {t: round(r, 2) for t, r in model.elo.ratings.items()},
        "teamGoalHistory": {
            t: [[round(gf, 1), round(ga, 1)] for gf, ga in hist[-GOAL_FORM_WINDOW:]]
            for t, hist in model.team_goal_history.items()
        },
        "poissonModel": {
            "intercept": round(float(params["Intercept"]), 6),
            "teamGf": round(float(params["team_gf"]), 6),
            "oppGa": round(float(params["opp_ga"]), 6),
            "isHome": round(float(params["is_home"]), 6),
        },
    }
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    (OUT_DIR / "nhl_model.json").write_text(json.dumps(payload, indent=1, sort_keys=True))
    print(f"NHL: {len(payload['eloRatings'])} teams -> {OUT_DIR / 'nhl_model.json'}")


if __name__ == "__main__":
    export_nfl()
    export_nhl()
