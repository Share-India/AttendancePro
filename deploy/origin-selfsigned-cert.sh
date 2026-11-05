#!/bin/sh
# Generates a long-lived self-signed certificate for the origin.
#
#   sudo deploy/origin-selfsigned-cert.sh attendance.shareindiainsurance.com
#
# Why this exists: behind Cloudflare, the hop from their edge to this server
# crosses the public internet. In "Flexible" mode that hop is plain HTTP, so
# a visitor sees a padlock while their password travels unencrypted for the
# second half of the journey.
#
# Encrypting that hop needs a certificate here, and nothing more — Cloudflare
# in "Full" mode accepts any certificate, including this one, because it is
# checking that the connection is encrypted rather than who the origin is.
# You do not need Cloudflare access to create this.
#
# "Full (strict)" also verifies the certificate, and for that it has to be a
# Cloudflare Origin CA certificate or a publicly trusted one. Strict is
# better and is the right end state. This is what you can do without waiting
# for anybody.
#
# SAFE TO RUN NOW. Adding a 443 listener does not stop port 80 answering, so
# Cloudflare in Flexible mode carries on working exactly as it does today.
# The certificate simply sits ready, and the Cloudflare mode change becomes a
# single toggle with nothing to coordinate.
#
# Do NOT use this on an origin that is reachable directly by browsers: a
# self-signed certificate is what a browser warns about. It is appropriate
# only behind a proxy that is doing the public-facing TLS.
set -eu

DOMAIN=${1:-}
if [ -z "$DOMAIN" ]; then
    echo "usage: $0 <hostname>" >&2
    exit 1
fi

DIR=/etc/ssl/attendance
KEY="$DIR/$DOMAIN.key"
CRT="$DIR/$DOMAIN.pem"

mkdir -p "$DIR"

# Ten years. A self-signed certificate behind a proxy gains nothing from
# rotation pressure, and an expiry nobody is watching is how an origin goes
# dark on a quiet Sunday.
openssl req -x509 -nodes -newkey rsa:2048 \
    -days 3650 \
    -keyout "$KEY" \
    -out "$CRT" \
    -subj "/CN=$DOMAIN/O=Share India" \
    -addext "subjectAltName=DNS:$DOMAIN" \
    2>/dev/null

chmod 600 "$KEY"
chmod 644 "$CRT"

echo "Wrote:"
echo "  certificate  $CRT"
echo "  private key  $KEY"
echo
echo "Next, in the vhost, uncomment the TLS block and point it at these:"
echo "  ssl_certificate     $CRT;"
echo "  ssl_certificate_key $KEY;"
echo
echo "Then: nginx -t && systemctl reload nginx"
echo
echo "Finally ask whoever runs Cloudflare to set SSL/TLS to Full. Until they"
echo "do, port 80 keeps serving and nothing changes for anyone."
