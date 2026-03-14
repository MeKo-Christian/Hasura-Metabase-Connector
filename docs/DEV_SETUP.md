# Development Setup

## Prerequisites

| Tool | Version | Install |
|------|---------|---------|
| Java | 11+ (21 recommended) | [adoptium.net](https://adoptium.net) |
| Clojure CLI | latest | [clojure.org/guides/install_clojure](https://clojure.org/guides/install_clojure) |
| Docker + Compose | Docker 24+ | [docs.docker.com](https://docs.docker.com/get-docker/) |

## Build the plugin JAR

No Metabase checkout is required to build.  The JAR ships `.clj` source files;
Metabase compiles them at plugin load time using its own JVM classloader.

```bash
clojure -T:build jar
# → target/metabase-driver-hasura-0.1.0-SNAPSHOT.metabase-driver.jar
```

Verify the JAR contains `metabase-plugin.yaml` at the root:

```bash
jar tf target/*.metabase-driver.jar | grep metabase-plugin.yaml
```

## Run unit tests

Unit tests use static fixtures (`test/de/meko/metabase/driver/hasura/fixtures.clj`)
and do not require Docker or a Metabase checkout.

```bash
make test-unit
# or:
clojure -M:test --namespace-regex '(?!.*integration).*-test$'
```

## Run integration tests

Integration tests hit a real Hasura + Postgres stack.

```bash
# 1. Start the stack (Postgres + Hasura; Metabase is optional for integration tests)
make stack-up

# 2. Verify the seed data and Hasura tracking
make stack-seed

# 3. Run integration tests
make test-integration
```

## REPL development against a live Metabase

To develop and test multimethod implementations interactively, you need a local
Metabase OSS checkout:

```bash
# Clone Metabase at the supported version
git clone https://github.com/metabase/metabase.git ../metabase
cd ../metabase
git checkout v0.59.0

# Back in this repo, start a REPL with Metabase on the classpath
# deps.edn :dev alias picks up clj-http, cheshire, and test utilities.
# Add your local Metabase checkout to the classpath:
METABASE_DIR=../metabase clojure -A:dev -Sdeps '{:deps {metabase/metabase-core {:local/root "../metabase"}}}'
```

Inside the REPL:
```clojure
;; Load the driver (Metabase must be initialised first in a real plugin host)
(require 'de.meko.metabase.driver.hasura)
```

## Install the plugin in a local Metabase

```bash
# 1. Build the JAR
make build

# 2. Start the full stack (includes Metabase with the plugin volume-mounted)
make stack-up

# Metabase mounts target/ as /plugins inside the container.
# After building, restart Metabase to pick up the new JAR:
docker compose restart metabase

# 3. Open Metabase and add a Hasura database
open http://localhost:6300
```

Connection settings for the local stack:

| Field | Value |
|-------|-------|
| Endpoint | `http://hasura:8080` (from inside Docker network) |
| Admin Secret | `local-admin-secret` |
| Use Metadata API | ✓ |

## Stack management

```bash
make stack-up       # start Postgres + Hasura + Metabase
make stack-seed     # verify Hasura metadata was applied
make stack-reset    # full teardown + fresh seed
make stack-down     # stop and remove all containers and volumes
make stack-status   # show container status
```

## Lint

```bash
make lint
# or: clojure -M:lint
```
