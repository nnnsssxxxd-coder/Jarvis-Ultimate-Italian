// Logica pura della Edge Function jarvis-ai.
// Nessuna API Deno qui dentro: così i test girano sia con `deno test` sia con Node.

export const SECRET_NAMES = ["jarvis", "OPENAI_API_KEY", "Jarvis"] as const;

export type EnvGetter = (name: string) => string | undefined;

/** Toglie spazi, a capo e virgolette: sono la causa classica di "401 incorrect API key". */
export function sanitizeKey(raw: string | undefined | null): string {
  if (!raw) return "";
  let k = raw.trim();
  if (
    (k.startsWith('"') && k.endsWith('"')) ||
    (k.startsWith("'") && k.endsWith("'"))
  ) {
    k = k.slice(1, -1).trim();
  }
  return k;
}

/**
 * Equivale a:
 *   Deno.env.get("jarvis") ?? Deno.env.get("OPENAI_API_KEY") ?? Deno.env.get("Jarvis")
 * ma ignora anche i valori vuoti. Restituisce solo il NOME della sorgente, mai loggare la chiave.
 */
export function resolveApiKey(get: EnvGetter): { key: string; source: string } | null {
  for (const name of SECRET_NAMES) {
    const key = sanitizeKey(get(name));
    if (key.length > 0) return { key, source: name };
  }
  return null;
}

export type ErrorCode =
  | "bad_request"
  | "payload_too_large"
  | "method_not_allowed"
  | "auth_invalid"
  | "auth_unavailable"
  | "forbidden"
  | "rate_limited"
  | "quota_exceeded"
  | "secret_missing"
  | "openai_key_rejected"
  | "model_not_found"
  | "openai_unreachable"
  | "openai_timeout"
  | "openai_unavailable"
  | "upstream_bad_request"
  | "server_misconfigured"
  | "internal_error";

export interface ApiError {
  code: ErrorCode;
  message: string;
  status: number;
  retryable: boolean;
  retryAfterMs?: number;
}

const TABLE: Record<ErrorCode, { status: number; retryable: boolean; message: string }> = {
  bad_request: { status: 400, retryable: false, message: "Richiesta non valida." },
  payload_too_large: { status: 413, retryable: false, message: "Richiesta troppo grande." },
  method_not_allowed: { status: 405, retryable: false, message: "Metodo non consentito: usa POST." },
  auth_invalid: { status: 401, retryable: false, message: "Autenticazione non valida o scaduta." },
  auth_unavailable: { status: 502, retryable: true, message: "Servizio di autenticazione Supabase non raggiungibile." },
  forbidden: { status: 403, retryable: false, message: "Operazione non consentita per questo utente." },
  rate_limited: { status: 429, retryable: true, message: "Troppe richieste: riprova tra poco." },
  quota_exceeded: { status: 429, retryable: false, message: "Quota o credito OpenAI esaurito." },
  secret_missing: {
    status: 500,
    retryable: false,
    message: "Secret OpenAI non configurato: crea il secret 'jarvis' nei Secrets di Supabase.",
  },
  openai_key_rejected: { status: 500, retryable: false, message: "OpenAI ha rifiutato la chiave configurata sul server." },
  model_not_found: { status: 500, retryable: false, message: "Modello OpenAI non disponibile per questa chiave." },
  openai_unreachable: { status: 502, retryable: true, message: "OpenAI non raggiungibile dal server." },
  openai_timeout: { status: 504, retryable: true, message: "OpenAI non ha risposto in tempo." },
  openai_unavailable: { status: 502, retryable: true, message: "OpenAI temporaneamente non disponibile." },
  upstream_bad_request: { status: 400, retryable: false, message: "OpenAI ha rifiutato la richiesta." },
  server_misconfigured: { status: 500, retryable: false, message: "Configurazione del server incompleta." },
  internal_error: { status: 500, retryable: false, message: "Errore interno della funzione." },
};

export function makeError(
  code: ErrorCode,
  extra: { message?: string; retryAfterMs?: number } = {},
): ApiError {
  const t = TABLE[code];
  return {
    code,
    status: t.status,
    retryable: t.retryable,
    message: extra.message ?? t.message,
    retryAfterMs: extra.retryAfterMs,
  };
}

