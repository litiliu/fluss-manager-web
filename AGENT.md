# AGENT.md

## Project Overview

Fluss Manager Web is an open source Spring Boot web UI for operating Apache Fluss clusters.
It supports administrator login, multi-cluster management, metadata exploration, table preview, primary-key lookup, and basic database/table management.

## Tech Stack

- Java 17
- Spring Boot 3.3.7
- Spring Security session authentication
- Maven
- Apache Fluss Java Client `0.9.1-incubating`
- Static frontend: plain HTML/CSS/JavaScript
- Docker with Eclipse Temurin 17 JRE

There is no JavaScript framework or frontend build step.

## Important Behavior

- Admin credentials and cluster definitions are stored in `./data/fluss-manager-web.json` by default.
- The config file stores BCrypt password hashes.
- API cluster responses must not expose saved SASL passwords.
- The app shows all databases returned by the selected cluster. Do not add hardcoded business categories.
- Database v1 supports create and delete only.
- Table v1 supports create, delete, and `alterTable` changes for columns/options.
- Fluss Java Client does not support generic SQL `WHERE`.
- Full primary-key lookup must use `table.newLookup().createLookuper()`, not `lookupBy(primaryKeys)`.
- Tables without primary keys must hide Query.

## Key Commands

```bash
node --check src/main/resources/static/app.js
mvn test
mvn -q package -DskipTests
./build.sh
```

## Runtime Notes

Java 17 needs:

```text
--add-opens=java.base/java.nio=ALL-UNNAMED --add-opens=java.base/sun.nio.ch=ALL-UNNAMED
```

These are configured in `pom.xml`, `.mvn/jvm.config`, and `Dockerfile`.

## Implementation Notes

- Keep Fluss SDK access in `SdkFlussMetadataService`.
- Keep REST controllers thin.
- Keep static frontend state in `src/main/resources/static/app.js`.
- Prefer forms over raw DDL editing for create/alter table.
- Confirm destructive operations in the browser before calling DELETE/PATCH drop-column operations.
- Do not commit `data/` or local config files.
