ARG BASE=ubuntu:24.04
FROM ${BASE}
ARG HOSTLABEL=devbox
ARG MOSH_PORTS=60000:60010
RUN apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
      openssh-server tmux mosh python3 git locales procps ca-certificates sudo \
    && sed -i 's/# en_US.UTF-8/en_US.UTF-8/' /etc/locale.gen && locale-gen && rm -rf /var/lib/apt/lists/*
RUN useradd -m -s /bin/bash dev && echo 'dev:demo' | chpasswd && mkdir -p /run/sshd \
    && printf 'PasswordAuthentication yes\nKbdInteractiveAuthentication no\nPrintMotd no\nPrintLastLog no\n' > /etc/ssh/sshd_config.d/demo.conf
# mosh-server limited to the ports published for this container.
RUN printf '#!/bin/sh\n[ "$1" = new ] && { shift; exec /usr/bin/mosh-server new -p %s "$@"; }\nexec /usr/bin/mosh-server "$@"\n' "$MOSH_PORTS" \
      > /usr/local/bin/mosh-server && chmod 755 /usr/local/bin/mosh-server
COPY fake-agent.py /usr/local/bin/fake-agent
RUN chmod 755 /usr/local/bin/fake-agent && ln -s fake-agent /usr/local/bin/claude && ln -s fake-agent /usr/local/bin/codex \
    && cp /usr/bin/python3 /usr/local/bin/node
COPY home/ /home/dev/
ARG LANG_DEMO=zh
RUN echo "$LANG_DEMO" > /home/dev/.demo-lang && chown -R dev:dev /home/dev \
    && for d in acme-app infra blog; do su dev -c "cd ~/$d && git init -q -b main && git add -A && \
         git -c user.name=dev -c user.email=dev@example.com commit -qm init"; done
CMD ["/bin/sh", "-c", "(cd /home/dev && su dev -c 'node -c \"import time\nwhile 1:\n  t=time.time()\n  while time.time()-t<0.35: pass\n  time.sleep(0.65)\"' &) ; exec /usr/sbin/sshd -D -e"]
