# DssMeet — Production Deployment Runbook

Target: **Hostinger KVM2 (Ubuntu 24.04, India)** + **Cloudflare Pages** (family-web).
Stack: `postgres` · `coturn` · `signaling` · `backend` · `caddy` (Docker Compose).
Not deployed on VPS: `family-web` (Pages), `warden-dashboard` / `vendor-dashboard` (future desktop apps).

Replace `example.com` with YOUR domain everywhere.

---

## 1. Prerequisites (one-time accounts)

| What | Where | Why |
|---|---|---|
| Domain | GoDaddy (already bought) | DNS |
| KVM2 VPS | Hostinger (already bought, India DC) | compute |
| Cloudflare account (free) | cloudflare.com | Pages + DNS |
| GitHub repo | github.com/coder-abhishektiwari/DssMeet (public) | code source |
| Razorpay (optional) | dashboard.razorpay.com | wallet payments |
| Fast2SMS | fast2sms.com | OTP SMS (can wait — launch with `SMS_PROVIDER=log`) |
| UptimeRobot (free) | uptimerobot.com | uptime alerts |

---

## 2. VPS bootstrap

**2.1** Hostinger hPanel → **VPS → Manage → Settings → OS → Ubuntu 24.04 LTS** (fresh install if not done).

**2.2** hPanel → VPS → **Settings → SSH → Generate SSH key** (or add yours). Note the IP (this is `VPS_PUBLIC_IP`).

**2.3** hPanel → **Advanced → Terminal** (or SSH from your machine) as root:

```bash
apt-get update -y && apt-get install -y git curl
git clone https://github.com/coder-abhishektiwari/DssMeet.git /opt/DssMeet
bash /opt/DssMeet/deploy/setup-vps.sh
```

This installs Docker, ufw (22/80/443/3478/41000-41040), fail2ban, log rotation, sysctl hardening.

**2.4 (optional, recommended)** Restrict SSH to your IP after confirming key login works:

```bash
ufw delete allow 22/tcp
ufw allow from YOUR.IP.HERE to any port 22 proto tcp
```

---

## 3. Configure `.env`

```bash
cd /opt/DssMeet
cp .env.production.example .env
chmod 600 .env
nano .env    # or vim
```

Fill **every** value. Secret generation:

```bash
openssl rand -hex 32    # JWT_SECRET
openssl rand -hex 32    # MEDIA_API_KEY
openssl rand -hex 16    # POSTGRES_PASSWORD  (HEX ONLY — no @ : /)
openssl rand -hex 16    # TURN_PASS           (HEX ONLY)
```

Checklist of must-change lines:

- [ ] `VPS_PUBLIC_IP` = KVM2 IP from hPanel
- [ ] `JWT_SECRET`, `MEDIA_API_KEY`, `POSTGRES_PASSWORD`, `TURN_PASS`
- [ ] `TURN_SERVERS` JSON → replace `turn.example.com` + `CHANGE_ME` (credential = your `TURN_PASS`, username = your `TURN_USER`)
- [ ] `API_HOST` / `SIGNAL_HOST` / `TURN_HOST` = your domain
- [ ] `FAMILY_WEB_URL` = `https://example.com` (Pages apex)
- [ ] `CORS_ORIGIN` = `https://example.com`
- [ ] `SIGNALING_PUBLIC_URL` = `https://signal.example.com`
- [ ] `SMS_PROVIDER=log` for now · Razorpay keys empty for now

---

## 4. DNS records

Two options — **A is simpler**:

**A. Keep DNS at your current panel (Hostinger/GoDaddy):**

| Type | Name | Value | Proxy |
|---|---|---|---|
| A | `api` | `VPS_PUBLIC_IP` | DNS only |
| A | `signal` | `VPS_PUBLIC_IP` | DNS only |
| A | `turn` | `VPS_PUBLIC_IP` | DNS only |
| A / CNAME | `@` (apex) | shown by **Cloudflare Pages → Custom domains** in step 6 | DNS only |

