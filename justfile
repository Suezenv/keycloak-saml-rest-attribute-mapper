#set quiet

# Default recipe to display help information
[private]
default:
  just --list --unsorted

# Build the docker image
[private]
[no-exit-message]
build-docker:
    docker build --file="infra/Dockerfile" --tag="keycloak-spi-plugin" .

# Build the project with default Keycloak version
[no-exit-message]
build: build-docker
    docker run --rm --volume="${PWD}:/opt/maven" keycloak-spi-plugin mvn -B clean package

# Build for a specific Keycloak version
build-version VERSION: build-docker
    docker run --rm --volume="${PWD}:/opt/maven" keycloak-spi-plugin mvn -B clean package -P keycloak-{{VERSION}}


# Start Keycloak with the authenticator
up:
    docker compose up

# Stop Keycloak and clean up
down:
    docker compose down

# Watch the Keycloak logs
logs:
    docker compose logs -f keycloak

# Start a shell in the Keycloak container
shell:
    docker compose exec keycloak bash

# List all available Keycloak versions
versions:
    @echo "Supported Keycloak versions:"
    @echo "- 26.4.7 (default)"