/** Nei messaggi OpenAI per chiave errata compare un pezzo della chiave: va sempre oscurato. */
export function redactSecrets(text: string): string {
  return text
    .replace(/sk-[A-Za-z0-9_\-*.]{4,}/g, "[chiave-oscurata]")
    .replace(/Bearer\s+[A-Za-z0-9._\-]+/gi, "Bearer [oscurato]");
}

export function parseRetryAfter(header: string | null | undefined, now = Date.now()): number | undefined {
  if (!header) return undefined;
  const secs = Number(header);
  if (Number.isFinite(secs) && secs >= 0) return Math.round(secs * 1000);
  const date = Date.parse(header);
  if (Number.isFinite(date)) return Math.max(0, date - now);
  return undefined;
}

/** Traduce uno status HTTP di OpenAI in un errore tipizzato. Mai inoltrare il messaggio del 401. */
export function classifyUpstream(
  status: number,
  bodyText: string,
  retryAfterHeader?: string | null,
): ApiError {
  let type = "";
  let code = "";
  let message = "";
  try {
    const parsed = JSON.parse(bodyText);
    type = String(parsed?.error?.type ?? "");
    code = String(parsed?.error?.code ?? "");
    message = String(parsed?.error?.message ?? "");
  } catch {
    // corpo non JSON: si usa solo lo status
  }
  const safe = redactSecrets(message).slice(0, 300);
  const retryAfterMs = parseRetryAfter(retryAfterHeader);

  if (status === 401) return makeError("openai_key_rejected");
  if (status === 403) {
    return makeError("openai_key_rejected", {
      message: "OpenAI ha negato l'accesso (403): controlla progetto, permessi e paese della chiave.",
    });
  }
  if (status === 404) return makeError("model_not_found");
  if (status === 408) return makeError("openai_timeout");
  if (status === 429) {
    if (type.includes("insufficient_quota") || code.includes("insufficient_quota")) {
      return makeError("quota_exceeded");
    }
    return makeError("rate_limited", { retryAfterMs });
  }
  if (status >= 500) return makeError("openai_unavailable", { retryAfterMs });
  return makeError("upstream_bad_request", { message: safe || undefined });
}

/** Attesa prima di un nuovo tentativo: esponenziale con jitter, mai oltre `cap`. */
export function backoffDelay(
  attempt: number,
  retryAfterMs?: number,
  random: () => number = Math.random,
  base = 400,
  cap = 4000,
): number {
  if (retryAfterMs !== undefined) return Math.min(retryAfterMs, 5000);
  const exp = Math.min(cap, base * 2 ** attempt);
  return Math.round(exp / 2 + random() * (exp / 2));
}

/** Un retry dentro la funzione ha senso solo se l'attesa richiesta è breve. */
export function shouldRetry(err: ApiError, attempt: number, maxRetries: number): boolean {
  if (!err.retryable || attempt >= maxRetries) return false;
  if (err.retryAfterMs !== undefined && err.retryAfterMs > 5000) return false;
  return true;
}

// ---------- Validazione richiesta ----------

export const ALLOWED_TOOL_TYPES = ["function", "web_search", "web_search_preview"];

export interface ChatRequest {
  input: string | Record<string, unknown>[];
  instructions?: string;
  previous_response_id?: string;
  tools?: Record<string, unknown>[];
  tool_choice?: string | Record<string, unknown>;
  stream: boolean;
  model?: string;
  max_output_tokens?: number;
  store: boolean;
}

export type Validation<T> = { ok: true; value: T } | { ok: false; message: string };

const isObj = (v: unknown): v is Record<string, unknown> =>
  typeof v === "object" && v !== null && !Array.isArray(v);

