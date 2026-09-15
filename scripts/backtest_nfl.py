"""
Walk-forward backtest: for each season in the data, train only on games
before that season, predict every game in it, then compare against what
actually happened. This avoids the trap of training and testing on the
same games, which would make the model look artificially good.

    python scripts/backtest_nfl.py --data data/nfl_games.csv --test-season 2025
"""
import argparse
import sys
from pathlib import Path

import pandas as pd

sys.path.append(str(Path(__file__).resolve().parents[1]))
from src.nfl.model import NFLModel
from src.common.backtest import summarize


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--data", default="data/nfl_games.csv")
    parser.add_argument("--test-season", type=int, required=True)
    args = parser.parse_args()

    games = pd.read_csv(args.data, parse_dates=["gameday"])
    train_games = games[games["season"] < args.test_season].reset_index(drop=True)
    test_games = games[games["season"] == args.test_season].sort_values("gameday")

    predictions = []
    for _, g in test_games.iterrows():
        model = NFLModel.train(train_games)  # retrain fresh each time in this simple version
        result = model.predict(g["home_team"], g["away_team"],
                                g.get("home_rest", 7), g.get("away_rest", 7))
        actual = 1 if g["home_score"] > g["away_score"] else 0
        predictions.append((result["home_win_prob"], actual))
        train_games = pd.concat([train_games, g.to_frame().T], ignore_index=True)

    print(f"Backtest: NFL {args.test_season} season, {len(predictions)} games")
    for k, v in summarize(predictions).items():
        print(f"  {k}: {v}")


if __name__ == "__main__":
    main()
