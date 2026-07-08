# Kindle Casero

Convierte una tablet Android vieja en un e-reader dedicado: subís tus PDFs
desde una plataforma web y los leés en la tablet, con sincronización,
subrayado y modos de lectura (claro / oscuro / escala de grises).

## Componentes

| Directorio  | Qué es                                                             |
| ----------- | ------------------------------------------------------------------ |
| `web/`      | Plataforma Next.js + Postgres + Garage (S3) para subir/gestionar PDFs |
| `android/`  | App Android (minSdk 15, Android 4.0.3+) para leer en la tablet     |
| `infra/`    | docker-compose config for Postgres + Garage (local/self-hosted)    |
| `docs/`     | `API.md` — contrato REST compartido entre la web y la app Android  |

## Cómo encajan las piezas

```
   [tablet Android 4]                 [tu servidor]
   ┌─────────────────┐   HTTPS       ┌──────────────────┐
   │  app Android     │ ───────────▶ │  Next.js (web/)   │
   │  (lector, cache  │  /api/...    │  ├─ Postgres      │
   │   local, modos   │ ◀─────────── │  └─ Garage (S3)   │
   │   de lectura)    │   JSON/PDF   └──────────────────┘
   └─────────────────┘
```

1. Subís PDFs desde el navegador (`web/`) — se guardan en Postgres
   (metadata) + Garage (los bytes del PDF).
2. La app Android sincroniza contra `/api/sync` (ver `docs/API.md`),
   descarga los PDFs nuevos, y los deja disponibles offline en la tablet.
3. Subrayados y progreso de lectura se sincronizan en ambas direcciones.

## Puesta en marcha rápida

```sh
./infra/generate-secrets.sh   # genera infra/garage.toml con secretos aleatorios
docker compose up -d          # Postgres + Garage (ver infra/README.md)
cd web
cp .env.example .env          # completar con las credenciales de Garage
npm install
npx prisma migrate deploy
npm run dev
```

Después, compilá e instalá `android/` en la tablet apuntando a la URL de tu
servidor (configurable desde la pantalla de ajustes de la app). Ver
`android/README.md` para el detalle de build (versión de Gradle/AGP,
librería de renderizado de PDF, etc.).

## Documentación

- `docs/API.md` — contrato REST completo (auth, documentos, subrayados,
  progreso, sync incremental).
- `infra/README.md` — cómo levantar Postgres + Garage, bootstrap manual de
  Garage si el script automático falla.
- `web/README.md` — detalle de la plataforma web.
- `android/README.md` — detalle de la app Android y sus restricciones de
  compatibilidad.
