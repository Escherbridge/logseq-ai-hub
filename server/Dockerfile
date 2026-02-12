FROM oven/bun:1 AS base
WORKDIR /app

COPY package.json bun.lockb* ./
RUN bun install --frozen-lockfile --production

COPY src/ src/

ENV NODE_ENV=production
EXPOSE 3000

CMD ["bun", "run", "src/index.ts"]
