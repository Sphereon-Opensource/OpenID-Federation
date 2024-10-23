#!/bin/bash

source ./version-config.sh

docker build -t ${FED_IMAGE}:${FED_VERSION} -f ../federation-server/Dockerfile ../../
docker build -t ${ADMIN_IMAGE}:${ADMIN_VERSION} -f ../admin-server/Dockerfile ../../
