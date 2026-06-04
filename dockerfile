
# ─── Stage 1: Build ───────────────────────────────────────────────────────────
FROM gradle:8.11-jdk21 AS build

WORKDIR /app

# Copia os arquivos de configuração do Gradle primeiro (melhor uso de cache)
COPY build.gradle.kts settings.gradle.kts gradle.properties* ./
COPY gradle/ gradle/

# Baixa dependências (camada cacheável separada)
RUN gradle dependencies --no-daemon || true

# Copia o código-fonte
COPY src/ src/

# Gera o fat JAR (shadowJar via plugin Ktor)
RUN gradle buildFatJar --no-daemon

# ─── Stage 2: Runtime ─────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# Copia apenas o JAR gerado
COPY --from=build /app/build/libs/*-all.jar app.jar

# Porta exposta (deve bater com a do application.yaml)
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
