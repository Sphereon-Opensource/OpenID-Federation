#!/bin/bash

source ./version-config.sh

# Push federation server images
docker tag ${FED_IMAGE}:${FED_VERSION} ${REGISTRY}/${FED_IMAGE}:${FED_VERSION}
docker push ${REGISTRY}/${FED_IMAGE}:${FED_VERSION}
docker tag ${FED_IMAGE}:${FED_VERSION} ${REGISTRY}/${FED_IMAGE}:latest
docker push ${REGISTRY}/${FED_IMAGE}:latest

# Push admin server images
docker tag ${ADMIN_IMAGE}:${ADMIN_VERSION} ${REGISTRY}/${ADMIN_IMAGE}:${ADMIN_VERSION}
docker push ${REGISTRY}/${ADMIN_IMAGE}:${ADMIN_VERSION}
docker tag ${ADMIN_IMAGE}:${ADMIN_VERSION} ${REGISTRY}/${ADMIN_IMAGE}:latest
docker push ${REGISTRY}/${ADMIN_IMAGE}:latest
