// Edge Function: jarvis-ai
// Android -> (JWT Supabase) -> questa funzione -> OpenAI Responses API.
// La chiave OpenAI vive SOLO qui, nel secret "jarvis" (fallback: OPENAI_API_KEY, Jarvis).
//
// Nessun import esterno: un import che fallisce provoca BOOT_ERROR e quindi un 503 della piattaforma.
// Nessuna lettura di variabili d'ambiente a livello di modulo, per lo stesso motivo.

import {
  type ApiError,
  type EnvGetter,
  type ErrorCode,
  RateLimiter,
  backoffDelay,
  buildOpenAiPayload,
  classifyUpstream,
  makeError,
  redactSecrets,
  resolveApiKey,
  shouldRetry,
  validateChatRequest,
} from "./lib.ts";

const FUNCTION_VERSION = "jarvis-ai/2026-11-19.1";
const OPENAI_BASE = "https://api.openai.com/v1";
const DEFAULT_MODEL_FALLBACK = "gpt-4.1-mini";
const MAX_BODY_BYTES = 6_000_000; // immagini in base64 incluse
const limiter = new RateLimiter(30, 60_000);

const env: EnvGetter = (name) => {
  try {
    return Deno.env.get(name) ?? undefined;
  } catch {
    return undefined;
  }
};

const CORS: Record<string, string> = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
  "Access-Control-Expose-Headers": "x-jarvis-request-id, x-jarvis-version, x-jarvis-latency-ms",
};

const sleep = (ms: number) => new Promise<void>((r) => setTimeout(r, ms));

function defaultModel(): string {
  return env("JARVIS_MODEL")?.trim() || DEFAULT_MODEL_FALLBACK;
}

function allowedModels(): string[] {
  const extra = (env("JARVIS_ALLOWED_MODELS") ?? "").split(",").map((s) => s.trim()).filter(Boolean);
  return Array.from(new Set([defaultModel(), ...extra]));
}

function withTimeout<T>(p: Promise<T>, ms: number): Promise<T> {
  return new Promise<T>((resolve, reject) => {
    const t = setTimeout(() => reject(new DOMException("timeout", "AbortError")), ms);
    p.then((v) => { clearTimeout(t); resolve(v); }, (e) => { clearTimeout(t); reject(e); });
  });
}

type Auth =
  | { ok: true; userId: string; anonymous: boolean }
  | { ok: false; error: ApiError };

/** Verifica il JWT chiedendo l'utente a GoTrue: funziona anche con le nuove chiavi asimmetriche. */
async function verifyUser(req: Request): Promise<Auth> {
  const m = /^Bearer\s+(.+)$/i.exec(req.headers.get("authorization") ?? "");
  if (!m) return { ok: false, error: makeError("auth_invalid", { message: "Token di accesso mancante." }) };

  const url = env("SUPABASE_URL");
  const anon = env("SUPABASE_ANON_KEY");
  if (!url || !anon) {
    return {
      ok: false,
      error: makeError("server_misconfigured", {
        message: "SUPABASE_URL o SUPABASE_ANON_KEY non disponibili nella funzione.",
      }),
    };
  }

  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), 8000);
  try {
    const res = await fetch(`${url}/auth/v1/user`, {
      headers: { Authorization: `Bearer ${m[1]}`, apikey: anon },
      signal: ctrl.signal,
    });
    if (res.status === 401 || res.status === 403) {
      await res.body?.cancel();
      return { ok: false, error: makeError("auth_invalid") };
    }
    if (!res.ok) {
      await res.body?.cancel();
      return { ok: false, error: makeError("auth_unavailable") };
    }
    const user = await res.json();
    if (!user?.id) return { ok: false, error: makeError("auth_invalid") };
    return { ok: true, userId: String(user.id), anonymous: user.is_anonymous === true };
  } catch {
    return { ok: false, error: makeError("auth_unavailable") };
  } finally {
    clearTimeout(timer);
  }
}

type Upstream =
  | { ok: true; res: Response; attempts: number }
  | { ok: false; error: ApiError; attempts: number };

/** Chiama OpenAI con timeout e retry limitati (solo su errori transitori). Niente retry infiniti. */
async function fetchUpstream(
  apiKey: string,
  path: string,
  init: { method: "GET" | "POST"; body?: unknown },
  opt: { timeoutMs: number; maxRetries: number },
): Promise<Upstream> {
  let attempt = 0;
  while (true) {
    let err: ApiError;
    const ctrl = new AbortController();
    const timer = setTimeout(() => ctrl.abort(), opt.timeoutMs);
    try {
      const res = await fetch(OPENAI_BASE + path, {
        method: init.method,
        headers: { Authorization: `Bearer ${apiKey}`, "Content-Type": "application/json" },
        body: init.body === undefined ? undefined : JSON.stringify(init.body),
        signal: ctrl.signal,
      });
      clearTimeout(timer);
      if (res.ok) return { ok: true, res, attempts: attempt + 1 };
      const text = await res.text().catch(() => "");
      err = classifyUpstream(res.status, text, res.headers.get("retry-after"));
    } catch (e) {
      clearTimeout(timer);
      err = (e as Error)?.name === "AbortError" ? makeError("openai_timeout") : makeError("openai_unreachable");
    }
    if (!shouldRetry(err, attempt, opt.maxRetries)) return { ok: false, error: err, attempts: attempt + 1 };
    await sleep(backoffDelay(attempt, err.retryAfterMs));
    attempt++;
  }
}

function json(body: unknown, status: number, headers: Record<string, string>): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...headers, "Content-Type": "application/json; charset=utf-8", "Cache-Control": "no-store" },
  });
}

