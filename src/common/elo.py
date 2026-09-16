"""
Generic Elo rating engine with margin-of-victory scaling (538-style).

Used as the base signal for both NFL and NHL models. Elo alone gives a
reasonable win probability; each sport-specific model layers a regression
on top of the Elo diff plus other features to calibrate and improve it.
"""
from __future__ import annotations

import math
from dataclasses import dataclass, field


@dataclass
class EloConfig:
    base_rating: float = 1500.0
    k_factor: float = 20.0          # base update speed
    home_advantage: float = 55.0    # rating-point edge for home team
    mov_multiplier: bool = True     # scale K by margin of victory
    season_regression: float = 0.33 # fraction reverted to mean between seasons


class EloRatings:
    def __init__(self, config: EloConfig | None = None):
        self.config = config or EloConfig()
        self.ratings: dict[str, float] = {}

    def get(self, team: str) -> float:
        return self.ratings.setdefault(team, self.config.base_rating)

    def expected_score(self, rating_a: float, rating_b: float) -> float:
        """Probability team A beats team B given their ratings."""
        return 1.0 / (1.0 + 10 ** ((rating_b - rating_a) / 400.0))

    def win_probability(self, team_a: str, team_b: str, home_team: str | None = None) -> float:
        ra, rb = self.get(team_a), self.get(team_b)
        if home_team == team_a:
            ra += self.config.home_advantage
        elif home_team == team_b:
            rb += self.config.home_advantage
        return self.expected_score(ra, rb)

    def _mov_multiplier(self, point_diff: float, elo_diff_winner: float) -> float:
        """538-style margin-of-victory multiplier; dampens blowout inflation
        and accounts for the favorite winning big being less informative."""
        if not self.config.mov_multiplier:
            return 1.0
        point_diff = max(abs(point_diff), 1)
        return math.log(point_diff + 1) * (2.2 / ((abs(elo_diff_winner) * 0.001) + 2.2))

    def update(
        self,
        team_a: str,
        team_b: str,
        score_a: float,
        score_b: float,
        home_team: str | None = None,
    ) -> tuple[float, float]:
        """Update ratings after a game. Returns (new_rating_a, new_rating_b)."""
        ra, rb = self.get(team_a), self.get(team_b)
        ra_eff, rb_eff = ra, rb
        if home_team == team_a:
            ra_eff += self.config.home_advantage
        elif home_team == team_b:
            rb_eff += self.config.home_advantage

        exp_a = self.expected_score(ra_eff, rb_eff)
        actual_a = 1.0 if score_a > score_b else (0.0 if score_a < score_b else 0.5)

        winner_elo_diff = (ra_eff - rb_eff) if score_a >= score_b else (rb_eff - ra_eff)
        mult = self._mov_multiplier(score_a - score_b, winner_elo_diff)

        delta = self.config.k_factor * mult * (actual_a - exp_a)
        self.ratings[team_a] = ra + delta
        self.ratings[team_b] = rb - delta
        return self.ratings[team_a], self.ratings[team_b]

    def regress_to_mean(self) -> None:
        """Call once between seasons so ratings don't carry over fully."""
        f = self.config.season_regression
        for team in list(self.ratings.keys()):
            self.ratings[team] = (
                self.ratings[team] * (1 - f) + self.config.base_rating * f
            )
