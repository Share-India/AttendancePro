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

Point the hostname at the static IP **before** asking for a certificate:

```
attendance.shareindiainsurance.com.   A   <static IP>
```

Whichever issues it — certbot on the host or Caddy in the stack — the ACME
challenge arrives over port 80 at that name, and no certificate exists for a
bare IP address. If DNS has not propagated the request fails, and Let's
Encrypt rate-limits to five failures per hostname per hour.

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

Flyway applies every migration against the empty database on first start.

Step 4 starts no TLS edge: on an instance that already serves HTTPS, the
existing proxy fronts this stack — see **Sharing the instance** below for the
virtual host and certbot. Where nothing else owns 80 and 443, add
`--profile edge-tls` to that command and Caddy handles TLS itself.

### The first start will refuse to serve

Expect it. Flyway migrates the database, then the startup gate stops the
application because the seeded `EMP0001` account still has the password
published in `V6` — and the repository is public. The container restarts in
a loop until that is dealt with.

It cannot be dealt with through the application, because the application is
not running. The full sequence is in
[`06-deployment.md`](06-deployment.md#the-bootstrap-account); the short form
is: deactivate the account in the database, restart the backend, then give
it a real password and create a named administrator.

Do all of it before anyone else is told the URL.

## Startup on a shared instance

Spring Boot startup is almost entirely class loading and JIT, so it is bound
by CPU rather than memory. On an instance whose vCPUs are already carrying
other containers, that shows up as a long boot: measured at **190 seconds**
on two vCPUs alongside a dozen other containers, against three to five on an
idle machine.

Nothing is wrong when that happens. What to expect:

- `docker compose ps` reports **`Up (unhealthy)`** while it boots. The
  healthcheck is given a 300-second start window in the production overlay
  for exactly this reason; the image's own 45 seconds is sized for a machine
  that is not sharing.
- Requests through the proxy answer **502** until it finishes, because the
  container's nginx has nothing upstream yet.

Confirm rather than guess:

```bash
docker compose ... logs backend | grep "Started AttendanceApplication"
```

Memory is the resource that is usually short, and it is worth checking it is
not this one: `docker stats --no-stream`. The backend settles around 400 MB
against its 1.5 GB ceiling and MySQL around 500 MB against 1.2 GB, so a box
with free memory and a slow boot is short of CPU, not RAM, and lowering the
memory ceilings would make it worse rather than better.

If the boot time becomes a problem — it is paid once per deploy, not per
request — the answers are more vCPUs or fewer neighbours, not tuning.

## Sharing the instance with another application

Nothing in this stack publishes a host port except Caddy, and Caddy is behind
a compose profile so it does not start unless asked. On an instance that
already serves HTTPS, leave it off and let the existing proxy front this too.

Find out what holds the ports:

```bash
sudo ss -tlnp '( sport = :80 or sport = :443 )'
```

### Behind the host's own nginx

This is the default: run the stack with no profile and nothing contends for
80 or 443. The SPA is published on `127.0.0.1:8081`, which only the box can
reach.

```bash
docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d --build
```

Install the virtual host, which touches no other server block — nginx selects
between them on `server_name` alone, so the application already on this box
is unaffected:

```bash
sudo cp deploy/nginx-host-vhost.conf /etc/nginx/sites-available/attendance
sudo ln -s /etc/nginx/sites-available/attendance /etc/nginx/sites-enabled/
sudo nginx -t && sudo systemctl reload nginx
```

Then let certbot add TLS to that file:

```bash
sudo certbot --nginx -d attendance.shareindiainsurance.com
```

certbot rewrites the block to listen on 443 with the certificate and adds a
redirect from 80. Let it: hand-writing that skips the renewal timer, which is
the part that matters in ninety days.

**The header that has to survive all three hops.** TLS is terminated by the
host's nginx, so that is the only hop that knows the request was encrypted.
It sets `X-Forwarded-Proto`; the container's nginx forwards what arrives
rather than overwriting it with its own plain-HTTP scheme; the backend reads
it. Change any one of those and the application believes it is serving over
plain HTTP.

### When the hostname is proxied through Cloudflare

If the DNS record is orange-clouded, `certbot --nginx` fails with a 403 and a
`Cf-Mitigated: challenge` header. Cloudflare answers the ACME challenge with
a bot-protection page; the request never reaches this instance, so nothing
configured here can fix it. Confirm with:

```bash
curl -sI http://attendance.shareindiainsurance.com/.well-known/acme-challenge/test | grep -i server
```

`Server: cloudflare` means the proxy is in the way.

Two ways through, and they end up in genuinely different places.

**Turn the proxy off for this hostname.** In Cloudflare DNS, switch the
record from *Proxied* to *DNS only*. It then resolves to the instance,
`certbot --nginx` works unchanged, and Let's Encrypt renews itself. You give
up Cloudflare's DDoS protection and origin-IP hiding for this name.

**Keep the proxy and use a Cloudflare Origin Certificate.** Cloudflare
already terminates TLS for visitors; what the origin needs is a certificate
Cloudflare will accept. Generate one under SSL/TLS → Origin Server (free,
valid fifteen years, no ACME), install it, and set the SSL/TLS mode to
**Full (strict)**. Anything less than strict leaves the Cloudflare-to-origin
hop unverified, which is the hop most people forget.

```nginx
# In the vhost, in place of what certbot would have written:
listen 443 ssl;
ssl_certificate     /etc/ssl/cloudflare/attendance.pem;
ssl_certificate_key /etc/ssl/cloudflare/attendance.key;
```

**If the proxy stays on, two things must be dealt with.**

*The challenge page.* A hostname returning `Just a moment...` with a 403 is
running an interactive JS challenge. A person in a browser passes it and gets
a clearance cookie, so the site looks fine; nothing programmatic does, which
is why ACME fails. The same applies to the SPA's own API calls — they are
XHR, with no browser navigation to challenge. Check what the API actually
returns before trusting it:

```bash
curl -s -o /dev/null -w '%{http_code}
' -X POST   https://attendance.shareindiainsurance.com/api/v1/auth/login   -H 'Content-Type: application/json' -d '{}'
```

`400` is the validator rejecting an empty body, which means the request
reached the application. `403` means Cloudflare answered instead. Turn the
challenge off for this hostname, or scope a rule to skip `/api/*`.

*Client addresses.* Behind any proxy, `$remote_addr` is the proxy. This
application records an IP against every punch event, every sign-in attempt
and every audit entry, so without the real one each of those columns fills
with a Cloudflare edge address. That is worse than an empty column, because
it looks like evidence. Install the trusted ranges and uncomment the include
in the vhost:

```bash
sudo deploy/cloudflare-real-ip.sh
sudo nginx -t && sudo systemctl reload nginx
```

### With this stack's own TLS


Only where nothing else owns 80 and 443:

```bash
docker compose -f docker-compose.yml -f docker-compose.prod.yml   --profile edge-tls up -d --build
```

Caddy then obtains and renews the certificate itself and the host needs no
nginx at all.

### The other options

**Move the other application behind Caddy** — add its hostname as a second
site block in `deploy/Caddyfile` and stop its proxy. One proxy instead of
two, but it means touching a running application.

**A second instance** — the right answer once either application matters
enough that restarting one must not disturb the other.

MySQL needs no decision either way: this stack's database is not published,
so it cannot collide with another MySQL on the box and the two never see
each other.

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
