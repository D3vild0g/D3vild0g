"""
NHL model.

Two components:
1. Elo ratings (lower home-ice edge and lower K than NFL -- hockey has more
   game-to-game variance from puck luck, so ratings should move slower)
   give the primary win probability.
2. A Poisson regression predicts each team's expected goals this game from
   their rolling offensive/defensive goal rates. Poisson is the standard
   choice for goal-scoring in hockey/soccer since goals are low-count,
   non-negative integers, not a continuous or binary outcome.

Win probability is then cross-checked via the Skellam distribution (the
distribution of the difference of two Poisson variables), and blended with
the Elo win probability for the final number.
"""
from __future__ import annotations

from dataclasses import dataclass, field

import numpy as np
import pandas as pd
from scipy.stats import skellam
import statsmodels.api as sm
import statsmodels.formula.api as smf

import sys
from pathlib import Path
sys.path.append(str(Path(__file__).resolve().parents[1]))
from common.elo import EloRatings, EloConfig

GOAL_FORM_WINDOW = 10  # games, for rolling goal-rate features
ELO_BLEND_WEIGHT = 0.5  # 0 = pure Poisson/Skellam, 1 = pure Elo


@dataclass
class NHLModel:
    elo: EloRatings
    poisson_model: object
    team_goal_history: dict  # team -> list of (goals_for, goals_against)
    league_avg_goals: float

    @classmethod
    def train(cls, games: pd.DataFrame) -> "NHLModel":
        """games: output of ingest.load_season_games(), sorted by date ascending."""
        elo = EloRatings(EloConfig(k_factor=6.0, home_advantage=25.0, mov_multiplier=True))
        history: dict[str, list[tuple[float, float]]] = {}
        rows = []

        league_avg_goals = (games["home_score"].mean() + games["away_score"].mean()) / 2

        for _, g in games.iterrows():
            home, away, hs, aws = g["home_team"], g["away_team"], g["home_score"], g["away_score"]

            home_gf = np.mean([x[0] for x in history.get(home, [])][-GOAL_FORM_WINDOW:]) or league_avg_goals
            home_ga = np.mean([x[1] for x in history.get(home, [])][-GOAL_FORM_WINDOW:]) or league_avg_goals
            away_gf = np.mean([x[0] for x in history.get(away, [])][-GOAL_FORM_WINDOW:]) or league_avg_goals
            away_ga = np.mean([x[1] for x in history.get(away, [])][-GOAL_FORM_WINDOW:]) or league_avg_goals

            # two training rows per game: one for the home side's goals, one for away's
            rows.append({"team_gf": home_gf, "opp_ga": away_ga, "is_home": 1, "goals": hs})
            rows.append({"team_gf": away_gf, "opp_ga": home_ga, "is_home": 0, "goals": aws})

            elo.update(home, away, hs, aws, home_team=home)
            history.setdefault(home, []).append((hs, aws))
            history.setdefault(away, []).append((aws, hs))

        df = pd.DataFrame(rows)
        poisson_model = smf.glm(
            formula="goals ~ team_gf + opp_ga + is_home",
            data=df,
            family=sm.families.Poisson(),
        ).fit()

        return cls(elo=elo, poisson_model=poisson_model, team_goal_history=history,
                    league_avg_goals=league_avg_goals)

    def _expected_goals(self, team: str, opponent: str, is_home: int) -> float:
        hist = self.team_goal_history.get(team, [])
        opp_hist = self.team_goal_history.get(opponent, [])
        team_gf = np.mean([x[0] for x in hist][-GOAL_FORM_WINDOW:]) if hist else self.league_avg_goals
        opp_ga = np.mean([x[1] for x in opp_hist][-GOAL_FORM_WINDOW:]) if opp_hist else self.league_avg_goals
        X = pd.DataFrame([{"team_gf": team_gf, "opp_ga": opp_ga, "is_home": is_home}])
        return float(self.poisson_model.predict(X)[0])

    def predict(self, home: str, away: str) -> dict:
        home_xg = self._expected_goals(home, away, is_home=1)
        away_xg = self._expected_goals(away, home, is_home=0)

        # Skellam: P(home_goals - away_goals > 0), with a split of the tie
        # mass (hockey games end in regulation ties ~20% of the time, going
        # to OT/shootout which is close to a coin flip)
        skellam_home_win = 1 - skellam.cdf(0, home_xg, away_xg)
        skellam_tie = skellam.pmf(0, home_xg, away_xg)
        skellam_prob = skellam_home_win + 0.5 * skellam_tie

        elo_prob = self.elo.win_probability(home, away, home_team=home)

        blended = ELO_BLEND_WEIGHT * elo_prob + (1 - ELO_BLEND_WEIGHT) * skellam_prob

        return {
            "home_team": home,
            "away_team": away,
            "home_win_prob": round(float(blended), 4),
            "away_win_prob": round(float(1 - blended), 4),
            "predicted_home_goals": round(home_xg, 2),
            "predicted_away_goals": round(away_xg, 2),
            "home_elo": round(self.elo.get(home), 1),
            "away_elo": round(self.elo.get(away), 1),
        }
