// TexJAHC · función "chat" (Supabase Edge Function)
// Guarda la clave de Claude en el servidor (secreto ANTHROPIC_API_KEY) y solo
// responde a quien mande el código de acceso correcto (secreto TEXJAHC_CODE).
//
// Variables de entorno (Supabase › Edge Functions › Secrets). Nunca van en el código:
//   ANTHROPIC_API_KEY   (obligatoria) clave de Claude
//   TEXJAHC_CODE        (obligatoria) código de acceso que escribe la gente en la app
//   TEXJAHC_MODEL       (opcional) modelo; por defecto claude-sonnet-5-5
//   ALLOWED_ORIGINS     (opcional) sitios que pueden llamar desde el navegador, separados por coma.
//                       Por defecto: la página de TexJAHC en GitHub Pages y localhost para pruebas.
//   TEXJAHC_MAX_TOKENS  (opcional) tope de largo de cada respuesta; por defecto 4000
import "jsr:@supabase/functions-js/edge-runtime.d.ts";

const MODEL = Deno.env.get("TEXJAHC_MODEL") ?? "claude-sonnet-5-5";
const MAX_TURNS = 20;          // últimos mensajes que se mandan como historial
const MAX_CHARS = 8000;        // por mensaje
const MAX_TOKENS = Math.min(Math.max(Number(Deno.env.get("TEXJAHC_MAX_TOKENS")) || 4000, 200), 8000);

// CORS: solo la app de TexJAHC puede llamar desde un navegador (antes era «*», cualquier sitio).
// La app Android nativa no manda «Origin» (no es navegador): se le permite y sigue pidiendo el código.
const ALLOWED = (Deno.env.get("ALLOWED_ORIGINS") ??
  "https://j2916artprepa2a-ux.github.io,http://localhost:8000,http://127.0.0.1:8000")
  .split(",").map((o) => o.trim().replace(/\/+$/, "")).filter(Boolean);

function cors(origin: string | null): Record<string, string> {
  const h: Record<string, string> = {
    "Access-Control-Allow-Headers": "content-type, x-texjahc-code",
    "Access-Control-Allow-Methods": "POST, OPTIONS",
    "Access-Control-Max-Age": "86400",
    "Vary": "Origin",
  };
  if (origin && ALLOWED.includes(origin)) h["Access-Control-Allow-Origin"] = origin;
  return h;
}

const SYSTEM = `Eres el asistente de TexJAHC, una app de teleprompter para creadores de contenido en México.
Responde en español de México, claro, útil y completo, en tono cercano.
Usa texto plano: sin markdown, sin asteriscos ni almohadillas (puedes usar guiones para listas).
Si usas información de internet, básate en las fuentes que encontraste.`;

function json(body: unknown, status = 200, headers: Record<string, string> = cors(null)) {
  return new Response(JSON.stringify(body), { status, headers: { ...headers, "content-type": "application/json" } });
}

type Msg = { role: "user" | "assistant"; content: string };

Deno.serve(async (req) => {
  const origin = req.headers.get("origin");
  const CORS = cors(origin);   // cada respuesta con sus propios encabezados
  const J = (b: unknown, st = 200) => json(b, st, CORS);
  // Un navegador desde otro sitio: se rechaza antes de tocar la IA
  if (origin && !ALLOWED.includes(origin)) return J({ error: "Este sitio no tiene permiso para usar el chat de TexJAHC." }, 403);
  if (req.method === "OPTIONS") return new Response(null, { status: 204, headers: CORS });
  if (req.method !== "POST") return J({ error: "Método no permitido" }, 405);

  const apiKey = Deno.env.get("ANTHROPIC_API_KEY");
  const code = Deno.env.get("TEXJAHC_CODE");
  if (!apiKey || !code) return J({ error: "El servidor aún no tiene configurada la clave de Claude o el código de acceso." }, 503);
  if (req.headers.get("x-texjahc-code") !== code) return J({ error: "Código de acceso incorrecto." }, 401);

  let body: { messages?: Msg[]; search?: boolean; maxTokens?: number };
  try { body = await req.json(); } catch { return J({ error: "Petición inválida." }, 400); }

  const messages = (Array.isArray(body.messages) ? body.messages : [])
    .filter((m) => (m.role === "user" || m.role === "assistant") && typeof m.content === "string" && m.content.trim())
    .slice(-MAX_TURNS)
    .map((m) => ({ role: m.role, content: m.content.slice(0, MAX_CHARS) }));
  while (messages.length && messages[0].role !== "user") messages.shift();
  if (!messages.length || messages[messages.length - 1].role !== "user") return J({ error: "Falta la pregunta." }, 400);

  const tools = body.search ? [{ type: "web_search_20260318", name: "web_search", max_uses: 5 }] : undefined;
  const maxTokens = Math.min(Math.max(Number(body.maxTokens) || 2000, 200), MAX_TOKENS);

  // deno-lint-ignore no-explicit-any
  const convo: any[] = [...messages];
  // deno-lint-ignore no-explicit-any
  let content: any[] = [];
  for (let i = 0; i < 4; i++) {
    const r = await fetch("https://api.anthropic.com/v1/messages", {
      method: "POST",
      headers: { "content-type": "application/json", "x-api-key": apiKey, "anthropic-version": "2023-06-01" },
      body: JSON.stringify({ model: MODEL, max_tokens: maxTokens, system: SYSTEM, messages: convo, ...(tools ? { tools } : {}) }),
    });
    const j = await r.json().catch(() => ({}));
    if (!r.ok) {
      const m: string = j?.error?.message ?? `Error ${r.status}`;
      const friendly = r.status === 401 ? "La clave de Claude del servidor no es válida."
        : /credit|balance/i.test(m) ? "La cuenta de Claude API no tiene saldo. Recarga en console.anthropic.com."
        : r.status === 429 ? "Demasiadas preguntas seguidas. Espera unos segundos."
        : "La IA no pudo responder: " + m;
      return J({ error: friendly }, r.status === 429 ? 429 : 502);
    }
    content = content.concat(j.content ?? []);
    if (j.stop_reason !== "pause_turn") break;
    // Búsqueda larga en pausa: se continúa mandando el turno tal cual.
    convo.push({ role: "assistant", content: j.content });
  }

  const text = content.filter((c) => c.type === "text").map((c) => c.text).join("").trim();
  const seen = new Set<string>();
  const sources: { url: string; title: string }[] = [];
  for (const c of content) {
    for (const cit of c.citations ?? []) {
      if (cit.url && !seen.has(cit.url)) { seen.add(cit.url); sources.push({ url: cit.url, title: cit.title || cit.url }); }
    }
  }
  return J({ text: text || "No obtuve respuesta, intenta de nuevo.", sources });
});
