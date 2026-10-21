# Seguridad de TexJAHC: auditoría de secretos y migración de la firma Android

> Documento de trabajo. **Nada de esto cambia la firma actual**: los APK se siguen firmando igual
> y se instalan encima de la versión anterior como siempre.

## 1. Lo que se revisó

| Qué | Dónde | Estado |
|---|---|---|
| Llave de firma Android | `android/keystore.jks` | ⚠️ **Pública** en el repositorio desde el commit `988aa66` |
| Contraseñas de la llave | `android/app/build.gradle.kts` (`prompter123`) | ⚠️ **Públicas** en el código |
| Clave de Claude (Anthropic) | `servidor/chat/index.ts` | ✅ Se lee de `ANTHROPIC_API_KEY` en Supabase; nunca se subió |
| Código de acceso del chat | `servidor/chat/index.ts` | ✅ Se lee de `TEXJAHC_CODE` en Supabase; el valor nunca se subió |
| `sk-ant-...` en el historial | `asistente.html` (commits `22b9940`, `454189a`) | ✅ Solo era el texto de ejemplo de un campo, no una clave real |
| Código de acceso en el navegador | `localStorage` del usuario (`pv2_accessCode`) | ✅ Solo en el dispositivo de cada quien |

**Riesgo de la llave pública:** cualquiera puede firmar un APK con el nombre TexJAHC y
Android lo aceptaría como *actualización* de tu app (misma firma), con acceso a los guiones
guardados en ese teléfono. No hay señales de que haya pasado, pero conviene migrar.

## 2. Preparación ya hecha (sin cambiar la firma)

`build.gradle.kts` ahora puede leer la llave y sus contraseñas de variables de entorno:

| Variable | Si no existe, usa |
|---|---|
| `TEXJAHC_KEYSTORE_PATH` | `../keystore.jks` (la actual) |
| `TEXJAHC_KEYSTORE_PASSWORD` | la contraseña actual |
| `TEXJAHC_KEY_ALIAS` | `prompter` |
| `TEXJAHC_KEY_PASSWORD` | la contraseña actual |

Como hoy no existen esas variables, **todo se firma exactamente igual que antes**.

## 3. Procedimiento de migración (cuando lo autorices)

Se usa la **rotación de llaves oficial de Android** (APK Signature Scheme v3, `apksigner rotate`),
que permite cambiar de llave **sin desinstalar** la app en Android 9 o superior.

1. **Crear la llave nueva en tu computadora** (nunca en el repositorio):
   `keytool -genkeypair -keystore texjahc-2026.jks -alias texjahc -keyalg RSA -keysize 4096 -validity 10000`
   Guarda el archivo y la contraseña en un lugar seguro (gestor de contraseñas + respaldo).
2. **Crear el linaje** (prueba de que la llave vieja autoriza a la nueva):
   `apksigner rotate --out lineage.bin --old-signer --ks android/keystore.jks --new-signer --ks texjahc-2026.jks`
3. **Guardar en GitHub › Settings › Secrets** (no en el código): la llave nueva en base64,
   su contraseña, la llave vieja en base64 y el `lineage.bin` en base64.
4. **Cambiar el workflow** para que firme con
   `apksigner sign --ks keystore-viejo.jks --next-signer --ks texjahc-2026.jks --lineage lineage.bin --rotation-min-sdk-version 28 app.apk`
5. **Probar** que el APK nuevo se instala *encima* del actual en un cel Android 9+.
6. **Hasta entonces**, quitar `android/keystore.jks` y las contraseñas del código
   (el historial de Git conservará la copia vieja; la rotación es lo que la deja sin valor).

### Qué pasa con cada teléfono

- **Android 9 o superior:** se actualiza normal, sin perder datos.
- **Android 8.x** (la app soporta desde Android 8): Android usa la firma vieja en esos equipos,
  así que seguirán actualizando, pero sin la protección de la llave nueva.

## 4. Lo que NO se debe hacer

- No cambiar la llave sin linaje: obligaría a desinstalar y se perderían los guiones guardados.
- No subir la llave nueva ni sus contraseñas al repositorio.
- No reescribir el historial de Git sin autorización (no elimina copias ya descargadas).
