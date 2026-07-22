# ── Stage 1: Build ─────────────────────────────────────────────
FROM node:20-alpine AS builder
WORKDIR /app

COPY package.json package-lock.json ./
RUN npm ci

COPY . .
ARG FRONTEND_BUILD_CONFIGURATION=production
RUN npm run build -- --configuration "$FRONTEND_BUILD_CONFIGURATION"

# ── Stage 2: Serve with Nginx ─────────────────────────────────
FROM nginx:alpine
WORKDIR /usr/share/nginx/html

# Remove default Nginx content
RUN rm -rf ./*

# Copy Angular build output (browser bundle)
COPY --from=builder /app/dist/frontend/browser ./
RUN if [ -f index.csr.html ]; then cp index.csr.html index.html; fi \
    && rm -rf dashboard orders products inventory login profile payments

COPY nginx.conf /etc/nginx/conf.d/default.conf

EXPOSE 4200

HEALTHCHECK --interval=30s --timeout=3s \
  CMD wget -q -O /dev/null http://127.0.0.1:4200/ || exit 1

CMD ["nginx", "-g", "daemon off;"]
