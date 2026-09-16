# NHL Betting Sentiment Service

A small standalone FastAPI backend for the sports-predictor Android app.

## Why this exists (and why it's NHL-only)

For NFL, the app already gets real sportsbook lines (moneyline/spread/total)
for free from a structured CSV
(`http://www.habitatring.com/games.csv`) — no backend needed there.

NHL has no equivalent free, structured odds or injury-report source. This
service fills that gap as a *fallback*: it searches the web for public
betting sentiment / line-movement chatter and injury / starting-goalie
buzz for a given NHL matchup, and returns a short honest summary with
source links. It never scrapes Google/Reddit/sportsbook HTML directly —
it only calls a real, documented search API with an API key.

If nothing useful is found, or the service isn't configured, it says so
plainly (`available: false` or a "no clear signal" summary) rather than
making anything up.

## Endpoints

### `GET /health`
Liveness check.
```json
{"status": "ok"}
```

### `GET /betting-sentiment?league=NHL&home=TOR&away=MTL`

**When configured and search succeeds:**
```json
{
  "available": true,
  "league": "NHL",
  "home_team": "TOR",
  "away_team": "MTL",
  "summary": "Betting chatter: ... Injury/goalie buzz: ...",
  "sources": [
    {"title": "...", "url": "...", "snippet": "..."}
  ],
  "fetched_at": "2026-09-16T12:00:00Z"
}
```

**When `SEARCH_API_KEY` is not set (or the provider fails):**
```json
{
  "available": false,
  "reason": "SEARCH_API_KEY not configured — see README for setup"
}
```
This always returns HTTP 200, so the Android client can show a graceful
"not available" state instead of handling an error.

Results are cached in-memory per `(league, home, away)` for 30 minutes, so
repeat requests for the same matchup don't re-hit the search API.

## Search provider: SerpApi

This service is implemented against **SerpApi's Google Search API**
(https://serpapi.com/search-api), chosen over the Bing Web Search API
because Microsoft has been retiring/restricting Bing Search API resource
creation on Azure, which makes it a risky long-term pick for a small side
project. SerpApi has:

- a plain REST/JSON response (no SDK required — this service just uses
  `httpx`),
- a free tier (100 searches/month at time of writing), plenty for
  checking a handful of NHL matchups,
- a single API key for auth.

### Getting a SerpApi key
1. Go to https://serpapi.com/ and sign up (free tier available).
2. From your dashboard, copy your **API Key**.
3. Set it as the `SEARCH_API_KEY` env var wherever you run this service.

`SEARCH_PROVIDER` defaults to `serpapi` (the only provider implemented
here). The code is structured so a different provider can be dropped in
later by implementing `SearchProvider` in `search.py` and registering it
in `get_provider()`.

## Running locally

```bash
cd server
pip install -r requirements.txt
SEARCH_API_KEY=your_serpapi_key uvicorn app:app --reload
```

Without a key, the service still starts fine — `/betting-sentiment` just
returns `available: false` for every request.

### Running the tests
```bash
cd server
pytest -q
```
The tests use FastAPI's `TestClient` and deliberately unset
`SEARCH_API_KEY`, so they need no real server or API key running.

## Deploying somewhere a phone can reach (Render.com free tier)

This dev sandbox can't host anything persistent, so deploy it to a real
free-tier host to get a public HTTPS URL for the Android app.

1. Push this repo (or at least the `server/` directory) to GitHub.
2. Go to https://render.com and sign up / log in.
3. **New +** → **Web Service** → connect your GitHub repo.
4. Configure the service:
   - **Root Directory**: `server`
   - **Runtime**: Python 3
   - **Build Command**: `pip install -r requirements.txt`
   - **Start Command**: `uvicorn app:app --host 0.0.0.0 --port $PORT`
   - **Instance Type**: Free
5. Under **Environment**, add an environment variable:
   - `SEARCH_API_KEY` = `<your SerpApi key>`
   - (optional) `SEARCH_PROVIDER` = `serpapi`
6. Click **Create Web Service**. Render will build and deploy it.
7. Once live, Render gives you a public URL like
   `https://your-service-name.onrender.com`. That's the base URL to hand
   to the Android app, e.g.:
   `https://your-service-name.onrender.com/betting-sentiment?league=NHL&home=TOR&away=MTL`

Note: Render's free tier spins down after inactivity, so the first
request after idling can take ~30-60s while it wakes back up — the
Android client should use a reasonably generous timeout and treat a slow
first response as normal, not an error.

## Example

```bash
curl "http://localhost:8000/betting-sentiment?league=NHL&home=TOR&away=MTL"
```

With no `SEARCH_API_KEY` set:
```json
{"available": false, "reason": "SEARCH_API_KEY not configured — see README for setup"}
```

With a valid key configured and results found:
```json
{
  "available": true,
  "league": "NHL",
  "home_team": "TOR",
  "away_team": "MTL",
  "summary": "Betting chatter: Maple Leafs have opened as -145 favorites at home with slight line movement toward Toronto since Monday... Injury/goalie buzz: Montreal's starting goalie is questionable for Tuesday's game...",
  "sources": [
    {
      "title": "Maple Leafs vs Canadiens Odds & Picks",
      "url": "https://example-sportsbook.com/nhl/tor-mtl",
      "snippet": "Maple Leafs have opened as -145 favorites at home..."
    }
  ],
  "fetched_at": "2026-09-16T12:00:00Z"
}
```
