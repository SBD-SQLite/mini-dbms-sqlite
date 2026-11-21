@echo off
rem Starts the full mDBMS server. Usage: run-server.cmd [--data DIR] [--port N] [--protocol lock|timestamp] [--no-sample]
cd /d "%~dp0"
if not exist query-processor\target\classes call mvn -q compile
java -cp "shared/target/classes;storage-manager/target/classes;query-optimizer/target/classes;concurrency-control/target/classes;failure-recovery/target/classes;query-processor/target/classes" sqlike.qp.QpServerMain %*
