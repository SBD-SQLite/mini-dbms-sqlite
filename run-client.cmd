@echo off
rem Starts a CLI client. Usage: run-client.cmd [--host H] [--port N] [-f script.sql]
cd /d "%~dp0"
if not exist query-processor\target\classes call mvn -q compile
java -cp "shared/target/classes;query-processor/target/classes" sqlike.qp.CliClient %*
