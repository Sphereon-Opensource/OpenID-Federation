FROM python:3.12.15-slim-bookworm@sha256:edd0b3ec946cc68bd20e39480bd03929016d0fb6254cbf870cd3bc55ea25116d

ENV PIP_CONFIG_FILE=/dev/null \
    PIP_INDEX_URL=https://pypi.org/simple \
    PIP_EXTRA_INDEX_URL= \
    PIP_TRUSTED_HOST= \
    PYTHONPATH=/opt/ofcli-source/src \
    PYTHONUNBUFFERED=1

WORKDIR /opt/ofcli-probe
COPY runtime-requirements.txt /tmp/runtime-requirements.txt
COPY sources/ofcli-289e97f45d1fd225f16ff77976a7ede1ea6390f0.tar.gz /tmp/ofcli-source.tar.gz
RUN echo "32173ee18f059fe351adf3de677fbf2efc9b53dc48e12458288bde86fa5fdb13  /tmp/ofcli-source.tar.gz" | sha256sum -c - \
    && python -m pip install --no-cache-dir --disable-pip-version-check --only-binary=:all: --require-hashes --index-url=https://pypi.org/simple -r /tmp/runtime-requirements.txt \
    && mkdir -p /opt/ofcli-source \
    && tar -xzf /tmp/ofcli-source.tar.gz -C /opt/ofcli-source \
    && rm /tmp/ofcli-source.tar.gz /tmp/runtime-requirements.txt

COPY ofcli_structural_probe.py /opt/ofcli-probe/ofcli_structural_probe.py
RUN python -m pip check \
    && python -c "import aiohttp, cryptojwt, ofcli.cli, pygraphviz; print('ofcli structural-probe imports passed')" \
    && python -m ofcli --version

ENTRYPOINT ["python", "/opt/ofcli-probe/ofcli_structural_probe.py"]
