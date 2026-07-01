#!/usr/bin/env bash
set -euo pipefail

# Syncs the persisted Keycloak realm settings needed by the marketplace native
# login/register bridge. Run from the repository root on a Docker Compose host.

ENV_FILE="${ENV_FILE:-.env.demo}"
COMPOSE_FILE="${COMPOSE_FILE:-docker-compose.demo.yml}"
REALM="${KEYCLOAK_REALM:-msb-local}"

if [ -f "$ENV_FILE" ]; then
  set -a
  # shellcheck disable=SC1090
  . "$ENV_FILE"
  set +a
fi

DC=(docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE")
KC_ADMIN_USER="${KEYCLOAK_ADMIN:-admin}"
KC_ADMIN_PASSWORD="${KEYCLOAK_ADMIN_PASSWORD:-demo-change-me-admin}"
REALM="${KEYCLOAK_REALM:-$REALM}"
MARKET_CLIENT="${KEYCLOAK_MARKETPLACE_CLIENT_ID:-msb-marketplace}"
MARKET_SECRET="${KEYCLOAK_MARKETPLACE_CLIENT_SECRET:-local-dev-only-change-me-marketplace}"
GATEWAY_CLIENT="${KEYCLOAK_GATEWAY_ADMIN_CLIENT_ID:-msb-gateway-admin}"
GATEWAY_SECRET="${KEYCLOAK_GATEWAY_ADMIN_CLIENT_SECRET:-local-dev-only-change-me-gateway-admin}"

kcadm() {
  "${DC[@]}" exec -T keycloak /opt/keycloak/bin/kcadm.sh "$@"
}

client_uuid() {
  kcadm get clients -r "$REALM" -q clientId="$1" --fields id --format csv \
    | awk -F, '{gsub(/"/, "", $1); gsub(/\r/, "", $1); if ($1 != "" && $1 != "id") {print $1; exit}}'
}

kcadm config credentials \
  --server http://localhost:8080 \
  --realm master \
  --user "$KC_ADMIN_USER" \
  --password "$KC_ADMIN_PASSWORD" >/dev/null

market_id="$(client_uuid "$MARKET_CLIENT")"
if [ -z "$market_id" ]; then
  echo "Missing Keycloak client: $MARKET_CLIENT" >&2
  exit 1
fi

kcadm update "clients/$market_id" \
  -r "$REALM" \
  -s directAccessGrantsEnabled=true \
  -s secret="$MARKET_SECRET" >/dev/null

gateway_id="$(client_uuid "$GATEWAY_CLIENT")"
if [ -z "$gateway_id" ]; then
  kcadm create clients \
    -r "$REALM" \
    -s clientId="$GATEWAY_CLIENT" \
    -s enabled=true \
    -s protocol=openid-connect \
    -s publicClient=false \
    -s clientAuthenticatorType=client-secret \
    -s secret="$GATEWAY_SECRET" \
    -s standardFlowEnabled=false \
    -s implicitFlowEnabled=false \
    -s directAccessGrantsEnabled=false \
    -s serviceAccountsEnabled=true >/dev/null
  gateway_id="$(client_uuid "$GATEWAY_CLIENT")"
fi

kcadm update "clients/$gateway_id" \
  -r "$REALM" \
  -s enabled=true \
  -s publicClient=false \
  -s clientAuthenticatorType=client-secret \
  -s secret="$GATEWAY_SECRET" \
  -s serviceAccountsEnabled=true >/dev/null

kcadm add-roles \
  -r "$REALM" \
  --uusername "service-account-$GATEWAY_CLIENT" \
  --cclientid realm-management \
  --rolename manage-users >/dev/null

kcadm add-roles \
  -r "$REALM" \
  --uusername "service-account-$GATEWAY_CLIENT" \
  --cclientid realm-management \
  --rolename view-users >/dev/null

echo "Keycloak native auth settings synced for realm $REALM."
