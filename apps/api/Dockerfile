# Multi-stage so the runtime image carries no TypeScript, no test deps and no
# source — just compiled JS, production node_modules and the Prisma engines.
FROM node:22-alpine AS build
WORKDIR /app

RUN apk add --no-cache openssl

# Copy manifests first so the dependency layer is cached across source changes.
COPY package.json package-lock.json ./
COPY packages/shared/package.json packages/shared/
COPY apps/api/package.json apps/api/
RUN npm ci

COPY packages/shared packages/shared
COPY apps/api apps/api
RUN npm run build --workspace @snaptab/shared \
 && npm run build --workspace @snaptab/api

# Drop dev dependencies, then re-generate the Prisma client the runtime needs.
RUN npm prune --omit=dev \
 && npx --workspace @snaptab/api prisma generate

FROM node:22-alpine AS runtime
WORKDIR /app
ENV NODE_ENV=production

RUN apk add --no-cache openssl \
 && addgroup -S snaptab && adduser -S snaptab -G snaptab

COPY --from=build --chown=snaptab:snaptab /app/node_modules ./node_modules
COPY --from=build --chown=snaptab:snaptab /app/package.json ./package.json
COPY --from=build --chown=snaptab:snaptab /app/packages/shared/dist ./packages/shared/dist
COPY --from=build --chown=snaptab:snaptab /app/packages/shared/data ./packages/shared/data
COPY --from=build --chown=snaptab:snaptab /app/packages/shared/package.json ./packages/shared/package.json
COPY --from=build --chown=snaptab:snaptab /app/apps/api/dist ./apps/api/dist
COPY --from=build --chown=snaptab:snaptab /app/apps/api/prisma ./apps/api/prisma
COPY --from=build --chown=snaptab:snaptab /app/apps/api/package.json ./apps/api/package.json

RUN mkdir -p /data/storage && chown -R snaptab:snaptab /data
USER snaptab

EXPOSE 4000

# PORT is read rather than hardcoded: Cloud Run injects 8080, App Runner 8080, Fargate
# whatever the task definition says. The app already honours it, and a health check that
# did not would report a healthy container as dead.
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s \
  CMD node -e "const p=process.env.PORT||4000;fetch('http://127.0.0.1:'+p+'/healthz').then(r=>process.exit(r.ok?0:1)).catch(()=>process.exit(1))"

WORKDIR /app/apps/api

# The API. The worker is the same image with a different command — see docs/DEPLOY.md:
#   node dist/workers/index.js
# Migrations are deliberately NOT run here. Every starting instance would race the others,
# and a rollback would have already migrated the schema forward. They run once per deploy,
# as their own step.
CMD ["node", "dist/index.js"]
