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

# 4. Up. The first run builds both images and takes several minutes.
docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d --build

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

## Stopping, removing, and moving to another instance

### Know which command destroys the database

The whole database lives in a Docker named volume. Four commands look alike
and differ enormously:

| Command | Containers | Volumes — your data |
|---|---|---|
| `docker compose stop` | stopped, still exist | untouched |
| `docker compose down` | removed | **untouched** |
| `docker compose down -v` | removed | **deleted — everything gone** |
| `rm -rf /var/lib/docker` | gone | **deleted — everything gone** |

`down` is safe and is what you want for a restart or a rebuild. The `-v` is
the one that cannot be undone. Note that purging the Docker packages does
*not* remove `/var/lib/docker`; deleting that directory is a separate,
deliberate act.

### Always take a dump off the box first

Before stopping anything you intend to remove, and before deleting an
instance, get the data somewhere else. This takes seconds and is the
difference between a migration and an incident.

```bash
cd ~/AttendancePro && . ./.env
docker compose exec -T mysql   mysqldump -u root -p"$MYSQL_ROOT_PASSWORD" --single-transaction     --routines --triggers "$DB_NAME" | gzip > ~/attendance-final.sql.gz

# Then copy it, and .env, to your own machine — not to the same instance.
# From your laptop:
#   scp -i LightsailKey.pem ubuntu@<ip>:~/attendance-final.sql.gz .
#   scp -i LightsailKey.pem ubuntu@<ip>:~/AttendancePro/.env ./env-backup
```

`.env` matters as much as the dump: without `MYSQL_ROOT_PASSWORD` the dump
restores fine, but without `JWT_SECRET` every existing session is invalidated
the moment you start elsewhere.

### Stopping without removing

```bash
cd ~/AttendancePro
docker compose stop          # pause; `docker compose start` resumes
docker compose down          # remove containers, keep the data volume
docker compose up -d         # back up again, same data
```

Stopping the Lightsail instance itself also works and costs less — but only
if a static IP is attached, or the public IP changes and DNS stops matching.

### Removing Docker entirely from the instance

Do the dump first. Then:

```bash
cd ~/AttendancePro && docker compose down
sudo apt purge -y docker-ce docker-ce-cli containerd.io                   docker-buildx-plugin docker-compose-plugin
sudo apt autoremove -y

# This is the irreversible step — every volume, image and container.
sudo rm -rf /var/lib/docker /var/lib/containerd /etc/docker
sudo rm -f /etc/apt/sources.list.d/docker.list /etc/apt/keyrings/docker.asc
```

### Moving to another instance

Two routes. Pick by whether you want the same box elsewhere or a clean one.

**Snapshot, if you are staying inside Lightsail.** Instance → Snapshots →
Create snapshot, then "Create new instance from snapshot". Everything comes
with it — Docker, volumes, certificates, cron. Attach the static IP to the
new instance (detach from the old one first; an IP can only be on one), and
nothing else changes. Simplest, and the right answer for resizing.

**Dump and restore, for a different host or a clean start.** On the new
machine, follow *First deploy* above as far as filling in `.env` — reuse the
same `JWT_SECRET` and database passwords from the old one — then:

```bash
# Bring up only the database, so Flyway has not run yet.
docker compose up -d mysql
sleep 20

gunzip -c attendance-final.sql.gz | docker compose exec -T mysql   mysql -u root -p"$MYSQL_ROOT_PASSWORD" "$DB_NAME"

# Now the rest. Flyway sees a schema already at its current version and
# applies nothing, which is the intended outcome.
docker compose up -d --build
```

Restoring into a database Flyway has *already* migrated fails on existing
tables, which is why the restore goes in before the backend's first start.

**Then move DNS.** Lower the record's TTL to 300 seconds a day beforehand, so
the switch takes minutes rather than hours. Point the A record at the new IP.
Caddy on the new instance requests its own certificate on first start — the
old one does not transfer, and does not need to.

Keep the old instance stopped, not deleted, until people have used the new
one for a day. A stopped instance with a snapshot costs almost nothing and is
the only cheap way to undo a bad migration.

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
