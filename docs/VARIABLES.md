# TexJAHC: variables de entorno, CORS y RLS

## Servidor del chat (Supabase › Edge Functions › Secrets)
Estas son secretas: viven solo en Supabase, nunca en el repositorio ni en la app.

| Variable | ¿Obligatoria? | Para qué |
|---|---|---|
| `ANTHROPIC_API_KEY` | Sí | Clave de Claude del servidor |
| `TEXJAHC_CODE` | Sí | Código de acceso que escribe la gente en la app |
| `TEXJAHC_MODEL` | No | Modelo (por defecto `claude-sonnet-5-5`) |
| `ALLOWED_ORIGINS` | No | Sitios que pueden usar el chat desde el navegador, separados por coma. Por defecto: `https://j2916artprepa2a-ux.github.io`, `http://localhost:8000`, `http://127.0.0.1:8000` |
| `TEXJAHC_MAX_TOKENS` | No | Tope de largo de respuesta (por defecto 4000, máximo 8000) |

## CORS (función `chat`)
- Antes respondía a **cualquier sitio** (`*`). Ahora solo a los de `ALLOWED_ORIGINS`.
- Un navegador desde otro sitio recibe **403** antes de tocar la IA.
- La app Android nativa no es navegador (no manda `Origin`): se le permite, y siempre pide el código.
- Encabezados permitidos: `content-type`, `x-texjahc-code`. Métodos: `POST`, `OPTIONS`.
- CORS no sustituye al código de acceso: el código sigue siendo obligatorio.

## App web (`config.js`)
Una página estática no tiene variables secretas: todo lo que llega al navegador es público.
`config.js` reúne la configuración pública (dirección del chat y modelos recomendados) en un solo lugar.
**Nunca pongas claves ahí.** Las claves de cada persona se guardan solo en su dispositivo (🔑).

## App Android (GitHub › Settings › Secrets and variables › Actions › **Variables**)
Se leen al compilar (`BuildConfig`). Si no existen, se usan las de siempre.

| Variable | Para qué |
|---|---|
| `TEXJAHC_WEB_URL` | Página de TexJAHC que abre la app (termina en `/`) |
| `TEXJAHC_CHAT_URL` | Dirección de la función del chat |

## RLS (Row Level Security)
- La app **no guarda datos en la base de datos**: guiones, claves y videos se quedan en cada dispositivo.
- Las tablas internas de Supabase ya tienen RLS activado y el revisor de seguridad no marca problemas.
- `servidor/sql/rls_auto_enable.sql`: hace que **cualquier tabla nueva** nazca con RLS. Pendiente de aplicar.
- Si algún día se guardan datos (por ejemplo guiones en la nube), cada tabla necesita su política, por ejemplo:
  `create policy "solo el dueño" on public.guiones for all using (auth.uid() = user_id) with check (auth.uid() = user_id);`
