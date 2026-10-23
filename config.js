/* TexJAHC · configuración pública de la app web (sus «variables de entorno»).
   Todo lo que está aquí lo puede ver cualquiera que abra la página: NUNCA pongas claves
   ni contraseñas. Los secretos viven en Supabase › Edge Functions › Secrets
   (ANTHROPIC_API_KEY, TEXJAHC_CODE…) y las claves de cada persona en su propio dispositivo.
   Si cambias de servidor, solo cambia CHAT_URL aquí (y TEXJAHC_CHAT_URL en GitHub para Android). */
window.TEXJAHC_CONFIG = Object.freeze({
  // Función del chat con código de acceso (Supabase)
  CHAT_URL: 'https://veznsmmyylsqrodqzcul.supabase.co/functions/v1/chat',
  // Modelos recomendados cuando cada quien usa su propia clave (se pueden cambiar en 🔑)
  MODELS: Object.freeze({ claude: 'claude-sonnet-5-5', openai: 'gpt-6.1-sol', gemini: 'gemini-3.8-flash' }),
});
