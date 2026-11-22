// Test del gestore HTTP reale (index.ts) con fetch simulato. SOLO Node:
//   node --test handler.node-test.ts
// (in Deno index.ts avvierebbe davvero il server, quindi qui si usa uno shim di Deno.serve)
import { test, before } from "node:test";
import assert from "node:assert/strict";

const SECRET = "sk-test-SEGRETISSIMO-1234567890";
const envMap: Record<string, string> = {
  SUPABASE_URL: "https://x.supabase.co",
  SUPABASE_ANON_KEY: "anon-test",
};
let handler: (req: Request) => Promise<Response>;
type Call = { url: string; init?: RequestInit };
let calls: Call[] = [];
let script: Array<(c: Call) => Response | Promise<Response>> = [];

(globalThis as any).Deno = {
  env: { get: (n: string) => envMap[n] },
  serve: (h: any) => { handler = h; },
};

function userOk(id: string) {
  return () => new Response(JSON.stringify({ id, is_anonymous: true }), { status: 200 });
}

before(async () => {
  (globalThis as any).fetch = async (url: string, init?: RequestInit) => {
    const c = { url: String(url), init };
    calls.push(c);
    const next = script.shift();
    if (!next) throw new Error("fetch non previsto: " + url);
    return next(c);
  };
  await import("./index.ts");
});

function reset(secrets: Record<string, string | undefined> = {}) {
  calls = [];
  script = [];
  for (const k of ["jarvis", "OPENAI_API_KEY", "Jarvis"]) delete envMap[k];
  for (const [k, v] of Object.entries(secrets)) if (v !== undefined) envMap[k] = v;
}

function post(body: unknown, token: string | null = "tok") {
  const headers: Record<string, string> = { "content-type": "application/json" };
  if (token) headers.authorization = `Bearer ${token}`;
  return new Request("https://f/jarvis-ai", { method: "POST", headers, body: JSON.stringify(body) });
}

async function readJson(res: Response) {
  const text = await res.text();
  assert.ok(!text.includes(SECRET), "la chiave non deve mai comparire nelle risposte");
  return JSON.parse(text);
}

test("senza token -> 401 auth_invalid", async () => {
  reset({ jarvis: SECRET });
  const res = await handler(post({ input: "ciao" }, null));
  assert.equal(res.status, 401);
  assert.equal((await readJson(res)).error.code, "auth_invalid");
});

test("token rifiutato da Supabase -> 401", async () => {
  reset({ jarvis: SECRET });
  script = [() => new Response("{}", { status: 401 })];
  const res = await handler(post({ input: "ciao" }));
  assert.equal(res.status, 401);
});

test("Supabase auth giù -> 502 auth_unavailable (non 'errore di rete' generico)", async () => {
  reset({ jarvis: SECRET });
  script = [() => new Response("boom", { status: 500 })];
  const res = await handler(post({ input: "ciao" }));
  assert.equal(res.status, 502);
  assert.equal((await readJson(res)).error.code, "auth_unavailable");
});

test("secret mancante -> errore specifico secret_missing e OpenAI NON viene chiamata", async () => {
  reset({});
  script = [userOk("u-secret-missing")];
  const res = await handler(post({ input: "ciao" }));
  const j = await readJson(res);
  assert.equal(res.status, 500);
  assert.equal(j.error.code, "secret_missing");
  assert.equal(calls.filter((c) => c.url.includes("api.openai.com")).length, 0);
});

test("secret 'jarvis' minuscolo -> chiamata reale a OpenAI con Bearer corretto", async () => {
  reset({ jarvis: SECRET });
  script = [
    userOk("u-ok"),
    () => new Response(JSON.stringify({ id: "resp_1", output_text: "ciao" }), { status: 200 }),
  ];
  const res = await handler(post({ input: "ciao" }));
  const j = await readJson(res);
  assert.equal(res.status, 200);
  assert.equal(j.ok, true);
  assert.equal(j.data.id, "resp_1");
  const openai = calls.find((c) => c.url.includes("api.openai.com/v1/responses"))!;
  assert.equal((openai.init!.headers as Record<string, string>).Authorization, `Bearer ${SECRET}`);
  const sent = JSON.parse(String(openai.init!.body));
  assert.equal(sent.store, false);
  assert.equal(sent.stream, false);
});

test("OpenAI 503 poi 200 -> un solo retry e successo", async () => {
  reset({ OPENAI_API_KEY: SECRET });
  script = [
    userOk("u-retry"),
    () => new Response(JSON.stringify({ error: { message: "overloaded" } }), { status: 503 }),
    () => new Response(JSON.stringify({ id: "resp_2" }), { status: 200 }),
  ];
  const res = await handler(post({ input: "ciao" }));
  const j = await readJson(res);
  assert.equal(res.status, 200);
  assert.equal(j.meta.attempts, 2);
});

