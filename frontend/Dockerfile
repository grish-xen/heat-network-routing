# Build context: the repository root (the bundle imports ../contracts and ../test-data at build time).
FROM node:22-alpine AS build
WORKDIR /build
COPY frontend/package.json frontend/package-lock.json frontend/
RUN npm --prefix frontend ci --no-audit --no-fund
COPY frontend frontend
COPY contracts contracts
COPY test-data/api test-data/api
COPY test-data/synthetic test-data/synthetic
# Real backend over the same origin: nginx proxies /api to the backend service.
ENV VITE_API_MODE=http
RUN npm --prefix frontend run build

FROM nginx:1.27-alpine
COPY frontend/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /build/frontend/dist /usr/share/nginx/html
EXPOSE 80
