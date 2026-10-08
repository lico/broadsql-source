#!/bin/sh
# | ==================================================== |
# | BroadSQL release 5.4.5, 2008-2026 by UpAndCoding.com |
# |                                                      |
# | Documentation and latest release available on:       |
# | https://www.broadsql.com                             |
# | ==================================================== |
#
# Starts BroadSQL on Linux/macOS: ./broadsql.sh [connection]
# Without an argument, BroadSQL opens the $CDF connection (the connections file itself);
# with one, it opens that connection, e.g. ./broadsql.sh WORLD
# Java 21 or later is required: JAVA_HOME/bin/java if JAVA_HOME is set, otherwise java from the PATH.

# Run from the installation folder, whatever the current directory (symbolic links to this script
# are followed): every relative path (conf/, lib/, drivers/, extensions/, samples/WorldDB) is relative to it
SCRIPT=$0
while [ -h "$SCRIPT" ]; do
	LINK=$(readlink "$SCRIPT")
	case "$LINK" in
		/*) SCRIPT=$LINK ;;
		*) SCRIPT=$(dirname "$SCRIPT")/$LINK ;;
	esac
done
BROADSQL_HOME=$(cd "$(dirname "$SCRIPT")" && pwd -P) || exit 1
cd "$BROADSQL_HOME" || exit 1

REQUIRED_JAVA=21
if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ]; then
	JAVA=$JAVA_HOME/bin/java
else
	if [ -n "$JAVA_HOME" ]; then
		echo "BroadSQL: JAVA_HOME ($JAVA_HOME) has no bin/java, using java from the PATH." >&2
	fi
	JAVA=$(command -v java) || JAVA=
	if [ -z "$JAVA" ]; then
		echo "BroadSQL requires Java $REQUIRED_JAVA or later: set JAVA_HOME or add java to the PATH." >&2
		exit 1
	fi
fi

# "21.0.2" -> 21, "1.8.0_112" -> 8
JAVA_VERSION=$("$JAVA" -version 2>&1 | sed -n 's/.* version "\([^"]*\)".*/\1/p' | head -n 1)
JAVA_MAJOR=${JAVA_VERSION%%.*}
if [ "$JAVA_MAJOR" = "1" ]; then
	JAVA_MAJOR=$(echo "$JAVA_VERSION" | cut -d. -f2)
fi
JAVA_MAJOR=$(echo "$JAVA_MAJOR" | sed 's/[^0-9].*//')
if [ -z "$JAVA_MAJOR" ]; then
	echo "BroadSQL: could not determine the version of $JAVA; Java $REQUIRED_JAVA or later is required." >&2
elif [ "$JAVA_MAJOR" -lt "$REQUIRED_JAVA" ]; then
	echo "BroadSQL requires Java $REQUIRED_JAVA or later, but $JAVA is Java $JAVA_VERSION." >&2
	echo "Set JAVA_HOME to a Java $REQUIRED_JAVA installation, or put its java first on the PATH." >&2
	exit 1
fi

# The connection to open: the first argument, or the literal $CDF (single quotes: never a shell variable)
if [ -n "$1" ]; then
	CONNECTION=$1
else
	CONNECTION='$CDF'
fi

# The classpath is quoted so the shell neither splits it nor expands its wildcards: Java expands lib/*, drivers/*
# and extensions/* itself (":" is the Unix classpath separator)
exec "$JAVA" -Xms2048m -Xmx4096m -Dbsql.settings=conf/broadsqlux.ini -Dfile.encoding=UTF-8 \
	-Dlogback.configurationFile=conf/logback-broadsql.xml \
	-cp "lib/*:drivers/*:extensions/*" com.upandcoding.broadsql.controller.BroadSQL "-to=$CONNECTION"
