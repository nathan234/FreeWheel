#!/bin/bash

# Build script for FreeWheelCore KMP framework
# This script is called by Xcode as a build phase

set -e

# Set up environment that Xcode strips
export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 21 2>/dev/null || /usr/libexec/java_home 2>/dev/null || echo /usr/local/java)}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"

# Navigate to project root (parent of iosApp)
cd "$SRCROOT/.."

# Determine which architecture to build for
if [ "$PLATFORM_NAME" = "iphonesimulator" ]; then
    if [ "$NATIVE_ARCH" = "arm64" ] || [ "$ARCHS" = "arm64" ]; then
        GRADLE_TASK=":core:linkReleaseFrameworkIosSimulatorArm64"
        FRAMEWORK_DIR="iosSimulatorArm64"
    else
        GRADLE_TASK=":core:linkReleaseFrameworkIosX64"
        FRAMEWORK_DIR="iosX64"
    fi
else
    GRADLE_TASK=":core:linkReleaseFrameworkIosArm64"
    FRAMEWORK_DIR="iosArm64"
fi

echo "Building KMP framework with task: $GRADLE_TASK"

# Build the framework
./gradlew $GRADLE_TASK --no-daemon

echo "KMP framework built successfully: core/build/bin/$FRAMEWORK_DIR/releaseFramework/FreeWheelCore.framework"
