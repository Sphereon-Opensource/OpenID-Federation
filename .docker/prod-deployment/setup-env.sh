#!/bin/bash

# Function to extract version from gradle file
get_version() {
    local gradle_file=$1
    if [ ! -f "$gradle_file" ]; then
        echo "Error: Gradle file not found: $gradle_file" >&2
        exit 1
    fi
    
    local version=$(grep -m 1 "version = " "$gradle_file" | cut -d'"' -f2)
    if [ -z "$version" ]; then
        echo "Error: Could not find version in $gradle_file" >&2
        exit 1
    fi
    echo "$version"
}

# Base paths
MODULES_PATH="../../"
DOCKER_REGISTRY="${DOCKER_REGISTRY:-sphereonregistry.azurecr.io}"

# Get versions with error handling
FED_VERSION=$(get_version "${MODULES_PATH}/build.gradle.kts") || exit 1

# Image names
FED_IMAGE="openid-federation-server"
ADMIN_IMAGE="openid-federation-admin-server"

# Export variables
export FED_VERSION
export FED_IMAGE
export ADMIN_IMAGE