export function validateChatRequest(
  body: unknown,
  opts: { allowedModels: string[] } = { allowedModels: [] },
): Validation<ChatRequest> {
  if (!isObj(body)) return { ok: false, message: "Il corpo deve essere un oggetto JSON." };

  const { input } = body;
  if (typeof input === "string") {
    if (input.length === 0 || input.length > 100_000) {
      return { ok: false, message: "'input' deve avere da 1 a 100000 caratteri." };
    }
  } else if (Array.isArray(input)) {
    if (input.length === 0 || input.length > 200) {
      return { ok: false, message: "'input' deve contenere da 1 a 200 elementi." };
    }
    if (!input.every(isObj)) return { ok: false, message: "Ogni elemento di 'input' deve essere un oggetto." };
  } else {
    return { ok: false, message: "'input' è obbligatorio (stringa o array)." };
  }

  const out: ChatRequest = {
    input: input as ChatRequest["input"],
    stream: body.stream === true,
    store: body.store === true,
  };

  if (body.instructions !== undefined) {
    if (typeof body.instructions !== "string" || body.instructions.length > 20_000) {
      return { ok: false, message: "'instructions' deve essere una stringa fino a 20000 caratteri." };
    }    out.instructions = body.instructions;
  }

  if (body.previous_response_id !== undefined) {
    const id = body.previous_response_id;
    if (typeof id !== "string" || !/^resp_[A-Za-z0-9_-]{1,200}$/.test(id)) {
      return { ok: false, message: "'previous_response_id' non valido." };
    }
    out.previous_response_id = id;
  }

  if (body.tools !== undefined) {
    if (!Array.isArray(body.tools) || body.tools.length > 32) {
      return { ok: false, message: "'tools' deve essere un array di massimo 32 elementi." };
    }
    for (const t of body.tools) {
      if (!isObj(t) || typeof t.type !== "string" || !ALLOWED_TOOL_TYPES.includes(t.type)) {
        return {
          ok: false,
          message: `Tipo di strumento non consentito. Ammessi: ${ALLOWED_TOOL_TYPES.join(", ")}.`,
        };
      }
    }
    out.tools = body.tools as Record<string, unknown>[];
  }

  if (body.tool_choice !== undefined) {
    const tc = body.tool_choice;
    const okString = typeof tc === "string" && ["auto", "none", "required"].includes(tc);
    const okObj = isObj(tc) && tc.type === "function";
    if (!okString && !okObj) return { ok: false, message: "'tool_choice' non valido." };
    out.tool_choice = tc as ChatRequest["tool_choice"];
  }

  if (body.model !== undefined) {
    if (typeof body.model !== "string" || !opts.allowedModels.includes(body.model)) {
      return { ok: false, message: "Modello non consentito." };
    }
    out.model = body.model;
  }

  if (body.max_output_tokens !== undefined) {
    const n = body.max_output_tokens;
    if (typeof n !== "number" || !Number.isInteger(n) || n < 16) {
      return { ok: false, message: "'max_output_tokens' deve essere un intero >= 16." };
    }
    out.max_output_tokens = Math.min(n, 8192);
  }

  return { ok: true, value: out };
}

export function buildOpenAiPayload(req: ChatRequest, model: string): Record<string, unknown> {
  const payload: Record<string, unknown> = {
    model,
    input: req.input,
    stream: req.stream,
    store: req.store,
  };
  if (req.instructions !== undefined) payload.instructions = req.instructions;
  if (req.previous_response_id !== undefined) payload.previous_response_id = req.previous_response_id;
  if (req.tools !== undefined && req.tools.length > 0) payload.tools = req.tools;
  if (req.tool_choice !== undefined) payload.tool_choice = req.tool_choice;
  if (req.max_output_tokens !== undefined) payload.max_output_tokens = req.max_output_tokens;
  return payload;
}

// ---------- Rate limit (best effort, per isolate) ----------

export class RateLimiter {
  private hits = new Map<string, number[]>();
  private limit: number;
  private windowMs: number;

  constructor(limit: number, windowMs: number) {
    this.limit = limit;
    this.windowMs = windowMs;
  }

  check(key: string, now = Date.now()): { allowed: boolean; retryAfterMs: number } {
    const arr = (this.hits.get(key) ?? []).filter((t) => now - t < this.windowMs);
    if (arr.length >= this.limit) {
      this.hits.set(key, arr);
      return { allowed: false, retryAfterMs: Math.max(1, arr[0] + this.windowMs - now) };
    }
    arr.push(now);
    this.hits.set(key, arr);
    if (this.hits.size > 5000) {
      for (const [k, v] of this.hits) {
        if (v.every((t) => now - t >= this.windowMs)) this.hits.delete(k);
      }
    }
    return { allowed: true, retryAfterMs: 0 };
  }
}
