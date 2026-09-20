# Security Policy

## Supported Versions

Security fixes are focused on the current BroadSQL release and the current public source snapshot in this repository.

| Version | Security support |
|---|---|
| Current BroadSQL release | Supported |
| Current `main` branch / public source snapshot | Supported |
| Older releases | Not normally backported; upgrade to the current release |

This repository is a public source excerpt of BroadSQL. It is published for transparency and verification, but it does not contain the complete release, documentation, packaging, website, or maintainers' internal tooling.

A security issue affecting the distributed BroadSQL product is still relevant even if the affected code or packaging is not present in this repository.

## Reporting a Vulnerability

Please **do not report security vulnerabilities in a public GitHub issue**.

Use GitHub's private vulnerability reporting for this repository when it is available. If private reporting is not available, contact the BroadSQL maintainers privately through the contact information published at [www.broadsql.com](https://www.broadsql.com) and clearly identify the message as a security report.

A useful report should include:

- a clear description of the vulnerability;
- the affected BroadSQL version or versions;
- the operating system and Java version, when relevant;
- the database or JDBC driver involved, when relevant;
- the steps required to reproduce the issue;
- the expected and actual behavior;
- the potential security impact;
- a minimal proof of concept, if one is needed to demonstrate the issue;
- suggested remediation, if you have one.

Do not include real passwords, database credentials, API tokens, production data, customer data, connection-definition files, or other secrets unless they are strictly necessary to reproduce the issue. Redact sensitive values whenever possible.

If you are unsure whether an issue is a security vulnerability, report it privately and let the maintainers triage it.

## Security-Sensitive Areas

BroadSQL is a database client and therefore operates close to sensitive systems and data. Reports are particularly useful when they concern areas such as:

- exposure, disclosure, or insecure handling of database credentials;
- authentication or authorization bypasses;
- unintended access to configured database connections;
- SQL injection in BroadSQL-generated SQL or command handling;
- command injection or unsafe execution of external processes;
- path traversal, arbitrary file read, or arbitrary file overwrite;
- unsafe import, export, LOAD, PULL, COPY, or file-processing behavior;
- exposure of secrets or sensitive data through logs, errors, history, temporary files, or generated files;
- unsafe handling of API credentials or HTTP authentication data where applicable;
- vulnerabilities in bundled or directly used third-party dependencies that are exploitable through BroadSQL;
- extension-loading behavior that allows code to execute outside the documented trust model;
- privilege escalation or execution outside the permissions intentionally granted to the BroadSQL process;
- vulnerabilities that could cause unintended modification or disclosure of data on a connected system.

## Intended Behavior That Is Not a Vulnerability

BroadSQL is an administrative and development tool. A user who is authorized to run BroadSQL can intentionally execute SQL and other supported commands against systems for which that user has credentials and permissions.

The following are therefore not security vulnerabilities by themselves:

- the ability to execute arbitrary SQL entered by the user;
- destructive SQL executed intentionally by an authorized user;
- access permitted by the credentials configured for a database connection;
- filesystem access explicitly requested by a user through documented import/export features;
- behavior of a third-party JDBC driver that is outside BroadSQL's control and is not made exploitable by BroadSQL;
- arbitrary behavior introduced by a locally installed, untrusted third-party extension.

BroadSQL should be run with operating-system, database, filesystem, and network privileges appropriate to the work being performed. BroadSQL does not turn a highly privileged database account into a restricted one.

## Extensions and Trusted Code

BroadSQL can load Java extensions. Extensions execute as trusted code inside the BroadSQL Java process and are not a security sandbox.

An installed extension may be able to access BroadSQL runtime state, database connections, the local filesystem, network resources, and other resources available to the BroadSQL process.

Only install extension JARs from sources you trust. A vulnerability in an independently developed extension should normally be reported to that extension's maintainer. A vulnerability in BroadSQL's extension-loading mechanism or in the APIs BroadSQL exposes to extensions should be reported here.

## Third-Party Dependencies and JDBC Drivers

BroadSQL relies on Java libraries and JDBC drivers.

A vulnerability in a third-party component should be reported here when BroadSQL ships the affected component or uses it in a way that makes the vulnerability relevant to BroadSQL users.

If the issue affects only a separately installed JDBC driver or another component supplied entirely by the user, report it to that component's maintainer. You may still report it privately to BroadSQL if you believe BroadSQL materially increases the impact or exposes the vulnerable behavior.

## Coordinated Disclosure

Please allow reasonable time for investigation, remediation, testing, and release preparation before publicly disclosing a reported vulnerability.

Depending on the issue, remediation may include:

- a BroadSQL code change;
- a dependency update;
- documentation or configuration guidance;
- changes to the Extension Kit or extension APIs;
- a new BroadSQL release;
- a GitHub security advisory;
- a CVE request when appropriate.

Public disclosure should preferably occur after affected users have a practical way to obtain a fixed release or apply the recommended mitigation.

## Security Advisories

Repository Security Advisories are used for concrete vulnerabilities, not for general hardening suggestions or architectural limitations.

General security improvements that do not describe an exploitable vulnerability may be submitted as normal GitHub issues, provided they do not reveal sensitive exploit details or put existing installations at risk.

## Safe Testing

When investigating a suspected vulnerability:

- use a test environment whenever possible;
- use disposable or non-production databases;
- do not access data you are not authorized to access;
- do not attempt denial-of-service testing against systems you do not own or control;
- do not publish credentials, private connection definitions, or confidential data;
- minimize any proof of concept to what is necessary to demonstrate the issue.

## Scope of This Repository

The source code in this repository is licensed under the Apache License 2.0. See `LICENSE` and `NOTICE` for the applicable terms.

The repository exists to make BroadSQL's core source code and tests inspectable and buildable. Security reports concerning the actual BroadSQL product are welcome even when the relevant release infrastructure or proprietary operational material is not part of this public repository.
