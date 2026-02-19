# Pulse (V1 MVP)

Pulse est un agent Java APM local orienté observabilité SQL + HTTP, basé sur OpenTelemetry SDK.

## Build

```bash
./mvnw -DskipTests package
```

Le jar agent à utiliser est:

```bash
target/pulse-0.1.0-SNAPSHOT-agent.jar
```

## Lancer une app avec Pulse

```bash
java -javaagent:target/pulse-0.1.0-SNAPSHOT-agent.jar=port=17321,slowMs=300,slowHttpMs=500,sampleRate=1.0,retentionMs=900000,bind=127.0.0.1 -jar app.jar
```

UI locale:

```text
http://127.0.0.1:17321
```

## Paramètres agent

- `port` (défaut `17321`)
- `slowMs` (défaut `300`)
- `slowHttpMs` (défaut `500`)
- `sampleRate` (défaut `1.0`)
- `retentionMs` (défaut `900000` = 15 min)
- `bind` (défaut `127.0.0.1`)
- `appName` (optionnel, sinon auto-détection depuis `spring.application.name` puis `pom.xml` du projet client en `groupId:artifactId`)

## API

- `GET /api/health`
- `GET /api/sql/config`
- `GET /api/sql/snapshot`
- `GET /api/http/snapshot`
- `GET /api/http/trace/{id}`

## Hot Reload Front (Dev Pulse uniquement)

Lancer Pulse en mode dev:

```bash
./mvnw spring-boot:run
```

Éditer le front ici:

```text
src/main/resources/static/index.html
```

Le rechargement est prévu pour le mode dev local de Pulse (`spring-boot:run`), pas pour l'usage en `-javaagent` côté application cliente.

## Notes V1

- Instrumentation locale via `-javaagent`.
- Spans OpenTelemetry (`SERVER` pour HTTP et `CLIENT` pour DB).
- Attributs standards OpenTelemetry (HTTP/DB) + attributs Pulse pour enrichissement UI.
- Capture SQL via JDBC (`Statement` / `PreparedStatement`) exposée dans la vue SQL.
- Capture des endpoints HTTP Spring Boot exposée dans la vue Endpoint Performance.
- Capture de toutes les requêtes HTTP (rapides et lentes), le seuil `slowHttpMs` ne sert qu'à marquer les appels lents.
- Profiling stack Java par sampling pour appels HTTP lents (détails sur clic).
- SQL normalisé (masquage simples strings/nombres).
- Mémoire bornée + rétention glissante.
- Rafraîchissement UI via polling REST configurable (1s/3s/5s/10s).
