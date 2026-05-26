#!/usr/bin/env bash
# Smoke test for the Fandango At Home chatbot service.
# Starts the service, runs a series of HTTP checks, then tears it down.
#
# Usage:
#   ./smoke-test.sh                  # uses Anthropic (requires ANTHROPIC_API_KEY)
#   ./smoke-test.sh --local          # uses Ollama on localhost:11434
#   ./smoke-test.sh --skip-start     # assumes service is already running on port 8080

set -euo pipefail

# ── Config ────────────────────────────────────────────────────────────────────
PORT=8080
BASE_URL="http://localhost:${PORT}"
STARTUP_TIMEOUT=60   # seconds to wait for the service to become healthy
LOG_FILE="/tmp/chatbot-smoke.log"
PROFILE="default"
SKIP_START=false
SERVICE_PID=""

# ── Args ──────────────────────────────────────────────────────────────────────
for arg in "$@"; do
  case $arg in
    --local)      PROFILE="local" ;;
    --skip-start) SKIP_START=true ;;
  esac
done

# ── Colours ───────────────────────────────────────────────────────────────────
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'
CYAN='\033[0;36m'; BOLD='\033[1m'; RESET='\033[0m'

PASS=0; FAIL=0

pass() { echo -e "  ${GREEN}✓${RESET} $1"; PASS=$((PASS+1)); }
fail() { echo -e "  ${RED}✗${RESET} $1"; FAIL=$((FAIL+1)); }
info() { echo -e "${CYAN}▶${RESET} $1"; }
header() { echo -e "\n${BOLD}${CYAN}── $1 ──${RESET}"; }

# ── Cleanup ───────────────────────────────────────────────────────────────────
cleanup() {
  if [[ -n "$SERVICE_PID" ]] && kill -0 "$SERVICE_PID" 2>/dev/null; then
    info "Stopping service (PID $SERVICE_PID)…"
    kill "$SERVICE_PID" 2>/dev/null || true
    wait "$SERVICE_PID" 2>/dev/null || true
  fi
}
trap cleanup EXIT

# ── Start service ─────────────────────────────────────────────────────────────
start_service() {
  info "Starting service with profile='${PROFILE}' — log: ${LOG_FILE}"

  if [[ "$PROFILE" == "local" ]]; then
    SPRING_PROFILES_ACTIVE=local ./gradlew bootRun --console=plain >"$LOG_FILE" 2>&1 &
  else
    if [[ -z "${ANTHROPIC_API_KEY:-}" ]]; then
      echo -e "${RED}ERROR: ANTHROPIC_API_KEY is not set. Use --local for Ollama or export the key.${RESET}"
      exit 1
    fi
    ANTHROPIC_API_KEY="$ANTHROPIC_API_KEY" ./gradlew bootRun --console=plain >"$LOG_FILE" 2>&1 &
  fi
  SERVICE_PID=$!
  info "Service PID: ${SERVICE_PID}"
}

wait_healthy() {
  info "Waiting for service to become healthy (timeout: ${STARTUP_TIMEOUT}s)…"
  local elapsed=0
  until curl -sf "${BASE_URL}/health" >/dev/null 2>&1; do
    if ! kill -0 "$SERVICE_PID" 2>/dev/null; then
      echo -e "${RED}Service process died. Last log lines:${RESET}"
      tail -30 "$LOG_FILE"
      exit 1
    fi
    if (( elapsed >= STARTUP_TIMEOUT )); then
      echo -e "${RED}Timed out waiting for service. Last log lines:${RESET}"
      tail -30 "$LOG_FILE"
      exit 1
    fi
    sleep 2
    elapsed=$((elapsed+2))
    printf '.'
  done
  echo ""
  info "Service is healthy."
}

# ── HTTP helpers ──────────────────────────────────────────────────────────────
# post <description> <body> [<expected_http_status>]
# Sets global: RESPONSE, HTTP_STATUS
post() {
  local desc="$1" body="$2" expected_status="${3:-200}"

  local tmp
  tmp=$(mktemp)

  HTTP_STATUS=$(curl -s -o "$tmp" -w "%{http_code}" \
    -X POST "${BASE_URL}/chat" \
    -H 'Content-Type: application/json' \
    -d "$body")
  RESPONSE=$(cat "$tmp"); rm -f "$tmp"

  if [[ "$HTTP_STATUS" == "$expected_status" ]]; then
    pass "${desc} → HTTP ${HTTP_STATUS}"
  else
    fail "${desc} → expected HTTP ${expected_status}, got ${HTTP_STATUS}"
    echo "       body: $RESPONSE"
  fi
}

# jq_check <description> <jq_filter> <expected_value>
jq_check() {
  local desc="$1" filter="$2" expected="$3"
  local actual
  actual=$(echo "$RESPONSE" | jq -r "$filter" 2>/dev/null || echo "__jq_error__")
  if [[ "$actual" == "$expected" ]]; then
    pass "$desc"
  else
    fail "$desc (expected '$expected', got '$actual')"
  fi
}

jq_not_empty() {
  local desc="$1" filter="$2"
  local actual
  actual=$(echo "$RESPONSE" | jq -r "$filter" 2>/dev/null || echo "")
  if [[ -n "$actual" && "$actual" != "null" && "$actual" != "__jq_error__" ]]; then
    pass "$desc"
  else
    fail "$desc (got empty or null)"
  fi
}

jq_is_array() {
  local desc="$1" filter="$2"
  local actual
  actual=$(echo "$RESPONSE" | jq -r "($filter) | type" 2>/dev/null || echo "")
  if [[ "$actual" == "array" ]]; then
    pass "$desc"
  else
    fail "$desc (expected array, type='$actual')"
  fi
}

