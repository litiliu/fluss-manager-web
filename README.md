# Fluss Manager Web

Open source web manager for Apache Fluss clusters.

## Features

- Administrator login with session authentication
- Local file persistence for admin credentials and cluster definitions
- Manage multiple Fluss clusters
- Show cluster server scale and server nodes
- Browse all databases and tables in the selected cluster
- Create and delete databases
- Create, alter, and delete tables
- View schema, keys, buckets, partitions, properties, stats, and generated DDL
- Preview recent table data
- Run exact primary-key lookups through a generated form
- Inspect PK-table changelogs and Log-table records with explicit bucket offsets

## Read-only log inspection

Select a table and open **Logs**. The **Offsets** tab shows every partition/bucket's
earliest retained offset and latest exclusive offset, along with sampling times.
Start offsets are editable; **Use latest as start** saves a baseline for subsequent
writes. Optional sampling runs every five seconds without overlapping requests,
stops when the dialog closes or a request fails, and retains up to 720 samples per
session. CSV exports all saved samples.

In **Changelog**, a read fixes an exclusive end offset for each requested bucket.
**Continue fixed range** resumes at the returned next offsets while preserving the
original ends. The view reports **Read complete**, **Page limit reached**, or
**Timed out**. An empty complete range stays empty; it never falls back to a table
snapshot. The result displays partition, bucket, offset, change type, timestamp,
and the raw row. Reads are ordered within each bucket, with no global ordering
implied between buckets. CSV exports every saved page; JSON includes all offset
samples, page statuses, records, and read/continuation boundaries.

The authenticated API provides `GET .../tables/{table}/offsets` and read-only
`POST .../tables/{table}/changelog`. A read body contains `tableId`, `ranges`
(`partitionId`, `bucket`, `startOffset`, optional `endOffset`), `limit` (1–5000),
and `timeoutMs` (100–30000). An omitted end captures the latest offset at that read.
Offsets and table/partition IDs use decimal strings to preserve 64-bit precision.
Expired ranges, recreated tables, unknown/duplicate buckets, and tables with more
than 256 partition/bucket combinations are rejected instead of silently skipping
data. Each request closes its own scanner and table/admin resources.

Scanner progress-only offsets are used when provided by the installed Fluss client,
so gaps/control records can finish a range without being mistaken for log rows.
CDC discard metrics and checkpoints still come from the CDC jobs' Flink APIs.

## Requirements

- Java 17+
- Maven 3.9+
- Docker, optional
- Apache Fluss reachable from this app

## Configuration

Environment variables:

| Variable | Default | Description |
| --- | --- | --- |
| `SERVER_PORT` | `8080` | HTTP port |
| `APP_CONFIG_PATH` | `./data/fluss-manager-web.json` | Local admin and cluster config file |
| `ADMIN_USERNAME` | `admin` | Initial admin username, used only when config file does not exist |
| `ADMIN_PASSWORD` | `admin` | Initial admin password, used only when config file does not exist |
| `FLUSS_BOOTSTRAP_SERVERS` | `localhost:9123` | Initial default cluster bootstrap servers |
| `FLUSS_SECURITY_PROTOCOL` | empty | Initial default cluster security protocol |
| `FLUSS_SASL_MECHANISM` | empty | Initial default cluster SASL mechanism |
| `FLUSS_SASL_USERNAME` | empty | Initial default cluster SASL username |
| `FLUSS_SASL_PASSWORD` | empty | Initial default cluster SASL password |

On first startup, the app creates `APP_CONFIG_PATH` with a BCrypt password hash and a default cluster.
Change `ADMIN_USERNAME` and `ADMIN_PASSWORD` before first production startup.

## Run Locally

```bash
ADMIN_USERNAME=admin ADMIN_PASSWORD=change-me \
FLUSS_BOOTSTRAP_SERVERS=your-fluss-bootstrap:9123 \
mvn spring-boot:run
```

Open:

```text
http://localhost:8080
```

## Build

```bash
node --check src/main/resources/static/app.js
mvn test
mvn package
```

## Docker

Build a Linux amd64 image:

```bash
./build.sh
```

Run:

```bash
docker run --rm -p 8080:8080 \
  -v "$PWD/data:/app/data" \
  -e ADMIN_USERNAME=admin \
  -e ADMIN_PASSWORD=change-me \
  -e FLUSS_BOOTSTRAP_SERVERS=your-fluss-bootstrap:9123 \
  fluss-manager-web:latest
```

Build Linux ARM:

```bash
PLATFORM=linux/arm64 ./build.sh
```

Build and push multi-arch:

```bash
PLATFORMS=linux/amd64,linux/arm64 PUSH=true ./build.sh your-registry/fluss-manager-web 0.1.0
```

## Notes

- Fluss Java Client is not a generic SQL engine.
- Primary-key query uses `table.newLookup().createLookuper()`.
- Tables without primary keys cannot use lookup, so the UI hides Query for those tables.
- Database update is not implemented in v1 because the current Fluss Java Client has no obvious `alterDatabase` API.
- Table update supports Fluss `alterTable` changes for columns and table options.
- Java 17 requires the configured `--add-opens` options because Fluss uses shaded Apache Arrow.
