# Kite API Options UI

Angular control console for the Spring Boot trading API.

## Screens

- Dashboard: scanner state, kill switch, routing, PnL, latest signal.
- Execution: start/stop scanner, mode, routing, kill switch, enabled underlyings.
- Config: documented `/config` response with parameter descriptions and current values.
- Monitoring: positions, orders, trades, PnL, recent signals.
- Reports: trade journal CSV download and entry-signal report archive.
- Kite Auth: Zerodha login URL generation and session/access-token validation flow.
- Backtests: single run, data downloads, suites, quick analysis, focused validation.
- Data Maintenance: append Zerodha option data into local datasets.

## Local Development

For normal local use, run the whole application from the repo root:

```powershell
.\run-local-app.ps1
```

Open:

```text
http://localhost:8080
```

This builds the Angular UI into Spring Boot static resources and starts the backend. Only one server is needed for daily use.

If the UI has not changed:

```powershell
.\run-local-app.ps1 -SkipUiBuild
```

## UI Development

Start the Spring Boot backend on port `8080`.

Then run the UI:

```powershell
cd ui
npm install
npm start
```

Open:

```text
http://localhost:4200
```

The dev server uses `proxy.conf.json` so browser calls like `/config` are forwarded to:

```text
http://localhost:8080/config
```

## Production Build

Build the Angular app:

```powershell
.\build-ui.ps1
```

The Angular build output is configured to write into:

```text
src/main/resources/static
```

After that, start the Spring Boot app and open:

```text
http://localhost:8080
```

## Zerodha Login Flow

Use the `Kite Auth` screen:

1. Click `Get Login URL`.
2. Open the returned Kite login URL.
3. Complete Zerodha login.
4. Zerodha redirects to `/auth/kite/callback`.
5. The backend exchanges the request token and stores the same-day access token locally.
6. Click `Start / Validate Session` to confirm the active session.

The UI never displays the raw Kite access token.

## Internet Access To Local UI

You can access the local UI from the internet by using a tunnel such as ngrok or Cloudflare Tunnel.

For development UI only:

```powershell
ngrok http 4200
```

For the normal single-host setup, build/serve the UI through Spring Boot:

```powershell
ngrok http 8080
```

This repo also includes a root `ngrok.yml` with one named tunnel:

```text
app -> http://localhost:8080
```

With the default config, the backend starts one tunnel to Spring Boot:

```powershell
ngrok http 8080
```

That single public host serves both the UI and Kite callback:

```text
https://your-tunnel.ngrok-free.app/
https://your-tunnel.ngrok-free.app/auth/kite/callback
```

Open ngrok's local inspector to see the generated public URLs:

```text
http://localhost:4040
```

For Zerodha login callbacks, configure the Kite developer redirect URL to the tunnel URL plus:

```text
/auth/kite/callback
```

Example:

```text
https://your-tunnel.ngrok-free.app/auth/kite/callback
```

Important: the current backend does not include application-user authentication. Do not expose this UI publicly unless access is protected by a tunnel access policy, VPN, firewall allowlist, or a backend authentication layer. The UI contains controls for starting/stopping scans, changing routing, and enabling live execution when backend configuration allows it.
