# A test host for the emulator (plan §5.1): sshd on port 2222 and tmux, user `tmls`, logging in
# only with the public key given as AUTHORIZED_KEY. The emulator reaches it at 10.0.2.2:2222.
#   docker build -t tmls-testhost tooling/android/testhost
#   docker run -d -p 2222:2222 -e AUTHORIZED_KEY="$(cat key.pub)" tmls-testhost
FROM debian:trixie-slim
RUN apt-get update \
 && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends openssh-server tmux procps locales \
 && rm -rf /var/lib/apt/lists/* \
 && useradd -m -s /bin/bash tmls \
 && mkdir -p /run/sshd \
 && printf 'Port 2222\nPasswordAuthentication no\nKbdInteractiveAuthentication no\nPermitRootLogin no\nAcceptEnv LANG LC_*\n' > /etc/ssh/sshd_config.d/tmls.conf
COPY entrypoint.sh /entrypoint.sh
EXPOSE 2222
ENTRYPOINT ["/bin/sh", "/entrypoint.sh"]
