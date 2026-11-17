# MineVest

AI-assisted Indian stock research foundation. This first build focuses on importing verified NSE CM Bhavcopy files and preserving daily OHLCV history. It does **not** generate buy/sell calls or promise returns.

## Requirements
- Java 21
- Internet on first backend build (Gradle downloads its pinned distribution and dependencies)
- Node.js 20+ and npm (frontend)

## Backend — IntelliJ IDEA
1. Open IntelliJ IDEA → **Open** → select this `MineVest` folder (the folder containing `settings.gradle`).
2. Select **Trust Project** if prompted.
3. Set Project SDK to Java 21: File → Project Structure → Project SDK.
4. Open the Gradle tool window and refresh. If IntelliJ asks for Gradle distribution, choose **Wrapper**.
5. In the IntelliJ terminal, run `./gradlew bootRun`.
   - Or use the run icon beside `MineVestApplication`.
6. Backend: http://localhost:8083
7. Health check: http://localhost:8083/api/health

The included `gradlew` bootstraps Gradle 8.14.3 on first run, so a global Gradle installation is not required. First run needs internet access.

## Frontend
Open a second terminal:
```bash
cd frontend
npm install
npm run dev
```
Open http://localhost:5173

## Import NSE Bhavcopy
In the dashboard header, choose **Import NSE data** and select the NSE ZIP archive or extracted CSV. The main page follows the earlier MineVest/MarketLens mockup: recommendations first, then saved data history. The Run Market Analysis control remains disabled until the actual research engine is implemented; saving a data snapshot is available separately and is clearly labelled. The importer accepts the CM UDiFF CSV format tested with:
`BhavCopy_NSE_CM_0_0_0_YYYYMMDD_F_0000.csv.zip`

You can also use curl:
```bash
curl -F "file=@/path/to/bhavcopy.zip" http://localhost:8083/api/market-data/import
```

## API
- `GET /api/health`
- `POST /api/market-data/import` — multipart field `file`, accepts `.zip` or `.csv`
- `GET /api/market-data/prices?symbol=RELIANCE`
- `GET /api/stocks`
- `POST /api/analysis/run` — saves an immutable **data-ingestion snapshot**, not a stock recommendation
- `GET /api/history`
- `GET /api/history/{runId}`

## Data and limitations
- Imported data is stored locally under `./data/` as JSON. It is not committed to Git.
- The importer preserves raw exchange series and instrument type. `EQ` alone is not treated as proof that a security is an ordinary operating-company share; ETFs and other instruments need security-master classification before recommendation research.
- Multiple imports are deduplicated by trading date + symbol + series + ISIN.
- No live price feed, news search, AI provider, financial-statement ingestion, or recommendation scoring is wired in this foundation.
- NSE access, retention, and reuse must comply with applicable NSE terms. A successful download does not itself grant redistribution rights.

## Next milestones
1. Add a reliable security master and exclude ETFs/debt/commodity instruments from the company-stock universe.
2. Expand historical price calculations and research views.
3. Connect free news/fundamental sources and an LLM provider only after validating their coverage and terms.
4. Implement transparent, evidence-backed candidate scoring and immutable recommendation snapshots.
5. Enable the on-demand Run Market Analysis action once the research pipeline is real and validated.
