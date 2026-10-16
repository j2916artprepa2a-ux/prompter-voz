// TexJAHC · función "chat" (Supabase Edge Function)
// Guarda la clave de Claude en el servidor (secreto ANTHROPIC_API_KEY) y solo
// responde a quien mande el código de acceso correcto (secreto TEXJAHC_CODE).
import "jsr:@supabase/functions-js/edge-runtime.d.ts";

const MODEL = Deno.env.get("TEXJAHC_MODEL") ?? "claude-sonnet-5-5";
const MAX_TURNS = 20;          // últimos mensajes que se mandan como historial
const MAX_CHARS = 8000;        // por mensaje

const CORS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "content-type, x-texjahc-code, authorization, apikey",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};

const SYSTEM = `Eres el asistente de TexJAHC, una app de teleprompter para creadores de contenido en México.
Responde en español de México, claro, útil y completo, en tono cercano.
Usa texto plano: sin markdown, sin asteriscos ni almohadillas (puedes usar guiones para listas).
Si usas información de internet, básate en las fuentes que encontraste.`;

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { ...CORS, "content-type": "application/json" } });
}

type Msg = { role: "user" | "assistant"; content: string };

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: CORS });
  if (req.method !== "POST") return json({ error: "Método no permitido" }, 405);

  const apiKey = Deno.env.get("ANTHROPIC_API_KEY");
  const code = Deno.env.get("TEXJAHC_CODE");
  if (!apiKey || !code) return json({ error: "El servidor aún no tiene configurada la clave de Claude o el código de acceso." }, 503);
  if (req.headers.get("x-texjahc-code") !== code) return json({ error: "Código de acceso incorrecto." }, 401);

  let body: { messages?: Msg[]; search?: boolean; maxTokens?: number };
  try { body = await req.json(); } catch { return json({ error: "Petición inválida." }, 400); }

  const messages = (Array.isArray(body.messages) ? body.messages : [])
    .filter((m) => (m.role === "user" || m.role === "assistant") && typeof m.content === "string" && m.content.trim())
    .slice(-MAX_TURNS)
    .map((m) => ({ role: m.role, content: m.content.slice(0, MAX_CHARS) }));
  while (messages.length && messages[0].role !== "user") messages.shift();
  if (!messages.length || messages[messages.length - 1].role !== "user") return json({ error: "Falta la pregunta." }, 400);

  const tools = body.search ? [{ type: "web_search_20260318", name: "web_search", max_uses: 5 }] : undefined;
  const maxTokens = Math.min(Math.max(Number(body.maxTokens) || 2000, 200), 4000);

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
      return json({ error: friendly }, r.status === 429 ? 429 : 502);
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
  return json({ text: text || "No obtuve respuesta, intenta de nuevo.", sources });
});
