set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"  

mvn -q -pl query-optimizer -am test-compile

case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) SEP=";" ;;
  *) SEP=":" ;;
esac
CP="query-optimizer/target/classes${SEP}query-optimizer/target/test-classes${SEP}shared/target/classes"

status=0
java -cp "$CP" sqlike.qo.QODriver --test || status=1
java -cp "$CP" sqlike.qo.QODriver --scenarios || status=1

if [ "$status" -eq 0 ]; then echo "ALL QO CHECKS PASSED"; else echo "QO CHECKS FAILED"; fi
exit "$status"
