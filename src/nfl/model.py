"""
NFL win-probability model.

Approach: Elo captures team strength over time via game-by-game updates,
but raw Elo win probability is often poorly calibrated for a specific
league. So we compute Elo ratings first, then train a logistic regression
on top using [elo_diff, home_flag, rest_diff, recent_form_diff] as
features. Logistic regression is the right tool here because the target
(win/loss) is binary and we want calibrated probabilities, not just a
classification.

Score margin is predicted separately with plain linear regression on the
same features, giving a point-spread estimate alongside the win prob.
"""
from __future__ import annotations

from dataclasses import dataclass

import numpy as np
import pandas as pd
from sklearn.linear_model import LogisticRegression, LinearRegression

import sys
from pathlib import Path
sys.path.append(str(Path(__file__).resolve().parents[1]))
from common.elo import EloRatings, EloConfig


RECENT_FORM_WINDOW = 5  # games


@dataclass
class NFLModel:
    elo: EloRatings
    win_clf: LogisticRegression
    margin_reg: LinearRegression
    team_form: dict  # team -> list of recent point diffs, most recent last

    @classmethod
    def train(cls, games: pd.DataFrame) -> "NFLModel":
        """games: output of ingest.load_schedules(), sorted by date ascending."""
        elo = EloRatings(EloConfig(k_factor=20.0, home_advantage=55.0))
        team_form: dict[str, list[float]] = {}
        rows = []

        for _, g in games.iterrows():
            home, away = g["home_team"], g["away_team"]
            hs, aws = g["home_score"], g["away_score"]

            elo_diff = elo.get(home) + elo.config.home_advantage - elo.get(away)
            rest_diff = (g.get("home_rest", 7) or 7) - (g.get("away_rest", 7) or 7)
            home_form = np.mean(team_form.get(home, [0])[-RECENT_FORM_WINDOW:])
            away_form = np.mean(team_form.get(away, [0])[-RECENT_FORM_WINDOW:])
            form_diff = home_form - away_form

            rows.append({
                "elo_diff": elo_diff,
                "rest_diff": rest_diff,
                "form_diff": form_diff,
                "home_won": 1 if hs > aws else 0,
                "margin": hs - aws,
            })

            # update state AFTER using pre-game values as features
            elo.update(home, away, hs, aws, home_team=home)
            team_form.setdefault(home, []).append(hs - aws)
            team_form.setdefault(away, []).append(aws - hs)

        df = pd.DataFrame(rows)
        X = df[["elo_diff", "rest_diff", "form_diff"]].values
        y_win = df["home_won"].values
        y_margin = df["margin"].values

        clf = LogisticRegression()
        clf.fit(X, y_win)

        reg = LinearRegression()
        reg.fit(X, y_margin)

        return cls(elo=elo, win_clf=clf, margin_reg=reg, team_form=team_form)

    def predict(self, home: str, away: str, home_rest: int = 7, away_rest: int = 7) -> dict:
        elo_diff = self.elo.get(home) + self.elo.config.home_advantage - self.elo.get(away)
        rest_diff = home_rest - away_rest
        home_form = np.mean(self.team_form.get(home, [0])[-RECENT_FORM_WINDOW:])
        away_form = np.mean(self.team_form.get(away, [0])[-RECENT_FORM_WINDOW:])
        form_diff = home_form - away_form

        X = np.array([[elo_diff, rest_diff, form_diff]])
        win_prob = float(self.win_clf.predict_proba(X)[0][1])
        margin = float(self.margin_reg.predict(X)[0])

        return {
            "home_team": home,
            "away_team": away,
            "home_win_prob": round(win_prob, 4),
            "away_win_prob": round(1 - win_prob, 4),
            "predicted_margin_home": round(margin, 1),
            "home_elo": round(self.elo.get(home), 1),
            "away_elo": round(self.elo.get(away), 1),
        }
