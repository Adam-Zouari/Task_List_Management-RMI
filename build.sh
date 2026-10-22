#!/bin/sh
set -eu
cd -- "$(dirname -- "$0")"

case "${1-}" in
    '') test_mode=false ;;
    --test) test_mode=true ;;
    *) echo 'Usage: sh ./build.sh [--test]' >&2; exit 2 ;;
esac
if [ "$#" -gt 1 ]; then
    echo 'Usage: sh ./build.sh [--test]' >&2
    exit 2
fi

mkdir -p out
if [ "$test_mode" = true ]; then
    javac --release 11 -Xlint:all -Werror -d out ./src/*.java ./tests/*.java
else
    javac --release 11 -Xlint:all -Werror -d out ./src/*.java
fi
echo 'Build complete: compiled classes are in out/.'

if [ "$test_mode" = true ]; then
    for test_class in TaskManagerImplTest TaskStoreTest TaskIntegrationTest TaskClientTest TaskTableModelTest TaskTransportTest; do
        java -Djava.awt.headless=true -cp out "$test_class"
    done
fi
