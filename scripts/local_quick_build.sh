#!/usr/bin/env bash

set -e

export MAVEN_OPTS="$MAVEN_OPTS -Xms8192m -Xmx8192m"
export JAVA_OPTS="$JAVA_OPTS -Xms8192m -Xmx8192m"
export JAVA_HOME=`/usr/libexec/java_home -v 17`

cd mockserver

echo
java -version
echo
./mvnw -version
echo

# to run from specific module use argument in quotes "-rf mockserver-war"
# -Djava.security.egd is supplied to all mockserver/ builds via .mvn/maven.config
./mvnw -T 3C clean install ${1:-}

cd ..
SKIP_JAVA_BUILD=true container_integration_tests/integration_tests.sh