#!/bin/bash

if ! source ./setup-env.sh; then
    echo "Error: Failed to source setup-env.sh"
    exit 1
fi

docker build -t ${FED_IMAGE}:${FED_VERSION} -f ../openid-federation-server/Dockerfile ../../
docker build -t ${ADMIN_IMAGE}:${FED_VERSION} -f ../openid-federation-admin-server/Dockerfile ../../
