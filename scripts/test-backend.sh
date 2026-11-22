#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")/.."
NODE_BIN="${NODE_BIN:-node}"
echo "== JARVIS 4.0: test libreria =="
"$NODE_BIN" --experimental-strip-types --test supabase/functions/jarvis-ai/lib.test.ts
echo "== JARVIS 4.0: test handler HTTP =="
"$NODE_BIN" --experimental-strip-types --test supabase/functions/jarvis-ai/handler.node-test.ts
echo "== Tutti i test backend completati =="
