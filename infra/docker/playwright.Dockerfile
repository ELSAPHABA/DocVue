FROM mcr.microsoft.com/playwright:v1.58.2-noble@sha256:6446946a1d9fd62d9ae501312a2d76a43ee688542b21622056a372959b65d63d
WORKDIR /workspace
COPY package.json package-lock.json ./
COPY apps/creator-admin/package.json apps/creator-admin/package.json
COPY apps/learner-web/package.json apps/learner-web/package.json
RUN node --version && npm ci --no-audit --no-fund
COPY apps apps
COPY packages packages
COPY scripts/dev.mjs scripts/dev.mjs
COPY playwright.config.ts .
COPY tests/e2e tests/e2e
ENV CI=true
CMD ["npx","playwright","test"]
