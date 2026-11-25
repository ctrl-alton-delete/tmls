#!/bin/sh
set -eu
[ -n "${AUTHORIZED_KEY:-}" ] || { echo "AUTHORIZED_KEY is not set" >&2; exit 1; }
install -d -m 700 -o tmls -g tmls /home/tmls/.ssh
printf '%s\n' "$AUTHORIZED_KEY" > /home/tmls/.ssh/authorized_keys
chown tmls:tmls /home/tmls/.ssh/authorized_keys
chmod 600 /home/tmls/.ssh/authorized_keys
ssh-keygen -A >/dev/null
exec /usr/sbin/sshd -D -e
