.PHONY: help deps build clean fmt fmt-check \
        stack-up stack-seed stack-reset stack-down stack-status \
        test-unit test-integration test-all lint

# ─────────────────────────────────────────────────────────────────────────────
# Configuration
#
# Load .env so that HASURA_GRAPHQL_ADMIN_SECRET (the variable docker-compose
# reads automatically) is available as a make variable.  This means that
# `make test-integration` uses the same secret as the running stack without
# the user having to export anything extra.
# ─────────────────────────────────────────────────────────────────────────────

-include .env

HASURA_URL      ?= http://localhost:6080
# Prefer the explicit HASURA_SECRET override; fall back to the docker-compose
# variable (from .env or shell); then fall back to the compiled-in default.
HASURA_SECRET   ?= $(or $(HASURA_GRAPHQL_ADMIN_SECRET),local-admin-secret)
COMPOSE         := docker compose
CLOJURE         := clojure

# ─────────────────────────────────────────────────────────────────────────────
# Help
# ─────────────────────────────────────────────────────────────────────────────

help:
	@echo ""
	@echo "Hasura Metabase Driver — development commands"
	@echo ""
	@echo "  Project"
	@echo "    make deps              Download Clojure dependencies"
	@echo "    make build             Compile and produce plugin JAR in target/"
	@echo "    make clean             Delete target/ directory"
	@echo "    make lint              Run clj-kondo static analysis"
	@echo "    make fmt               Format Markdown, YAML, and JSON with Prettier"
	@echo "    make fmt-check         Check formatting (CI-friendly, no writes)"
	@echo ""
	@echo "  Tests"
	@echo "    make test-unit         Run unit tests (no Docker required)"
	@echo "    make test-integration  Run integration tests (stack must be running)"
	@echo "    make test-all          Unit + integration"
	@echo ""
	@echo "  Docker stack"
	@echo "    make stack-up          Start Postgres, Hasura, Metabase"
	@echo "    make stack-seed        Wait for healthy Hasura then verify metadata"
	@echo "    make stack-reset       Full teardown + recreate with fresh seed data"
	@echo "    make stack-down        Stop and remove all containers and volumes"
	@echo "    make stack-status      Print container status"
	@echo ""
	@echo "  Environment variables"
	@echo "    HASURA_URL     Hasura base URL  (default: http://localhost:6080)"
	@echo "    HASURA_SECRET  Admin secret     (default: local-admin-secret)"
	@echo ""

# ─────────────────────────────────────────────────────────────────────────────
# Project
# ─────────────────────────────────────────────────────────────────────────────

deps:
	$(CLOJURE) -A:dev -P

build:
	$(CLOJURE) -T:build jar

clean:
	$(CLOJURE) -T:build clean

lint:
	$(CLOJURE) -M:lint

FMT_GLOB := "**/*.{md,yaml,yml,json}"

fmt:
	prettier --write $(FMT_GLOB)

fmt-check:
	prettier --check $(FMT_GLOB)

# ─────────────────────────────────────────────────────────────────────────────
# Tests
# ─────────────────────────────────────────────────────────────────────────────

test-unit:
	$(CLOJURE) -M:test

test-integration:
	HASURA_URL=$(HASURA_URL) HASURA_SECRET=$(HASURA_SECRET) $(CLOJURE) -M:test-integration

test-all: test-unit test-integration

# ─────────────────────────────────────────────────────────────────────────────
# Docker stack
# ─────────────────────────────────────────────────────────────────────────────

# Copy .env.example to .env if it does not exist yet.
.env:
	cp .env.example .env
	@echo "Created .env from .env.example — edit as needed."

stack-up: .env
	$(COMPOSE) up -d
	@echo ""
	@echo "Waiting for Hasura to become healthy..."
	@$(COMPOSE) run --rm -T --no-deps \
	  -e HASURA_URL=$(HASURA_URL) \
	  --entrypoint sh postgres \
	  -c 'until curl -sf $(HASURA_URL)/healthz; do sleep 2; done && echo " Hasura ready."' \
	  || true
	@echo ""
	@echo "Services:"
	@echo "  Hasura console : $(HASURA_URL)/console"
	@echo "  Metabase       : http://localhost:6300"
	@echo ""

stack-seed: .env
	@echo "Verifying Hasura metadata was applied..."
	@curl -sf \
	  -H "X-Hasura-Admin-Secret: $(HASURA_SECRET)" \
	  -H "Content-Type: application/json" \
	  -d '{"type":"export_metadata","args":{}}' \
	  $(HASURA_URL)/v1/metadata \
	  | python3 -c "import sys,json; m=json.load(sys.stdin); \
	    tables=[t['table']['name'] for s in m.get('metadata',{}).get('sources',[]) for t in s.get('tables',[])];\
	    print('Tracked tables:', tables);\
	    assert 'authors' in tables, 'authors not tracked — check dev/hasura/metadata/'" \
	  && echo "Metadata OK." \
	  || (echo "ERROR: Hasura metadata check failed. See docs/TEST_STRATEGY.md." && exit 1)

stack-reset: .env
	$(COMPOSE) down -v
	$(COMPOSE) up -d
	@echo "Stack reset. Run 'make stack-seed' after containers are healthy."

stack-down:
	$(COMPOSE) down -v

stack-status:
	$(COMPOSE) ps