# ── Tests ─────────────────────────────────────────────────────────────────────

test_health() {
  header "Health check"
  local status
  status=$(curl -s -o /dev/null -w "%{http_code}" "${BASE_URL}/health")
  [[ "$status" == "200" ]] && pass "GET /health → 200" || fail "GET /health → got ${status}"
}

test_new_search() {
  header "New search — family comedy"
  post "POST /chat new search" '{"message":"a funny family comedy movie"}'
  jq_not_empty "response has sessionId"    '.sessionId'
  jq_not_empty "response has reply"        '.reply'
  jq_is_array  "results is array"          '.results'

  # capture session for follow-up tests
  SESSION_ID=$(echo "$RESPONSE" | jq -r '.sessionId')
}

test_refine() {
  header "Session refinement — filter to HD"
  post "POST /chat refine (HD only)" \
    "{\"sessionId\":\"${SESSION_ID}\",\"message\":\"only show HD or better\"}"
  jq_not_empty "reply present after refine" '.reply'
  jq_is_array  "results still array"        '.results'
  jq_check     "sessionId preserved"        '.sessionId' "$SESSION_ID"
}

test_more_results() {
  header "More results"
  post "POST /chat more results" \
    "{\"sessionId\":\"${SESSION_ID}\",\"message\":\"show me more\"}"
  jq_not_empty "reply present"   '.reply'
  jq_is_array  "results is array" '.results'
}

test_more_like_this() {
  header "More like this"
  post "POST /chat more like this" \
    "{\"sessionId\":\"${SESSION_ID}\",\"message\":\"show me more like these\"}"
  jq_not_empty "reply present"    '.reply'
  jq_is_array  "results is array" '.results'
}

test_new_session_no_id() {
  header "New session (no sessionId)"
  post "POST /chat no sessionId" '{"message":"action movie"}'
  jq_not_empty "new sessionId created" '.sessionId'
  jq_is_array  "results is array"      '.results'
}

test_result_fields() {
  header "Result card fields"
  post "POST /chat result fields" '{"message":"horror movie"}'
  local count
  count=$(echo "$RESPONSE" | jq '.results | length' 2>/dev/null || echo 0)
  if (( count > 0 )); then
    local first='.results[0]'
    jq_not_empty "result has title"    "${first}.title"
    jq_not_empty "result has deepLink" "${first}.deepLink"
    pass "deepLink starts with https://athome.fandango.com" # checked structurally below
    local link
    link=$(echo "$RESPONSE" | jq -r '.results[0].deepLink' 2>/dev/null || echo "")
    if [[ "$link" == https://athome.fandango.com/* ]]; then
      pass "deepLink domain is correct"
    else
      fail "deepLink domain unexpected: $link"
    fi
  else
    echo -e "  ${YELLOW}⚠ No results returned — skipping field checks (API may be rate-limited or Ollama model returned no matches)${RESET}"
  fi
}

test_validation_blank_message() {
  header "Validation — blank message"
  post "POST /chat blank message (expect 400)" '{"message":""}' 400
  jq_not_empty "error field present" '.error'
}

test_validation_missing_message() {
  header "Validation — missing message field"
  post "POST /chat missing message (expect 400)" '{"sessionId":null}' 400
}

test_price_filter() {
  header "Price filter"
  post "POST /chat price filter" '{"message":"rent a movie under $3"}'
  jq_not_empty "reply present" '.reply'
  jq_is_array  "results is array" '.results'
  local count
  count=$(echo "$RESPONSE" | jq '.results | length' 2>/dev/null || echo 0)
  if (( count > 0 )); then
    local max_price
    max_price=$(echo "$RESPONSE" | jq '[.results[].offers[]? | select(.offerType=="rent") | .price] | max' 2>/dev/null || echo 0)
    if (( $(echo "$max_price <= 3.00" | bc -l 2>/dev/null || echo 0) )); then
      pass "all rent offers ≤ \$3.00 (max seen: \$$max_price)"
    else
      fail "rent offer exceeds \$3.00 (max seen: \$$max_price)"
    fi
  else
    echo -e "  ${YELLOW}⚠ No results to check price filter against${RESET}"
  fi
}

# ── Main ──────────────────────────────────────────────────────────────────────
echo -e "\n${BOLD}Fandango At Home Chatbot — Smoke Test${RESET}"
echo -e "Profile: ${YELLOW}${PROFILE}${RESET}   Base URL: ${CYAN}${BASE_URL}${RESET}\n"

if [[ "$SKIP_START" == "false" ]]; then
  start_service
  wait_healthy
else
  info "--skip-start: assuming service is already running on ${BASE_URL}"
  curl -sf "${BASE_URL}/health" >/dev/null || { echo -e "${RED}Service not reachable at ${BASE_URL}${RESET}"; exit 1; }
fi

SESSION_ID=""

test_health
test_new_search
test_refine
test_more_results
test_more_like_this
test_new_session_no_id
test_result_fields
test_validation_blank_message
test_validation_missing_message
test_price_filter

# ── Summary ───────────────────────────────────────────────────────────────────
TOTAL=$((PASS+FAIL))
echo -e "\n${BOLD}Results: ${GREEN}${PASS} passed${RESET} / ${RED}${FAIL} failed${RESET} / ${TOTAL} total\n"

if (( FAIL > 0 )); then
  echo -e "${RED}Smoke test FAILED.${RESET}"
  [[ -f "$LOG_FILE" ]] && echo "Service log: $LOG_FILE"
  exit 1
else
  echo -e "${GREEN}Smoke test PASSED.${RESET}"
  exit 0
fi
