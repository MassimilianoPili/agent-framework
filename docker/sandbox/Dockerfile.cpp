# Sandbox image for C++ compilation (gcc + cmake)
# Security: runs as non-root (UID 1000), no network, read-only root filesystem
FROM gcc:14

RUN apt-get update && \
    apt-get install -y --no-install-recommends cmake make && \
    apt-get clean && \
    useradd -m -u 1000 sandbox && \
    mkdir -p /code /out && \
    chown 1000:1000 /code /out

USER 1000:1000
WORKDIR /code
