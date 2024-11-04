#!/bin/bash

if ! source ./version-config.sh; then
    echo "Error: Failed to source version-config.sh"
    exit 1
fi

docker build -t ${FED_IMAGE}:${FED_VERSION} -f ../federation-server/Dockerfile ../../
docker build -t ${ADMIN_IMAGE}:${FED_VERSION} -f ../admin-server/Dockerfile ../../
