# Local demo launcher: protected database identities

The launcher borrows already running, independently verified PostgreSQL and Kafka
dependencies. It does not create or initialize them. Before using Start, prepare
the separate private `logs/kaipay-demo/protected-identities.txt` file. This file
is ignored by Git and is independent of `logs/kaipay-demo/target.json`.

Put each database that must never be used by the demo on its own line as the
PostgreSQL cluster system identifier, a `|`, and the database OID. Both numbers
must be canonical, nonzero decimal strings. For example, the following is a
synthetic format example, not a usable inventory of your own databases:

```text
123456789012|42
```

Before use, replace the example with an independently verified, nonempty list of
all protected pairs for the installation. Keep the policy in a trusted location:
the launcher cannot discover missing entries or defend against someone changing
the policy. Missing, empty, invalid, duplicate or matching entries prevent Start.
Do not publish the policy, target manifest, credentials, logs or backups. Stop
does not require the policy; Status reports the target unverified if it cannot
validate it.

The operator must maintain exclusive control of database naming and client
access while identity checks and startup run. A successful identity check is not
a substitute for that fence or for an independently verified backup. Follow the
installation's reviewed setup and recovery procedures for the dependencies.

Start requires an existing readable backend JAR containing exactly one PostgreSQL
JDBC driver before any build; prepare that artifact separately if absent. It
requests the database credential even when the backend is already running. It
first verifies the `kaipay` catalog identity through the `postgres` maintenance
database and then verifies a direct `kaipay` connection, including after any
build that could replace the driver. Maintenance database access must be allowed.
This assumes the maintenance database is not protected and loopback reaches the
verified PostgreSQL instance directly, with no database-aware routing. A fence
against concurrent administrators and historical clients remains required.

## Isolated instance (optional)

By default the launcher uses Compose project `kaipay`, containers
`kaipay-postgres`/`kaipay-kafka` and loopback ports 25432, 29092, 28080 and
28081. A separate checkout can run beside it, for example for a rehearsal, by
adding an ignored `logs/kaipay-demo/instance.json` to that checkout:

```json
{"name":"kpdemo2","postgresPort":45432,"kafkaPort":49092,"backendPort":48080,"frontendPort":48081}
```

The name must be lowercase letters and digits and differ from `kaipay`; the
four ports must be distinct, non-default and at least 1024. The launcher then
uses that name for the Compose project and container names, generates
`logs/kaipay-demo/instance.compose.yml` to override container names, published
ports and the Kafka external listener, and points the frontend dev-server proxy
at the instance's backend. The checkout still needs its own verified
`target.json` and protected-identity policy, and its dependencies must already
exist; an instance file never relaxes identity checks. Without the file,
behaviour and Compose files are unchanged.

For an isolated instance:

- Give it its own data volumes. The launcher refuses a PostgreSQL or Kafka data
  volume that any other container, running or stopped, also uses, but it cannot
  recognise another installation's volume once that installation's containers
  are removed; never point `target.json` at another installation's volumes or
  Kafka cluster.
- Include every other installation's protected pairs, and the default
  installation's active `kaipay` pair, in this checkout's policy so a
  misconfigured target cannot select them.
- Create its dependencies with the same Compose files the launcher checks, in
  this order: `docker-compose.yml`, `scripts/kaipay-demo-kafka.compose.yml`,
  `scripts/kaipay-demo-postgres-guard.compose.yml` and the generated
  `logs/kaipay-demo/instance.compose.yml` (written by any launcher action, e.g.
  `-Action Status`, once `instance.json` exists), with project name equal to the
  instance name and the checkout as project directory. The disposable rehearsal
  `tests/demo-launcher-isolated-startup.e2e.ps1` shows the full sequence.
- Browser sessions are host-scoped cookies, so two instances on `127.0.0.1`
  share them. Use a separate browser profile or private window for the isolated
  instance, or signing in to one signs the other out.