test("OpenAI 503 sempre -> dopo 3 tentativi errore tipizzato, niente loop infinito", async () => {
  reset({ jarvis: SECRET });
  script = [
    userOk("u-503"),
    () => new Response("x", { status: 503 }),
    () => new Response("x", { status: 503 }),
    () => new Response("x", { status: 503 }),
  ];
  const res = await handler(post({ input: "ciao" }));
  const j = await readJson(res);
  assert.equal(res.status, 502);
  assert.equal(j.error.code, "openai_unavailable");
  assert.equal(j.error.retryable, true);
  assert.equal(calls.filter((c) => c.url.includes("api.openai.com")).length, 3);
});

test("OpenAI 401 -> openai_key_rejected senza pezzi di chiave", async () => {
  reset({ jarvis: SECRET });
  script = [
    userOk("u-401"),
    () => new Response(JSON.stringify({ error: { message: `Incorrect API key provided: ${SECRET}` } }), { status: 401 }),
  ];
  const res = await handler(post({ input: "ciao" }));
  const j = await readJson(res);
  assert.equal(j.error.code, "openai_key_rejected");
  assert.equal(calls.filter((c) => c.url.includes("api.openai.com")).length, 1, "nessun retry su 401");
});

test("OpenAI 429 quota -> quota_exceeded non ritentabile", async () => {
  reset({ jarvis: SECRET });
  script = [
    userOk("u-quota"),
    () => new Response(JSON.stringify({ error: { type: "insufficient_quota" } }), { status: 429 }),
  ];
  const j = await readJson(await handler(post({ input: "ciao" })));
  assert.equal(j.error.code, "quota_exceeded");
  assert.equal(j.error.retryable, false);
});

test("rete OpenAI assente -> openai_unreachable", async () => {
  reset({ jarvis: SECRET });
  script = [
    userOk("u-net"),
    () => { throw new TypeError("fetch failed"); },
    () => { throw new TypeError("fetch failed"); },
    () => { throw new TypeError("fetch failed"); },
  ];
  const j = await readJson(await handler(post({ input: "ciao" })));
  assert.equal(j.error.code, "openai_unreachable");
});

test("richiesta non valida e tool vietati -> 400 senza toccare OpenAI", async () => {
  reset({ jarvis: SECRET });
  script = [userOk("u-bad"), userOk("u-bad")];
  const a = await handler(post({ foo: 1 }));
  assert.equal(a.status, 400);
  const b = await handler(post({ input: "x", tools: [{ type: "code_interpreter" }] }));
  assert.equal(b.status, 400);
  assert.equal(calls.filter((c) => c.url.includes("api.openai.com")).length, 0);
});

test("stream: inoltra text/event-stream", async () => {
  reset({ jarvis: SECRET });
  script = [
    userOk("u-stream"),
    () => new Response("event: response.output_text.delta\ndata: {\"type\":\"response.output_text.delta\",\"delta\":\"Ci\"}\n\n", {
      status: 200,
      headers: { "content-type": "text/event-stream" },
    }),
  ];
  const res = await handler(post({ input: "ciao", stream: true }));
  assert.equal(res.status, 200);
  assert.match(res.headers.get("content-type")!, /text\/event-stream/);
  assert.match(await res.text(), /response\.output_text\.delta/);
});

test("health con secret mancante -> 200 ok:false code secret_missing", async () => {
  reset({});
  script = [userOk("u-h1")];
  const j = await readJson(await handler(post({ action: "health" })));
  assert.equal(j.ok, false);
  assert.equal(j.code, "secret_missing");
  assert.equal(j.checks.auth, "valid");
  assert.equal(j.checks.secret, "missing");
  assert.equal(j.secret_source, null);
});

test("health ok -> richiesta reale a OpenAI, riporta solo il nome del secret", async () => {
  reset({ jarvis: SECRET });
  script = [userOk("u-h2"), () => new Response(JSON.stringify({ id: "resp_h" }), { status: 200 })];
  const j = await readJson(await handler(post({ action: "health" })));
  assert.equal(j.ok, true);
  assert.equal(j.checks.openai, "reachable");
  assert.equal(j.secret_source, "jarvis");
  assert.ok(calls.some((c) => c.url.endsWith("/v1/responses")));
});

test("health con chiave rifiutata -> code openai_key_rejected", async () => {
  reset({ jarvis: SECRET });
  script = [userOk("u-h3"), () => new Response("{}", { status: 401 })];
  const j = await readJson(await handler(post({ action: "health" })));
  assert.equal(j.ok, false);
  assert.equal(j.code, "openai_key_rejected");
  assert.equal(j.checks.openai, "failed");
});

test("GET -> 405 e OPTIONS -> 204", async () => {
  reset({ jarvis: SECRET });
  const g = await handler(new Request("https://f/x", { method: "GET" }));
  assert.equal(g.status, 405);
  const o = await handler(new Request("https://f/x", { method: "OPTIONS" }));
  assert.equal(o.status, 204);
});
