# Deploying on AWS Lightsail

One Lightsail instance running the whole stack under Docker Compose: MySQL, the
Spring Boot API, the nginx that serves the SPA, and Caddy terminating TLS in
front of them.

[`06-deployment.md`](06-deployment.md) covers what the application itself needs
— the startup gate, the bootstrap account, backups, upgrades. This document is
only the Lightsail-specific part. Read both before the first deploy.

## Why one instance

For a few dozen employees the load is trivial and the failure modes are almost
all operational rather than capacity. A single box with snapshots is easier to
reason about, cheaper, and recovers faster than a split topology nobody
practises restoring.

A Lightsail **managed database** is the one upgrade worth considering: it moves
backups, patching and point-in-time restore off your plate for about the price
of a second instance. The compose file takes `DB_HOST`, so switching later is a
change of three environment values and a dump-and-load, not a rewrite.

## Instance

| | |
|---|---|
| Blueprint | Ubuntu 24.04 LTS — OS only, not a prepackaged app |
| Plan | **4 GB RAM / 2 vCPU** |
| Storage | 80 GB SSD (comes with the plan) |
| Networking | Attach a **static IP**. The default public IP changes on stop/start, and the certificate is bound to a hostname that must keep resolving |

4 GB is the realistic floor, not a luxury. MySQL takes a 512 MB buffer pool,
the JVM is capped at 1.6 GB, and a 2 GB instance leaves the build itself with
nowhere to go — `mvn package` inside the backend image is the heaviest thing
that ever runs here. If the budget demands 2 GB, build the images elsewhere and
pull them rather than building on the box.

### Firewall

In **Networking → IPv4 Firewall**, allow only:

| Application | Port |
|---|---|
| SSH | 22 |
| HTTP | 80 |
| HTTPS | 443 |

Delete anything else. In particular never open **3306** — the database is bound
to loopback and nothing outside the box should reach it.

Restrict SSH to your own address if your office IP is stable.

## DNS

Point the hostname at the static IP **before** the first start:

```
attendance.shareindia.co.in.   A   <static IP>
```

Caddy requests the certificate on startup and cannot get one for a bare IP
address. If DNS has not propagated the first boot fails the ACME challenge,
and Let's Encrypt rate-limits to five failures per hostname per hour.

## First deploy

```bash
# 1. Docker, from Docker's own repository — Ubuntu's packaged version is old
#    enough to miss compose v2 features.
curl -fsSL https://get.docker.com | sudo sh
sudo usermod -aG docker ubuntu && newgrp docker

# 2. The code.
git clone https://github.com/Share-India/AttendancePro.git
cd AttendancePro

# 3. Configuration. Fill in every blank; the app refuses to start otherwise.
cp .env.production.example .env
openssl rand -base64 64        # paste as JWT_SECRET
nano .env

# 4. Up. Pick ONE edge overlay. The first run builds both images and takes
#    several minutes.
#
#    Instance is ours alone — Caddy takes 80/443 and handles TLS:
docker compose -f docker-compose.yml -f docker-compose.prod.yml                -f docker-compose.caddy.yml up -d --build
#
#    Something else already owns 80/443 — see "Sharing the instance" below:
#    ... -f docker-compose.behind-proxy.yml up -d --build

# 5. Watch the gate. A refusal names exactly what is wrong.
docker compose logs -f backend
```

Flyway applies `V1`–`V20` against the empty database on first start.

### Immediately afterwards

The bootstrap account is the one thing standing open. `V6` seeds `EMP0001`
with a password published in the migration, and the repository is public.

1. Sign in as `EMP0001` and set a real password.
2. Create a named administrator for whoever actually runs the system.
3. Deactivate `EMP0001`.

Do this before anyone else is told the URL.

## Sharing the instance with another application

Nothing in this stack publishes a host port except Caddy, on 80 and 443. If
something else on the instance already serves HTTP there, Caddy cannot start
and neither can the deploy.

Find out what holds them:

```bash
sudo ss -tlnp '( sport = :80 or sport = :443 or sport = :3306 )'
```

There are three honest ways out, in increasing order of effort:

1. **Let the existing reverse proxy front this too** — see below. Least
   disruptive: the other application's configuration is never touched.
2. **Let Caddy front both.** Add the other application's hostname to
   `deploy/Caddyfile` as a second site block and stop its proxy. Caddy then
   handles certificates for both, which is less to maintain than two proxies.
