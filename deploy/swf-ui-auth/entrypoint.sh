#!/bin/sh
# Render the Basic-Auth htpasswd from environment variables (never stored in the
# repository), then hand over to stock nginx. Keeping this wrapper in deploy/ means
# the nginx image and SeaweedFS upstream both stay untouched -> upgrades are safe.
# Hash = SHA-512 crypt via busybox cryptpw (nginx's musl libcrypt verifies $6$);
# the nginx:alpine image ships no openssl CLI (measured 2026-11-16, exit 127 loop).
set -eu
: "${SWF_UI_USER:?SWF_UI_USER missing (.env)}"
: "${SWF_UI_PASSWORD:?SWF_UI_PASSWORD missing (.env)}"
SALT=$(tr -dc 'A-Za-z0-9./' < /dev/urandom | head -c 16)
HASH=$(printf '%s' "$SWF_UI_PASSWORD" | cryptpw -m sha512 -S "$SALT")
printf '%s:%s\n' "$SWF_UI_USER" "$HASH" > /etc/nginx/seaweedfs.htpasswd
chmod 640 /etc/nginx/seaweedfs.htpasswd
# master runs as root, workers as nginx: the realm check happens in worker context,
# so the file must be group-readable by the worker user or auth lookups return 500.
chown root:nginx /etc/nginx/seaweedfs.htpasswd 2>/dev/null || chown root:www-data /etc/nginx/seaweedfs.htpasswd
exec nginx -g 'daemon off;'
