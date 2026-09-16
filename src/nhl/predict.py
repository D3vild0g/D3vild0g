"""
CLI: train the NHL model on data/nhl_games.csv and predict a matchup.

    python -m src.nhl.predict --home TOR --away MTL
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

import pandas as pd

sys.path.append(str(Path(__file__).resolve().parents[2]))
from src.nhl.model import NHLModel
from src.common import storage


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--home", required=True)
    parser.add_argument("--away", required=True)
    parser.add_argument("--data", default="data/nhl_games.csv")
    parser.add_argument("--game-id", default=None)
    args = parser.parse_args()

    games = pd.read_csv(args.data, parse_dates=["date"])
    model = NHLModel.train(games)
    result = model.predict(args.home, args.away)

    print(f"\n{result['away_team']} @ {result['home_team']}")
    print(f"  {result['home_team']} win prob: {result['home_win_prob']:.1%}")
    print(f"  {result['away_team']} win prob: {result['away_win_prob']:.1%}")
    print(f"  Predicted score: {result['home_team']} {result['predicted_home_goals']} - "
          f"{result['predicted_away_goals']} {result['away_team']}")
    print(f"  Elo: {result['home_team']} {result['home_elo']} vs {result['away_team']} {result['away_elo']}")

    if args.game_id:
        storage.log_prediction(
            league="NHL",
            game_id=args.game_id,
            home_team=args.home,
            away_team=args.away,
            home_win_prob=result["home_win_prob"],
            predicted_home_score=result["predicted_home_goals"],
            predicted_away_score=result["predicted_away_goals"],
        )
        print(f"  Logged prediction under game_id={args.game_id}")


if __name__ == "__main__":
    main()
