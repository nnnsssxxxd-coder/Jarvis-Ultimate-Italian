// Funziona con `deno test` e con `node --test` (Node >= 22.18, type stripping).
import { test } from "node:test";
import assert from "node:assert/strict";
import {
  RateLimiter,
  backoffDelay,
  buildOpenAiPayload,
  classifyUpstream,
  makeError,
  parseRetryAfter,
  redactSecrets,
  resolveApiKey,
  shouldRetry,
  validateChatRequest,
} from "./lib.ts";

const envOf = (m: Record<string, string>) => (n: string) => m[n];

test("secret: legge 'jarvis' minuscolo per primo", () => {
  const r = resolveApiKey(envOf({ jarvis: "sk-aaa", OPENAI_API_KEY: "sk-bbb", Jarvis: "sk-ccc" }));
  assert.deepEqual(r, { key: "sk-aaa", source: "jarvis" });
});

test("secret: fallback OPENAI_API_KEY poi Jarvis", () => {
  assert.equal(resolveApiKey(envOf({ OPENAI_API_KEY: "sk-b", Jarvis: "sk-c" }))?.source, "OPENAI_API_KEY");
  assert.equal(resolveApiKey(envOf({ Jarvis: "sk-c" }))?.source, "Jarvis");
});

test("secret: valori vuoti o solo spazi vengono ignorati e le virgolette tolte", () => {
  assert.equal(resolveApiKey(envOf({ jarvis: "  \n", OPENAI_API_KEY: '"sk-x"\n' }))?.key, "sk-x");
});

test("secret: assente -> null", () => {
  assert.equal(resolveApiKey(envOf({})), null);
});

test("errori: ogni codice ha uno status e secret_missing è distinguibile", () => {
  const e = makeError("secret_missing");
  assert.equal(e.status, 500);
  assert.equal(e.retryable, false);
  assert.match(e.message, /jarvis/);
});

test("upstream 401 non inoltra il messaggio con pezzi di chiave", () => {
  const e = classifyUpstream(401, JSON.stringify({ error: { message: "Incorrect API key provided: sk-abc***xyz" } }));
  assert.equal(e.code, "openai_key_rejected");
  assert.doesNotMatch(e.message, /sk-/);
});

test("upstream 429: quota vs rate limit", () => {
  const quota = classifyUpstream(429, JSON.stringify({ error: { type: "insufficient_quota" } }));
  assert.equal(quota.code, "quota_exceeded");
  assert.equal(quota.retryable, false);
  const rl = classifyUpstream(429, "{}", "2");
  assert.equal(rl.code, "rate_limited");
  assert.equal(rl.retryAfterMs, 2000);
});

test("upstream 5xx e 404 e 400", () => {
  assert.equal(classifyUpstream(503, "").code, "openai_unavailable");
  assert.equal(classifyUpstream(404, "").code, "model_not_found");
  const bad = classifyUpstream(400, JSON.stringify({ error: { message: "bad sk-12345678 thing" } }));
  assert.equal(bad.code, "upstream_bad_request");
  assert.doesNotMatch(bad.message, /sk-1234/);
});

test("retry: solo errori transitori, con limite e attesa breve", () => {
  assert.equal(shouldRetry(makeError("openai_unavailable"), 0, 2), true);
  assert.equal(shouldRetry(makeError("openai_unavailable"), 2, 2), false);
  assert.equal(shouldRetry(makeError("quota_exceeded"), 0, 2), false);
  assert.equal(shouldRetry(makeError("rate_limited", { retryAfterMs: 30_000 }), 0, 2), false);
  assert.equal(shouldRetry(makeError("secret_missing"), 0, 2), false);
});

test("backoff: cresce, ha un tetto e rispetta Retry-After", () => {
  const fixed = () => 0.5;
  const d0 = backoffDelay(0, undefined, fixed);
  const d3 = backoffDelay(3, undefined, fixed);
  assert.ok(d3 > d0);
  assert.ok(backoffDelay(10, undefined, fixed) <= 4000);
  assert.equal(backoffDelay(0, 1500), 1500);
  assert.equal(backoffDelay(0, 60_000), 5000);
});

test("Retry-After in secondi e in data", () => {
  assert.equal(parseRetryAfter("3"), 3000);
  assert.equal(parseRetryAfter("Wed, 21 Oct 2026 07:28:00 GMT", Date.parse("Wed, 21 Oct 2026 07:27:58 GMT")), 2000);
  assert.equal(parseRetryAfter("boh"), undefined);
});

test("redazione chiavi", () => {
  assert.equal(redactSecrets("key sk-proj-abcd1234 e Bearer abc.def"), "key [chiave-oscurata] e Bearer [oscurato]");
});

test("validazione: input obbligatorio e tipi", () => {
  assert.equal(validateChatRequest({}).ok, false);
  assert.equal(validateChatRequest({ input: "" }).ok, false);
  assert.equal(validateChatRequest({ input: "ciao" }).ok, true);
  assert.equal(validateChatRequest({ input: [1] }).ok, false);
  assert.equal(validateChatRequest(null).ok, false);
});

test("validazione: tool non ammessi e modello non in lista", () => {
  assert.equal(validateChatRequest({ input: "x", tools: [{ type: "code_interpreter" }] }).ok, false);
  assert.equal(validateChatRequest({ input: "x", tools: [{ type: "web_search" }, { type: "function", name: "a" }] }).ok, true);
  assert.equal(validateChatRequest({ input: "x", model: "gpt-x" }, { allowedModels: ["gpt-4.1-mini"] }).ok, false);
  assert.equal(validateChatRequest({ input: "x", model: "gpt-4.1-mini" }, { allowedModels: ["gpt-4.1-mini"] }).ok, true);
  assert.equal(validateChatRequest({ input: "x", previous_response_id: "../x" }).ok, false);
  assert.equal(validateChatRequest({ input: "x", previous_response_id: "resp_abc123" }).ok, true);
});

test("validazione: max_output_tokens viene limitato", () => {
  const v = validateChatRequest({ input: "x", max_output_tokens: 999999 });
  assert.ok(v.ok && v.value.max_output_tokens === 8192);
  assert.equal(validateChatRequest({ input: "x", max_output_tokens: 3 }).ok, false);
});

test("payload OpenAI: campi sconosciuti del client non passano", () => {
  const v = validateChatRequest({ input: "ciao", stream: true, evil: { a: 1 }, user: "x" });
  assert.ok(v.ok);
  if (v.ok) {
    const p = buildOpenAiPayload(v.value, "gpt-4.1-mini");
    assert.deepEqual(Object.keys(p).sort(), ["input", "model", "store", "stream"]);
    assert.equal(p.stream, true);
    assert.equal(p.store, false);
  }
});

test("rate limiter: blocca oltre il limite e si libera dopo la finestra", () => {
  const rl = new RateLimiter(2, 1000);
  assert.equal(rl.check("u", 0).allowed, true);
  assert.equal(rl.check("u", 10).allowed, true);
  const blocked = rl.check("u", 20);
  assert.equal(blocked.allowed, false);
  assert.equal(blocked.retryAfterMs, 980);
  assert.equal(rl.check("u", 1001).allowed, true);
  assert.equal(rl.check("altro", 20).allowed, true);
});