**B. Move nameservers to Cloudflare** (recommended long-term): add site in Cloudflare → it gives 2 nameservers → update them at your registrar → then Pages + all records live in one dashboard (apex auto-configures; proxy ON for apex, OFF for `turn` — TURN is UDP, CF can't proxy it).

---

## 5. Start the stack

```bash
cd /opt/DssMeet
docker compose -f docker-compose.prod.yml up -d --build
```

First run pulls images + builds backend/signaling (~3-5 min). Then:

```bash
docker compose -f docker-compose.prod.yml ps        # all services running/healthy
docker compose -f docker-compose.prod.yml logs backend | head -40
```

Backend boot must show migration success (migrations auto-run, `lib/migrate.js`).

**Smoke test (server side):**

```bash
curl -s http://localhost:3001/health          # backend
curl -s http://localhost:3002/api/health      # signaling
# After DNS + cert issue (give Caddy ~1 min):
curl -s https://api.example.com/health
curl -s https://signal.example.com/api/health
```

---

## 6. Cloudflare Pages (family-web)

1. cloudflare.com → **Workers & Pages → Create → Pages → Connect to Git** → pick `DssMeetPro`.
2. Settings → **Build & deployments**:
   - Framework preset: **Vite**
   - Root directory: **`family-web`**
   - Build command: `npm run build`
   - Build output directory: `dist`
3. Settings → **Environment variables** (Production **and** Preview):

   | Key | Value |
   |---|---|
   | `VITE_API_GATEWAY_URL` | `https://api.example.com` |
   | `VITE_SIGNALING_URL` | `https://signal.example.com` |
   | `VITE_WEBRTC_ICE_SERVERS` | *(exact same JSON as `TURN_SERVERS` in .env)* |

4. **Custom domains** → add `example.com` (apex) → Pages shows the DNS record to create → add it in your DNS panel (step 4) → wait for "Active".
5. Open `https://example.com` → login page loads → DevTools network: API calls hit `https://api.example.com` with 200s.

---

## 7. Razorpay webhook (payments)

Razorpay Dashboard → **Settings → Webhooks → Add**:

- URL: `https://api.example.com/api/webhooks/razorpay`
- Secret: your `RAZORPAY_WEBHOOK_SECRET` (same as .env)
- Events: `payment.captured`, `payment.failed`

Then fill `RAZORPAY_KEY_ID` / `RAZORPAY_KEY_SECRET` / `RAZORPAY_WEBHOOK_SECRET` in `.env` and:

```bash
docker compose -f docker-compose.prod.yml up -d backend
```

---

## 8. Go live with SMS (before real users)

1. Fast2SMS → DLT templates approved (OTP / link).
2. `.env`: `SMS_PROVIDER=fast2sms`, fill all `FAST2SMS_*`, set `SMS_OTP_DOMAIN=example.com` (must equal the Pages origin — WebOTP depends on it).
3. `docker compose -f docker-compose.prod.yml up -d backend`
4. Test: family login → real OTP arrives on the phone.

---

## 9. Nightly backups

```bash
crontab -e
```

Add:

```
0 3 * * * /opt/DssMeet/deploy/backup.sh >> /var/log/pc-backup.log 2>&1
```

Layer 2 (automatic): hPanel → VPS → **Backups** — weekly off-server backup is included free. Verify it's enabled.

Test a dump manually: `bash /opt/DssMeet/deploy/backup.sh`

---

## 10. Restore drill (once, so you know it works)

```bash
docker exec -i dssmeet-postgres pg_restore -U dssmeet \
  -d dssmeet --clean --if-exists < /var/backups/dssmeet/pc-<DATE>.dump
```

---

## 11. Monitoring

UptimeRobot (free) → 3 monitors, 5-min interval:

| URL | Expect |
|---|---|
| `https://api.example.com/health` | `{"status":"ok"...}` |
| `https://signal.example.com/api/health` | `{"status":"ok"}` |
| `https://example.com/` | 200 |

---

## 12. Decommission Render (SECURITY — do this)

Old Render services still run with old secrets: backend, signaling, familyweb, superadmin, warden.

1. render.com dashboard → each service → **Delete**.
2. This repo's `.github/workflows/keep-alive.yml` is already schedule-disabled (manual-only).
3. DigitalOcean: no active resources needed (keep the $5 credit for a future managed DB).

---

## 13. Android kiosk APK rebuild

`android-kiosk/local.properties` (gitignored, local only):

```properties
API_BASE_URL=https://api.example.com
SIGNALING_URL=https://signal.example.com
```

- TURN credentials are **not** baked in — the join-ACK from signaling delivers them (prod `TURN_SERVERS`).
- Old Render/metered lines: remove or leave (overridden at runtime), never ship them.
- Build: Android Studio → Build → Generate Signed APK. Install on kiosk device.

### Dashboard auto logout (inactivity) — server-stored setting
The warden dashboard can sign itself out after an idle period. The VALUE is
stored on the server; the countdown runs locally in the browser (no polling,
no server round trip):

```
PATCH /settings/auto-logout   { "enabled": true, "timeoutMinutes": 5 }
```

- Allowed timeouts: **2, 5 or 10 minutes** (anything else → 400).
- Roles: warden / admin / vendor / super-admin. A kiosk device token → 403.
- Set it from the dashboard: **Configurations → Auto Logout (Dashboard Inactivity)**.
- Default: **off** (the dashboard stays signed in, as it always did).
- The **inmate kiosk is not affected** by this setting — the kiosk app has its
  own fixed 2-minute inactivity logout (`AUTO_LOGOUT_TIMEOUT_MS` in
  `android-kiosk/local.properties`), entirely local to the device.

---

## 13b. Builds & app updates (manual, on demand)

### Building (GitHub → Actions → Run workflow)

| Workflow | What it builds | Notes |
|---|---|---|
| **Android Kiosk APK** | signed release APK | inputs: `version_code` (MUST be higher than the installed one), `version_name`, `publish_release` |
| **Warden Desktop Builds** | Windows `.exe` + Linux `AppImage`/`.deb` | input: `publish_release` |

Both are **manual-only** — nothing is built on push. Tick `publish_release`
to also create a GitHub Release; its asset URL + sha256 are what you publish
in the dashboard.

The APK is signed with the release keystore stored in the repo SECRETS
(`DSM_KEYSTORE_BASE64`, `DSM_KEYSTORE_PASSWORD`, `DSM_KEY_ALIAS`,
`DSM_KEY_PASSWORD`). Without that secret the build produces an **unsigned**
APK (fresh installs only — it can never update an installed one).

### Warden desktop: electron-updater (real auto-update)

The installed desktop app updates itself through **electron-updater**:

- Feed: the GitHub Releases page of this repo (`electron-builder.yml` →
  `publish.provider: github`). The repo is public, so no token is needed.
- The release **tag must be `v<version>`** and the version must match
  `warden-dashboard/package.json` — **bump the version in package.json before
  building**, otherwise the updater has nothing newer to offer.
- The release MUST contain `latest.yml` (Windows) / `latest-linux.yml`
  (Linux) plus the `*.blockmap` files — the CI uploads them. Without
  `latest.yml` the updater cannot find the new build.
- Flow in the app: check on start + every 15 min → banner (optional) or a
  blocking gate (mandatory) → download (progress shown) → **Restart &
  install** → the app restarts into the new version.
- In a browser tab (not the installed app) there is no updater — the UI
  falls back to the release registry and opens the download URL.

### Publishing an update

**The version is written in ONE place — the build.** The workflow publishes
the release to the registry itself; you never type the version twice.

**Kiosk APK:**
1. Actions → **Android Kiosk APK** → **Run workflow**
2. `version_code` = the number the devices will compare against (must be
   HIGHER than the installed one), `version_name` = `1.1.0`
3. Tick `publish_release` → the workflow creates the GitHub Release **and**
   publishes the registry entry (version, versionCode, sha256, download URL)
4. Tick `mandatory` if kiosks must be blocked until they update

**Warden desktop:**
1. Bump the version in `warden-dashboard/package.json` (e.g. 1.0.0 → 1.1.0)
2. Actions → **Warden Desktop Builds** → **Run workflow** → tick
   `publish_release` (+ `mandatory` if the console must be blocked)
3. The workflow tags the release `v1.1.0` and publishes the registry entry

Both need the repo secret `RELEASE_PUBLISH_TOKEN` (same value as the
backend env `RELEASE_PUBLISH_TOKEN`). Without it the build still creates the
GitHub Release, but no registry entry — so the *mandatory* flag and the
browser fallback would not work.

The dashboard's **Updates** page stays available for manual publishing and
for flipping an existing release to mandatory.

### How the clients learn about it

- **Kiosk**: the manifest rides on the heartbeat it already sends every
  30s → **no extra request**. The app compares the registry's `versionCode`
  with its own `BuildConfig.VERSION_CODE`:
  - higher + mandatory → the screen is blocked with "UPDATE REQUIRED", the
    APK is downloaded, its sha256 verified and installed **silently**
    (device-owner kiosk), then the app restarts
  - higher + optional → a dismissible banner on the dashboard
  - equal/lower → nothing happens
- **Warden console**: electron-updater checks the GitHub feed on start and
  every 15 minutes. A mandatory release shows a blocking overlay; an
  optional one a dismissible banner. The installer is downloaded, verified
  and installed on restart.

### Release registry (server side)

```
GET  /app-releases/latest?app=kiosk|warden&platform=android|windows|linux   (no auth)
GET  /app-releases                                                           (warden/admin)
POST /app-releases                                                           (warden/admin)
PATCH /app-releases/:releaseId                                               (mandatory toggle)
```

Publishing a new build supersedes the previous one for that app + platform.
The registry stores METADATA ONLY — the binaries live in GitHub Releases.

---

## 14. End-to-end call test (the real acceptance test)

1. **TURN check:** open <https://webrtc.github.io/samples/src/content/peerconnection/trickle-ice/> → add server `turn:turn.example.com:3478`, username/password = `TURN_USER`/`TURN_PASS` → **Add Server** → gather candidates → you must see type **relay** rows.
2. Two-browser call: family-web login (OTP via log/SMS) → start/join call from two profiles → video+audio flows; in `chrome://webrtc-internals` a relay candidate appears only when P2P fails (normal = srflx/host, relay = fallback — both must be *possible*).
3. Kiosk: same call from installed APK.
4. Wallet (if Razorpay live): post-call popup shows balance + Add money → test ₹1 payment → webhook credits balance.

---

## 15. Day-2 operations

**Auto-deploy (default path):** pushes to `main` that touch `backend/`,
`signaling-server/`, `docker-compose.prod.yml`, `Caddyfile` or `deploy/` trigger
`.github/workflows/deploy.yml` → SSH into the VPS → `deploy/deploy.sh`:

1. remembers the currently-running commit,
2. resets to the pushed SHA and `docker compose up -d --build`,
3. polls backend + signaling health **inside their containers** (no host ports),
4. healthy → done; unhealthy → **auto-rolls back** to the previous commit,
   rebuilds, and marks the workflow run **failed** so you get the alert.

One-time setup — repo **Settings → Secrets and variables → Actions**:

| Secret | Value |
|---|---|
| `VPS_HOST` | KVM2 public IP |
| `VPS_USER` | `root` (or your deploy user) |
| `VPS_SSH_KEY` | private key; public half must be in VPS `~/.ssh/authorized_keys` |

VPS-side `.env` is gitignored and never touched by deploys.

Manual fallback (also how you deploy if Actions is down):

```bash
# logs
docker compose -f docker-compose.prod.yml logs -f backend
# restart after .env change
docker compose -f docker-compose.prod.yml up -d
# manual deploy (same script CI runs)
bash /opt/DssMeet/deploy/deploy.sh /opt/DssMeet
# update code (script-less fallback)
git pull && docker compose -f docker-compose.prod.yml up -d --build
# disk check
df -h && docker system df
```

Rollback: `git checkout <last-good-commit> && docker compose -f docker-compose.prod.yml up -d --build`. DB is append-only via migrations — restore from `/var/backups/dssmeet/` only if a bad migration ran.

---

## 16. Local config cleanup (after Render is gone AND the first VPS deploy works)

The old tunnel/Render-era local files were removed (start.bat, cloudflared,
playit scripts, PUBLIC_URLS.md). Two `.env` files still carry dead values —
clean them once the VPS is live:

- **Root `.env`** (keep the file — dev `docker-compose.yml` needs it):
  - Remove `PLAYIT_HOSTNAME` (playit tunnel era).
  - Replace `SIGNALING_PUBLIC_URL=https://...trycloudflare.com` with
    `https://signal.example.com` (or remove — dev compose has a default).
  - Replace `FAMILY_WEB_URL=http://1.187.x.x:5173` with
    `http://127.0.0.1:5173`.
- **`family-web/.env`** (local vite dev):
  - `VITE_API_GATEWAY_URL` → `https://api.example.com` (or `http://127.0.0.1:59354` for pure-local dev).
  - `VITE_SIGNALING_URL` → `https://signal.example.com` (or `http://127.0.0.1:3002`).
  - `VITE_WEBRTC_ICE_SERVERS` → same JSON as `TURN_SERVERS` in `.env`.

---

## Architecture recap

```
Browser → Cloudflare Pages (apex) ──fetch──► https://api.domain   → Caddy → backend:3001
Kiosk   ───────────────────────────────────► https://signal.domain → Caddy → signaling:3002 (WSS)
WebRTC  P2P (host/srflx) → fallback TURN coturn @ turn.domain:3478 + relay 41000-41040/udp

KVM2: ufw + fail2ban · mem limits (2g pg / 512m api / 256m signal / 128m turn+caddy)
      nightly pg_dump (14d) + Hostinger weekly offsite · UptimeRobot
```
