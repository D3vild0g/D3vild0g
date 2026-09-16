# Sports Predictor — NFL & NHL

Statistical models for predicting NFL and NHL game outcomes.

- **NFL**: Elo ratings feed a logistic regression (win probability) and a
  linear regression (predicted point margin), using Elo diff, rest-day
  difference, and recent-form point differential as features.
- **NHL**: Elo ratings blended with a Poisson regression on rolling
  offensive/defensive goal rates — goals are count data, so Poisson (run
  through the Skellam distribution for win probability) fits better than
  a logistic model alone.

Both are backed by an Elo engine in `src/common/elo.py`, and every
prediction can be logged to a local SQLite DB (`src/common/storage.py`) so
you can grade the model against real results over time.

## Setup

```bash
cd sports-predictor
python -m venv venv && source venv/bin/activate
pip install -r requirements.txt
```

## 1. Pull historical data (needs internet)

```bash
python -m src.nfl.ingest    # -> data/nfl_games.csv
python -m src.nhl.ingest    # -> data/nhl_games.csv
```

NFL data comes from `nfl_data_py` (free, wraps nflverse). NHL data comes
from the free public NHL API. Both are unauthenticated — no API keys
needed. Adjust the season list/year in each `ingest.py`'s `__main__`
block.

## 2. Predict a matchup

```bash
python -m src.nfl.predict --home KC --away DEN
python -m src.nhl.predict --home TOR --away MTL
```

Add `--game-id some-id` to log the prediction to `data/predictions.db` for
later grading.

## 3. Backtest

```bash
python scripts/backtest_nfl.py --data data/nfl_games.csv --test-season 2025
```

Walk-forward backtest: trains only on games before the test season, so
results reflect genuine predictive performance, not lookback bias. Reports
accuracy, Brier score, log loss, and a calibration table. Note: the
current script retrains from scratch for each game in the test season for
simplicity — fine for a season's worth of games, but swap in incremental
Elo updates if you extend the backtest window much further.

## 4. Grade predictions over time

Once a logged game finishes:

```python
from src.common.storage import grade_prediction
grade_prediction(game_id="some-id", actual_home_score=27, actual_away_score=24)
```

Then pull `get_graded_predictions("NFL")` and run it through
`src.common.backtest.summarize()` to see real-world accuracy vs. the
backtest numbers.

## 5. Android app

`android/` is a standalone native Kotlin app (no Python runtime on-device).
The win-probability *model* itself is a static snapshot — it reads bundled
JSON (`android/app/src/main/assets/{nfl,nhl}_model.json`: Elo ratings,
recent-form windows, fitted regression coefficients) and re-runs only the
forward pass (sigmoid / linear / Poisson+Skellam) on device, no training
on the phone. Everything else in the app — schedule, kickoff times,
player stats, injuries, betting lines — is fetched live over the internet
each time you open it, from the same free sources the Python ingest
scripts use:

- **Schedule + kickoff times + real betting lines (NFL)**:
  `http://www.habitatring.com/games.csv` — also has moneyline/spread/total,
  so NFL betting info needs no backend or API key.
- **Schedule + kickoff times (NHL)**: `api-web.nhle.com`.
- **Player season stat leaders**: nflverse `player_stats` release (NFL),
  `api-web.nhle.com` club-stats (NHL).
- **Injury reports (NFL only)**: nflverse `injuries` release. There's no
  free structured injury-report API for NHL.
- **NHL betting sentiment / injury chatter**: NHL has no free structured
  odds source either, so this one goes through a small backend
  (`server/`) that does a real web search — see `server/README.md` to
  deploy your own and point the app at it (Settings icon in the app).
  Without a configured backend it just shows "not available" rather than
  guessing.

Regenerate the static model snapshots after pulling fresh training data:

```bash
python -m src.nfl.ingest && python -m src.nhl.ingest
python scripts/export_android_model.py
```

Then build the APK:

```bash
cd android && ./gradlew assembleDebug
# -> android/app/build/outputs/apk/debug/app-debug.apk
```

Only the Elo/regression predictions reflect data as of the last export —
schedule, lines, stats, and injuries are always live as of when you open
the app (subject to each source's own update lag; nflverse's weekly
player-stats release in particular can lag a week or two into a new
season).

## Extending

The model interfaces (`NFLModel.train()` / `.predict()`, same for NHL)
are intentionally simple — adding features means updating the row
construction in `train()` and the matching vector in `predict()`
together. Good next additions: injury reports, QB/goalie-specific
adjustments, weather (NFL), back-to-back fatigue (NHL, partially handled
via Elo's game-to-game granularity already).

This is a probability tool, not a betting system — treat its outputs as
inputs to your own judgment, not as picks.