Deno.serve(async (req: Request): Promise<Response> => {
  const requestId = crypto.randomUUID();
  const started = performance.now();
  const baseHeaders: Record<string, string> = {
    ...CORS,
    "x-jarvis-request-id": requestId,
    "x-jarvis-version": FUNCTION_VERSION,
  };
  const latency = () => Math.round(performance.now() - started);

  const fail = (e: ApiError): Response => {
    console.log(JSON.stringify({ evt: "error", rid: requestId, code: e.code, status: e.status, ms: latency() }));
    const headers: Record<string, string> = { ...baseHeaders, "x-jarvis-latency-ms": String(latency()) };
    if (e.retryAfterMs !== undefined) headers["Retry-After"] = String(Math.ceil(e.retryAfterMs / 1000));
    return json(
      {
        ok: false,
        error: {
          code: e.code,
          message: e.message,
          retryable: e.retryable,
          retry_after_ms: e.retryAfterMs ?? null,
          request_id: requestId,
        },
      },
      e.status,
      headers,
    );
  };

  try {
    if (req.method === "OPTIONS") return new Response(null, { status: 204, headers: baseHeaders });
    if (req.method !== "POST") return fail(makeError("method_not_allowed"));

    const auth = await verifyUser(req);
    if (!auth.ok) return fail(auth.error);

    const rl = limiter.check(auth.userId);
    if (!rl.allowed) return fail(makeError("rate_limited", { retryAfterMs: rl.retryAfterMs }));

    const declared = Number(req.headers.get("content-length") ?? "0");
    if (declared > MAX_BODY_BYTES) return fail(makeError("payload_too_large"));
    const raw = await req.text();
    if (raw.length > MAX_BODY_BYTES) return fail(makeError("payload_too_large"));

    let body: Record<string, unknown>;
    try {
      body = JSON.parse(raw);
    } catch {
      return fail(makeError("bad_request", { message: "Il corpo non è JSON valido." }));
    }

    const action = typeof body?.action === "string" ? body.action : "chat";
    if (action !== "chat" && action !== "health") {
      return fail(makeError("bad_request", { message: "Azione sconosciuta." }));
    }

    const secret = resolveApiKey(env);
    const model = defaultModel();

    // ---------------- HEALTH: chiamata reale a OpenAI ----------------
    if (action === "health") {
      const deep = body.deep !== false;
      const checks = {
        auth: "valid",
        secret: secret ? "present" : "missing",
        openai: "skipped",
      };
      let code: ErrorCode | "ok" = "ok";
      let message = "Connessione riuscita.";
      let openaiMs: number | null = null;

      if (!secret) {
        code = "secret_missing";
        message = makeError("secret_missing").message;
      } else {
        const t0 = performance.now();
        const r = deep
          ? await fetchUpstream(secret.key, "/responses", {
              method: "POST",
              body: { model, input: "ping", max_output_tokens: 16, store: false },
            }, { timeoutMs: 15_000, maxRetries: 0 })
          : await fetchUpstream(secret.key, `/models/${encodeURIComponent(model)}`, { method: "GET" }, {
              timeoutMs: 10_000,
              maxRetries: 0,
            });
        openaiMs = Math.round(performance.now() - t0);
        if (r.ok) {
          await r.res.body?.cancel();
          checks.openai = "reachable";
        } else {
          checks.openai = "failed";
          code = r.error.code;
          message = r.error.message;
        }
      }

      return json(
        {
          ok: code === "ok",
          code,
          message,
          checks,
          secret_source: secret?.source ?? null, // solo il NOME della variabile, mai il valore
          model,
          openai_latency_ms: openaiMs,
          function_version: FUNCTION_VERSION,
          anonymous_user: auth.anonymous,
          server_time: new Date().toISOString(),
        },
        200,
        { ...baseHeaders, "x-jarvis-latency-ms": String(latency()) },
      );
    }

    // ---------------- CHAT ----------------
    if (!secret) return fail(makeError("secret_missing"));

    const v = validateChatRequest(body, { allowedModels: allowedModels() });
    if (!v.ok) return fail(makeError("bad_request", { message: v.message }));
    const chatReq = v.value;
    const usedModel = chatReq.model ?? model;
    const payload = buildOpenAiPayload(chatReq, usedModel);

    const up = await fetchUpstream(secret.key, "/responses", { method: "POST", body: payload }, {
      timeoutMs: 45_000, // tempo massimo per ricevere gli header (primo byte)
      maxRetries: 2,
    });
    if (!up.ok) return fail(up.error);

    console.log(JSON.stringify({
      evt: "chat",
      rid: requestId,
      user: auth.userId.slice(0, 8),
      stream: chatReq.stream,
      model: usedModel,
      attempts: up.attempts,
      ttfb_ms: latency(),
    }));

    if (chatReq.stream) {
      return new Response(up.res.body, {
        status: 200,
        headers: {
          ...baseHeaders,
          "Content-Type": "text/event-stream; charset=utf-8",
          "Cache-Control": "no-cache, no-transform",
          "x-accel-buffering": "no",
          "x-jarvis-latency-ms": String(latency()),
        },
      });
    }

    const data = await withTimeout(up.res.json(), 60_000);
    return json(
      {
        ok: true,
        data,
        meta: { request_id: requestId, latency_ms: latency(), model: usedModel, attempts: up.attempts, version: FUNCTION_VERSION },
      },
      200,
      { ...baseHeaders, "x-jarvis-latency-ms": String(latency()) },
    );
  } catch (e) {
    const msg = redactSecrets(String((e as Error)?.message ?? e)).slice(0, 200);
    console.error(JSON.stringify({ evt: "unhandled", rid: requestId, name: (e as Error)?.name, msg }));
    return fail(makeError("internal_error"));
  }
});
