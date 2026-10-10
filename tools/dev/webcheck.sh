#!/usr/bin/env bash
# Compiles and tests the pure machine-maths port (com.gtnhplanner.machines.web) with javac and JUnit straight from
# Gradle's caches, into a folder of your own, without Gradle: several people (or agents) can check their part at once
# while a Gradle build runs. Usage: tools/dev/webcheck.sh <out-dir> [test class name filter, a regex]
set -euo pipefail
OUT=${1:?out dir}
FILTER=${2:-.}
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
JDK=~/.gradle/jdks/eclipse_adoptium-25-amd64-windows.2/bin
C=~/.gradle/caches/modules-2/files-2.1
jar() { find "$C/$1" -name "*.jar" ! -name "*sources*" | head -1; }
CP=""
for p in org.junit.jupiter/junit-jupiter-api/5.10.2 org.junit.jupiter/junit-jupiter-engine/5.10.2 \
  org.junit.jupiter/junit-jupiter-params/5.10.2 org.junit.platform/junit-platform-launcher/1.10.2 \
  org.junit.platform/junit-platform-engine/1.10.2 org.junit.platform/junit-platform-commons/1.10.2 \
  org.opentest4j/opentest4j/1.3.0 org.apiguardian/apiguardian-api/1.1.2 com.google.code.gson/gson/2.2.4 \
  com.google.code.findbugs/jsr305/3.0.2; do CP="$CP$(cygpath -w "$(jar $p)");"; done
rm -rf "$OUT/classes" && mkdir -p "$OUT/classes" "$OUT/launcher"
cat > "$OUT/launcher/WebCheck.java" <<'JAVA'
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
public class WebCheck {
    public static void main(String[] a) {
        var request = LauncherDiscoveryRequestBuilder.request()
            .selectors(java.util.Arrays.stream(a).map(DiscoverySelectors::selectClass).toList()).build();
        var listener = new SummaryGeneratingListener();
        LauncherFactory.create().execute(request, listener);
        var s = listener.getSummary();
        s.printFailuresTo(new java.io.PrintWriter(System.out), 25);
        System.out.println("tests " + s.getTestsFoundCount() + ", passed " + s.getTestsSucceededCount() + ", failed "
            + s.getTestsFailedCount());
        System.exit(s.getTotalFailureCount() == 0 ? 0 : 1);
    }
}
JAVA
SRC=$(find "$ROOT/src/main/java/com/gtnhplanner/machines/web" "$ROOT/src/test/java/com/gtnhplanner/machines/web" \
  -name '*.java' 2>/dev/null; echo "$OUT/launcher/WebCheck.java")
"$JDK/javac.exe" -nowarn -encoding UTF-8 -d "$(cygpath -w "$OUT/classes")" -cp "$CP" $(for f in $SRC; do cygpath -w "$f"; done)
RUNCP="$(cygpath -w "$OUT/classes");$(cygpath -w "$ROOT/src/main/resources");$(cygpath -w "$ROOT/src/test/resources");$CP"
# Package scanning finds nothing here on Windows, so every test class is named.
TESTS=$(cd "$OUT/classes" && find com -name '*Test.class' ! -name '*$*' | sed 's/[.]class$//; s|/|.|g' | grep -E "$FILTER" || true)
[ -n "$TESTS" ] || { echo "no test classes match $FILTER"; exit 1; }
"$JDK/java.exe" -cp "$RUNCP" WebCheck $TESTS
