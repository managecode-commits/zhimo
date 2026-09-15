# Copyright © 2026 立方田 <managecode@gmail.com>
ARG BASE=debian:bookworm
FROM ${BASE}
RUN if [ -f /etc/apt/sources.list.d/debian.sources ]; then \
      sed -i 's@deb.debian.org/debian-security@security.debian.org/debian-security@g;s@deb.debian.org/debian@ftp.debian.org/debian@g' /etc/apt/sources.list.d/debian.sources; \
    fi
RUN apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
    build-essential cmake ninja-build pkg-config dpkg-dev python3 python3-gi \
    gir1.2-ibus-1.0 gir1.2-gtk-3.0 libfcitx5core-dev librime-dev ibus fcitx5 \
    ca-certificates binutils dbus-x11 xvfb xauth pulseaudio-utils python3-gi-cairo \
    && apt-get clean
ENV CARGO_HOME=/cargo CARGO_TARGET_DIR=/out/cargo RIME_ROOT=/usr
ENV PATH=/toolchain/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
WORKDIR /src