3. **A second Lightsail instance.** Cleanest separation, and the right answer
   once either application matters enough that a restart of one must not
   touch the other.

MySQL needs no decision: this stack's database is not published, so it cannot
collide with another MySQL on the box, and the two never see each other.

### Behind an existing host nginx

Swap the Caddy overlay for the behind-proxy one. Caddy never starts, so it
never asks for the ports that are taken.

```bash
export COMPOSE_FILE=docker-compose.yml:docker-compose.prod.yml:docker-compose.behind-proxy.yml
docker compose up -d --build
```

That publishes the SPA container on `127.0.0.1:8091` — nothing on the public
interface. Confirm it answers before touching nginx:

```bash
curl -I http://127.0.0.1:8091          # 200 from the SPA
```

Then add the virtual host and let certbot put TLS on it:

```bash
sudo cp deploy/nginx-attendance.conf /etc/nginx/sites-available/attendance
sudo ln -s /etc/nginx/sites-available/attendance /etc/nginx/sites-enabled/
sudo nginx -t && sudo systemctl reload nginx
sudo certbot --nginx -d attendance.shareindia.co.in
```

`nginx -t` before the reload is the whole safety margin here: a bad config
file would otherwise take the other application down with it on reload.

certbot rewrites the vhost in place, adding `listen 443 ssl`, the certificate
paths and a redirect from port 80, and installs its own renewal timer. The
file then no longer matches the copy in this repository, which is expected.

**The scheme has to survive three hops.** Host nginx terminates TLS and is
the only hop that knows the request was HTTPS, so it sets
`X-Forwarded-Proto`. The container's nginx passes through whatever arrives
rather than overwriting it with its own `$scheme`, and Spring Boot reads it
from there. Drop that header anywhere in the chain and the application
believes every request came in over plain HTTP.

## Operations

### Deploying a change

```bash
cd AttendancePro && git pull
docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d --build
```

Compose recreates only what changed. Flyway applies any new migrations on
start — and refuses to start if one fails, rather than serving against a
half-migrated schema.

### Backups

Two layers, because they fail differently.

**Lightsail automatic snapshots** (Instance → Snapshots) cover the whole disk
and are the answer to "the instance is gone". They are not the answer to
"someone deleted a department last Tuesday", because restoring one is a whole
new instance.

**A nightly dump** covers that:

```bash
sudo tee /etc/cron.daily/attendance-backup >/dev/null <<'SH'
#!/bin/sh
set -eu
cd /home/ubuntu/AttendancePro
. ./.env
OUT=/home/ubuntu/backups
mkdir -p "$OUT"
docker compose exec -T mysql \
  mysqldump -u root -p"$MYSQL_ROOT_PASSWORD" --single-transaction \
    --routines --triggers "$DB_NAME" | gzip > "$OUT/db-$(date +%F).sql.gz"
find "$OUT" -name 'db-*.sql.gz' -mtime +14 -delete
SH
sudo chmod +x /etc/cron.daily/attendance-backup
```

`--single-transaction` so the dump is consistent without locking anyone out
mid-punch. Fourteen days retained; the snapshots cover anything older.

**Test a restore before you need one.** A backup nobody has restored is a
hypothesis.

### Logs

```bash
docker compose logs -f backend          # application
docker compose logs -f caddy            # TLS and certificate renewal
docker compose exec backend wget -qO- localhost:8080/actuator/health
```

Rotation is configured at 10 MB × 3 files per service. Without it the default
driver grows until the disk fills, and the first symptom is MySQL failing to
write rather than anything mentioning logs.

### Certificate renewal

Automatic, roughly 30 days before expiry. Nothing to schedule. The two ways it
breaks are DNS moving away from the instance and port 80 being closed — Caddy
needs it for the renewal challenge even though all traffic is on 443.

## What this does not give you

Stated plainly, because each is a decision rather than an oversight:

- **No high availability.** One instance. A reboot is a few minutes of
  downtime, a lost instance is a snapshot restore.
- **No CDN.** The SPA is served from the instance. Fine for staff on one
  continent; add CloudFront if that stops being true.
- **No log aggregation.** Logs live on the box and rotate away.
- **No staging environment.** A second instance from the same compose file
  with its own hostname is the cheapest way to get one, if you want it before
  changes meet real attendance data.
