# mini-dbms-sqlite

A mini database management system for IF3140 Sistem Basis Data (ITB, 2026), built by supergroup **SQLike**. It accepts SQL from a CLI, optimizes and executes queries, controls concurrent transactions, and recovers from failures.

## Components

| Folder | Component | Responsibility |
|---|---|---|
| [`query-processor`](query-processor) | Query Processor (QP) | Sessions, transaction commands, and query plan execution |
| [`query-optimizer`](query-optimizer) | Query Optimizer (QO) | Parsing, semantic validation, and cost-based plan selection |
| [`storage-manager`](storage-manager) | Storage Manager (SM) | Records, pages, buffers, indexes, catalog, and statistics |
| [`concurrency-control`](concurrency-control) | Concurrency Control Manager (CCM) | Transaction lifecycle, lock-based and timestamp-based access control |
| [`failure-recovery`](failure-recovery) | Failure Recovery Manager (FRM) | Write-ahead log, rollback, checkpoint, and crash recovery |

Each folder has its own README and class diagram under `docs/`.

## Contracts

Components talk only through the interfaces and shared data types agreed in the SQLike supergroup report: interfaces in `sqlike.contract`, shared types in `sqlike.shared`.

## Requirements

- JDK 17 or newer
- Java standard library only, no third-party libraries

## Running the DBMS

The full system (QP + QO + SM + CCM + FRM) runs as one server plus one or more CLI clients. Run everything from the repository root. The commands below use plain `java`, so they work on Windows, Linux and macOS without the `.sh` launchers.

### 1. Build

Requires JDK 17 or newer (the project is compiled with `--release 21`) and Maven.

```bash
mvn -o -q compile     
```

### 2. Start the server (terminal 1)

```bash
./run-server.sh --data sqlike-data --protocol lock        # Linux / macOS / Git Bash
run-server.cmd --data sqlike-data --protocol lock         # Windows cmd
.\run-server.cmd --data sqlike-data --protocol lock       # Windows PowerShell
```

| Option | Meaning | Default |
|---|---|---|
| `--data DIR` | Data directory (tables, indexes, catalog, WAL) | `sqlike-data` |
| `--protocol lock\|timestamp` | Concurrency control protocol (CC-02) | `lock` |
| `--buffer-frames N` | Buffer pool size in pages | built-in default |
| `--port N` | TCP port to listen on | `5432` |
| `--max-clients N` | Maximum simultaneous clients | `16` |
| `--index HASH\|BPLUS_TREE` | Default index type for `CREATE INDEX` | `HASH` |
| `--no-sample` | Do not load the sample database on a fresh data directory | sample is loaded |
| `--help` | Print usage | |

> **Port 5432 already in use** (for example by PostgreSQL)? Start the server and every client with another port, such as `--port 5433`.

### 3. Start one or more clients (terminal 2, 3, ...)

Each client is one session. Open two or more to test concurrent transactions.

```bash
./run-client.sh --port 5432          # Linux / macOS / Git Bash
run-client.cmd --port 5432           # Windows cmd
.\run-client.cmd --port 5432         # Windows PowerShell
```

- End every statement with `;`. Statements can span several lines.
- `\help` lists commands; `\q` or `EXIT;` quits.
- `-f script.sql` runs a script file instead of interactive input; `--host` and `--port` select the server.

### SQL commands

Keywords are not case-sensitive.

| Command | Syntax |
|---|---|
| Query | `SELECT * FROM t;` or `SELECT a, b FROM t [WHERE cond] [ORDER BY col [ASC\|DESC]] [LIMIT n];` |
| Insert | `INSERT INTO t VALUES (v1, v2, ...);` |
| Update | `UPDATE t SET col = value [WHERE cond];` |
| Delete | `DELETE FROM t [WHERE cond];` |
| Create table | `CREATE TABLE t (col type, ..., PRIMARY KEY (col), FOREIGN KEY (col) REFERENCES t2(col2));` with `INTEGER`, `FLOAT`, `CHAR(n)`, `VARCHAR(n)` |
| Drop table | `DROP TABLE t [CASCADE\|RESTRICT];` (default `RESTRICT`) |
| Create index | `CREATE INDEX name ON t(col) [USING HASH\|BPLUS_TREE];` |
| Transaction | `BEGIN [TRANSACTION];` `COMMIT;` `ABORT;` |

- A statement outside `BEGIN` is auto-committed. `CREATE TABLE` and `DROP TABLE` cannot run inside `BEGIN`.
- `WHERE` and `ON` accept `= <> > >= < <=`, `+ - * /` and `AND`.
- Joins: `FROM t1, t2`, `JOIN ... ON`, `INNER JOIN ... ON`, `NATURAL JOIN`. Aliases need `AS` (`products AS p`).
- Subqueries in `WHERE`: `IN (SELECT ...)` and `EXISTS (SELECT ...)`.
- `RESTRICT` fails if another table references the table. `CASCADE` also drops those foreign keys, not the other tables.
- Not supported: `DISTINCT`, `GROUP BY`, `HAVING`, `COUNT` and other functions, `OR`, `NOT`, `NULL`, `LIKE`, `BETWEEN`, `ALTER`, `TRUNCATE`, `EXPLAIN`, `SHOW TABLES`, `ROLLBACK` (use `ABORT`).

### Sample database

| Table | Columns | Keys | Rows |
|---|---|---|---|
| `categories` | `category_id` INTEGER, `name` VARCHAR(40), `code` CHAR(4) | PK `category_id` | 50 |
| `products` | `product_id` INTEGER, `name` VARCHAR(50), `category_id` INTEGER, `price` FLOAT, `stock` INTEGER | PK `product_id`, FK `category_id` | 120 |
| `customers` | `customer_id` INTEGER, `name` VARCHAR(50), `city` VARCHAR(30), `tier` CHAR(1) | PK `customer_id` | 80 |
| `orders` | `order_id` INTEGER, `customer_id` INTEGER, `product_id` INTEGER, `quantity` INTEGER, `total` FLOAT, `status` VARCHAR(10) | PK `order_id`, FK `customer_id`, `product_id` | 200 |

### Running the tests

| What | Command |
|---|---|
| SM unit tests (JUnit) | `mvn -o test -pl storage-manager -am` |
| QO driver | `bash query-optimizer/run-tests.sh` |
| CCM driver | `bash concurrency-control/run-tests.sh` |
| FRM driver | `java -cp <shared+frm classes> sqlike.frm.driver.FRMDriver --data-dir <dir>` |


## Drivers

| Component | Entry point |
|---|---|
| QP | `query-processor/run-tests-isolated.sh` (stubs) and `query-processor/run-tests-full-integration.sh` (real components) |
| QO | `sqlike.qo.QODriver` |
| SM | `sqlike.storage.StorageDriver` |
| CCM | `sqlike.ccm.CCMDriver` |
| FRM | `sqlike.frm.driver.FRMDriver` |

## Contributing

Each group works on `<type>/<scope>/<description>` branches, merges them into `develop/<scope>`, and then opens a Pull Request from `develop/<scope>` to `main`. Branch names, commit messages, and PR titles are checked by CI. See [CONTRIBUTING.md](CONTRIBUTING.md).

## Milestones

| Milestone | Deadline |
|---|---|
| Milestone 1 | 31 October 2026 |
| Milestone 2 | 7 November 2026 |
| Milestone 3 | 14 November 2026 |
| Final integration | 21 November 2026 |
| Demo | 23 to 27 November 2026 |

Releases are tagged `v<milestone>.<version>`.
