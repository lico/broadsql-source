BroadSQL - open-source excerpt
=====================================

This repository contains the core source code (and test suite) of BroadSQL, a free command-line
SQL client for H2, PostgreSQL, Derby, HSQLDB, SQLite, and any JDBC-compatible database.

It is published for transparency: to let anyone read, build, and verify the code that connects to
their databases. It is not the full BroadSQL project - documentation, release packaging, and the
maintainers' internal tooling are intentionally not included here.

Full product, documentation, and downloads: https://www.broadsql.com

Build:
    mvn package
    (produces target/BroadSQL.jar, a single runnable jar)

Run:
    java -jar target/BroadSQL.jar

License: Apache License, Version 2.0. See LICENSE for the full text and NOTICE for attribution
requirements.

Copyright (c) UpAndCoding.com
