# WhatsApp Drive Backup

APK Android para copiar el respaldo local completo de WhatsApp a Google Drive usando el selector de archivos del sistema.

## Qué hace

1. Elegís la carpeta \`WhatsApp/Databases\`.
2. Elegís una carpeta de Google Drive como destino.
3. Copia todos los archivos \`msgstore*\` y \`wa.db\` disponibles a una carpeta con fecha/hora.

No usa la API de Meta, no necesita root y no pide credenciales de WhatsApp.

## Importante

Los archivos \`msgstore.db.crypt14\` / \`msgstore.db.crypt15\` contienen el historial cifrado. Esta primera versión hace una copia masiva segura del historial; no descifra los mensajes.

Para convertir el historial a SQLite/JSON/TXT legible hace falta la clave correspondiente al respaldo de WhatsApp. Una APK común no puede extraer silenciosamente esa clave desde el almacenamiento privado de WhatsApp.

## Rutas habituales

WhatsApp normal:

\`Android/media/com.whatsapp/WhatsApp/Databases\`

WhatsApp Business:

\`Android/media/com.whatsapp.w4b/WhatsApp Business/Databases\`
