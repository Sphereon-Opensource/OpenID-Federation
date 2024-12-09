#!/bin/bash

source ./setup-env.sh

# Push federation server images
docker tag ${FED_IMAGE}:${FED_VERSION} ${DOCKER_REGISTRY}/${FED_IMAGE}:${FED_VERSION}
docker push ${DOCKER_REGISTRY}/${FED_IMAGE}:${FED_VERSION}
docker tag ${FED_IMAGE}:${FED_VERSION} ${DOCKER_REGISTRY}/${FED_IMAGE}:latest
docker push ${DOCKER_REGISTRY}/${FED_IMAGE}:latest

# Push admin server images
docker tag ${ADMIN_IMAGE}:${FED_VERSION} ${DOCKER_REGISTRY}/${ADMIN_IMAGE}:${FED_VERSION}
docker push ${DOCKER_REGISTRY}/${ADMIN_IMAGE}:${FED_VERSION}
docker tag ${ADMIN_IMAGE}:${FED_VERSION} ${DOCKER_REGISTRY}/${ADMIN_IMAGE}:latest
docker push ${DOCKER_REGISTRY}/${ADMIN_IMAGE}:latest
