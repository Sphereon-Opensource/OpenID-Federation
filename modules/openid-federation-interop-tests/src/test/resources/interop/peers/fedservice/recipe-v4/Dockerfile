# Source-built test peer candidate. No image was built during preparation.
# The image is pinned to the linux/amd64 child manifest, not a mutable tag.
FROM --platform=linux/amd64 python@sha256:edd0b3ec946cc68bd20e39480bd03929016d0fb6254cbf870cd3bc55ea25116d AS builder

ENV PIP_DISABLE_PIP_VERSION_CHECK=1 \
    PIP_NO_CACHE_DIR=1 \
    HTTP_PROXY= HTTPS_PROXY= ALL_PROXY= \
    http_proxy= https_proxy= all_proxy=
WORKDIR /build

COPY build-closure/requirements.txt /tmp/build-requirements.txt
COPY pypi-closure/requirements.txt /tmp/runtime-requirements.txt
COPY sources/ /tmp/sources/
COPY sources.sha256 /tmp/sources.sha256

# Every archive is an exact source export at a full Git commit.
RUN cd /tmp && sha256sum -c sources.sha256 \
 && mkdir -p /src/fedservice /src/idpy-oidc /src/openid4v /src/idpy-sdjwt /opt/python \
 && tar -xzf /tmp/sources/fedservice-0304f72453214c4f85bed7f792a6b5ad39f357d3.tar.gz --strip-components=1 -C /src/fedservice \
 && tar -xzf /tmp/sources/idpy-oidc-1a66289b5f788d5b54024de94e9cfd860fb36a8c.tar.gz --strip-components=1 -C /src/idpy-oidc \
 && tar -xzf /tmp/sources/openid4v-dfcea1c10a038f67766d453433840c64a9aa2b10.tar.gz --strip-components=1 -C /src/openid4v \
 && tar -xzf /tmp/sources/idpy-sdjwt-de1715ef89c7f3db5daf3cdc53fec26d6ade03d9.tar.gz --strip-components=1 -C /src/idpy-sdjwt

# Install only hash-locked binary wheels for the complete PyPI runtime closure.
RUN python -m pip install --only-binary=:all: --require-hashes \
      --target=/opt/python -r /tmp/runtime-requirements.txt \
 && python -m pip install --only-binary=:all: --require-hashes \
      -r /tmp/build-requirements.txt

# Build the exact pinned source exports locally with the separately locked
# build backends. --no-deps and --no-index prohibit a second resolver/network path.
RUN python -m pip install --no-deps --no-build-isolation --no-index --target=/opt/python /src/idpy-oidc \
 && python -m pip install --no-deps --no-build-isolation --no-index --target=/opt/python /src/idpy-sdjwt \
 && python -m pip install --no-deps --no-build-isolation --no-index --target=/opt/python /src/fedservice \
 && python -m pip install --no-deps --no-build-isolation --no-index --target=/opt/python /src/openid4v

FROM --platform=linux/amd64 python@sha256:edd0b3ec946cc68bd20e39480bd03929016d0fb6254cbf870cd3bc55ea25116d AS peer
ENV PYTHONPATH=/opt/python \
    PIP_DISABLE_PIP_VERSION_CHECK=1 \
    HTTP_PROXY= HTTPS_PROXY= ALL_PROXY= \
    http_proxy= https_proxy= all_proxy=
WORKDIR /opt/fedservice/edu_federation
COPY --from=builder /opt/python /opt/python
COPY --from=builder /src/fedservice/edu_federation /opt/fedservice/edu_federation
COPY --from=builder /src/fedservice/setup_federation /opt/fedservice/setup_federation
RUN mkdir -p /usr/share/licenses/fedservice /usr/share/licenses/idpy-oidc \
             /usr/share/licenses/openid4v /usr/share/licenses/idpy-sdjwt
COPY --from=builder /src/fedservice/LICENSE /usr/share/licenses/fedservice/LICENSE
COPY --from=builder /src/idpy-oidc/LICENSE /usr/share/licenses/idpy-oidc/LICENSE
COPY --from=builder /src/openid4v/LICENSE /usr/share/licenses/openid4v/LICENSE
COPY --from=builder /src/idpy-sdjwt/LICENSE /usr/share/licenses/idpy-sdjwt/LICENSE
COPY fedservice-entrypoint.py /usr/local/bin/fedservice-entrypoint.py
COPY provision-subordinate.sh /usr/local/bin/provision-subordinate
RUN chmod 0555 /usr/local/bin/provision-subordinate
RUN mkdir -p /var/lib/fedservice && chown 10001:10001 /var/lib/fedservice
VOLUME ["/var/lib/fedservice"]
USER root
EXPOSE 7003
ENTRYPOINT ["python", "/usr/local/bin/fedservice-entrypoint.py"]
CMD ["trust_anchor"]


