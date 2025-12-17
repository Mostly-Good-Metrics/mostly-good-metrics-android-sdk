#!/bin/bash
# Bootstrap script for android-sdk (Kotlin/Gradle)
set -e

echo "Bootstrapping android-sdk..."

# Install tools via mise (Java)
mise install

# Copy .env.sample to .env if it exists and .env doesn't
if [ -f ".env.sample" ] && [ ! -f ".env" ]; then
  cp .env.sample .env
  echo "Created .env from .env.sample"
fi

# Build and download dependencies
./gradlew build

echo "Done! Android SDK is ready."
