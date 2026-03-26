# ── Stage 1: compile ─────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /build
COPY src/ .
RUN javac -d out *.java

# ── Stage 2: run ──────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=builder /build/out ./out
CMD ["java", "-cp", "out", "Main"]
