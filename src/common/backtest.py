"""
Backtesting utilities: score a list of (predicted_prob, actual_outcome) pairs
using the metrics that actually matter for probabilistic predictions.
Accuracy alone is misleading for a model that outputs probabilities --
Brier score and log loss reward good calibration, not just picking winners.
"""
from __future__ import annotations

import math


def accuracy(predictions: list[tuple[float, int]], threshold: float = 0.5) -> float:
    correct = sum(1 for p, y in predictions if (p >= threshold) == bool(y))
    return correct / len(predictions) if predictions else 0.0


def brier_score(predictions: list[tuple[float, int]]) -> float:
    """Mean squared error between predicted probability and outcome.
    Lower is better. 0.25 is what you'd get guessing 50/50 every time."""
    if not predictions:
        return float("nan")
    return sum((p - y) ** 2 for p, y in predictions) / len(predictions)


def log_loss(predictions: list[tuple[float, int]], eps: float = 1e-15) -> float:
    if not predictions:
        return float("nan")
    total = 0.0
    for p, y in predictions:
        p = min(max(p, eps), 1 - eps)
        total += -(y * math.log(p) + (1 - y) * math.log(1 - p))
    return total / len(predictions)


def calibration_curve(predictions: list[tuple[float, int]], bins: int = 10):
    """Bucket predictions by confidence and compare predicted vs actual
    win rate in each bucket. A well-calibrated model's 70%-confidence picks
    should win about 70% of the time."""
    buckets = [[] for _ in range(bins)]
    for p, y in predictions:
        idx = min(int(p * bins), bins - 1)
        buckets[idx].append((p, y))
    rows = []
    for i, b in enumerate(buckets):
        if not b:
            continue
        lo, hi = i / bins, (i + 1) / bins
        avg_pred = sum(p for p, _ in b) / len(b)
        actual_rate = sum(y for _, y in b) / len(b)
        rows.append({
            "range": f"{lo:.1f}-{hi:.1f}",
            "n": len(b),
            "avg_predicted": round(avg_pred, 3),
            "actual_win_rate": round(actual_rate, 3),
        })
    return rows


def summarize(predictions: list[tuple[float, int]]) -> dict:
    return {
        "n": len(predictions),
        "accuracy": round(accuracy(predictions), 4),
        "brier_score": round(brier_score(predictions), 4),
        "log_loss": round(log_loss(predictions), 4),
        "calibration": calibration_curve(predictions),
    }
