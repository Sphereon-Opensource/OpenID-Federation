#!/bin/bash

# Function to extract version from gradle file
get_version() {
    local gradle_file=$1
    local version=$(grep -m 1 "version = " "$gradle_file" | cut -d'"' -f2)
    if [ -z "$version" ]; then
        echo "Could not find version in $gradle_file"
        exit 1
    fi
    echo "$version"
}

# Base paths
MODULES_PATH="../../modules"
REGISTRY="sphereonregistry.azurecr.io"

# Get versions
FED_VERSION=$(get_version "${MODULES_PATH}/federation-server/build.gradle.kts")
ADMIN_VERSION=$(get_version "${MODULES_PATH}/admin-server/build.gradle.kts")

# Image names
FED_IMAGE="federation-server"
ADMIN_IMAGE="federation-admin-server"
