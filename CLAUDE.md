# Sports Predictor — NFL & NHL

Statistical prediction models for NFL and NHL games. Not a betting tool —
treat outputs as probability estimates to evaluate, not certainties.

## Architecture

- `src/common/elo.py` — shared Elo rating engine (margin-of-victory scaled,
  538-style). Home advantage and K-factor differ by sport (see `model.py`
  in each sport folder — NFL uses K=20/home=55, NHL uses K=6/home=25,
  since hockey has much higher game-to-game variance).
- `src/nfl/model.py` — Elo → logistic regression (win prob) + linear
  regression (point margin), features: elo_diff, rest_diff, recent-form
  point differential.
- `src/nhl/model.py` — Elo (win prob) blended 50/50 with a Poisson
  goal-scoring model run through the Skellam distribution (goal
  differential distribution) for a second win-prob estimate. Poisson is
  used because goals are low-count non-negative integers, not a
  continuous outcome — a straight regression would be the wrong tool here.
- `src/common/storage.py` — SQLite log of every prediction made
  (`data/predictions.db`), so real accuracy can be tracked over time
  against backtest numbers.
- `src/common/backtest.py` — accuracy, Brier score, log loss, calibration
  curve. Brier score and log loss matter more than accuracy for a
  probabilistic model — a model that says 55% and wins 55% of the time is
  doing its job even if it "gets it wrong" 45% of the time.

## Data

Ingestion scripts (`src/nfl/ingest.py`, `src/nhl/ingest.py`) need internet
access and should be run in this local environment, not a sandboxed one.

- NFL: `nfl_data_py` (wraps free nflverse data)
- NHL: free public NHL API (`api-web.nhle.com`)

Run ingestion before training:
```
python -m src.nfl.ingest   # writes data/nfl_games.csv
python -m src.nhl.ingest   # writes data/nhl_games.csv
```

## Common commands

```
python -m src.nfl.predict --home KC --away DEN
python -m src.nhl.predict --home TOR --away MTL
python scripts/backtest_nfl.py --test-season 2025
```

## Conventions

- Elo ratings are per-instance state on a trained model object — never
  reuse a stale trained model across seasons without re-running
  `regress_to_mean()` first if extending to multi-season carryover.
- Always compute features from data *before* the game being predicted —
  never let a model see same-game results as inputs (see how
  `model.train()` updates Elo/form *after* using pre-game values as
  features — preserve that order in any edits).
- When extending features (e.g. adding injuries, weather, goalie starts),
  add them to both the training-row construction in `train()` and the
  matching `predict()` feature vector — they must stay in sync.
