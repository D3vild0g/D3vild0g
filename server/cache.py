"""Tiny in-memory TTL cache. A plain dict with timestamps — no Redis needed
for a personal side project with low request volume."""

from __future__ import annotations

import time
from typing import Any, Hashable


class TTLCache:
    def __init__(self, ttl_seconds: float = 1800.0):
        self.ttl_seconds = ttl_seconds
        self._store: dict[Hashable, tuple[float, Any]] = {}

    def get(self, key: Hashable) -> Any | None:
        entry = self._store.get(key)
        if entry is None:
            return None
        stored_at, value = entry
        if time.monotonic() - stored_at > self.ttl_seconds:
            self._store.pop(key, None)
            return None
        return value

    def set(self, key: Hashable, value: Any) -> None:
        self._store[key] = (time.monotonic(), value)

    def clear(self) -> None:
        self._store.clear()
