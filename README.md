# 9Mail (NineMail) Enterprise Mail Server

9Mail is an SMTP/IMAP mail server implementation built on Java Spring Boot and Netty. It manages local domain user accounts, mailboxes, and message metadata using SQLite (MyBatis), while separating mail processing flows into ActiveMQ (JMS) queues for asynchronous handling.

Note: The default configuration uses privileged ports (SMTP: 25, IMAP: 143), which require administrator/root privileges.

## Core Concepts

- **Protocol Servers(SMTP/IMAP)**: Handles line-based protocols directly using Netty.
- **Inbound Processing**: Stores the raw EML received via SMTP DATA, records metadata in the DB after parsing, and delivers it to the recipient's INBOX.
- **Outbound Transmission**: For transmissions to external domains, it performs an MX lookup and sends directly via Jakarta Mail. Failures are managed through a queue-based retry mechanism with exponential backoff.
- **Storage Method**: Raw EML files are stored once (minimizing duplication), and "mail items" in each mailbox are managed as DB records.

## Key Features

- SMTP
  - Based on RFC 5321 ESMTP
  - AUTH PLAIN/LOGIN, STARTTLS (when configured)
  - Local domain delivery + External domain relay (based on Authentication/Relay IP)
- IMAP
  - Based on RFC 3501 (IMAP4rev1)
  - UID-based command processing
  - Aims to support extensions such as IDLE, MOVE, and UNSELECT
- Account Management REST API
  - Create/Delete/Inquiry accounts: `/api/accounts`
- Monitoring
  - Spring Actuator + Prometheus endpoint


## Tech Stack

- Java 25, Spring Boot 3.2.x
- Netty 4.1.x (SMTP/IMAP Tech Stack)
- Spring WebFlux (REST API)
- MyBatis + SQLite (`data/mailserver.db`)
- ActiveMQ(Embedded) + JMS (Mail Queue)
- Jakarta Mail(Angus Mail) (External SMTP Transmission)
- Micrometer + Prometheus (Metrics)

## Quick Start

### 1) Requirements

- JDK 25+
- Maven 3.8+

### 2) Check/Change Configuration

Configuration file: `src/main/resources/application.yml`

- DB: `jdbc:sqlite:data/mailserver.db`
- Storage:
  - Maildir: `data/maildir`
  - Raw EML: `data/eml`
- Ports:
  - SMTP: `ninemail.smtp.port`
  - IMAP: `ninemail.imap.port`

Recommended example for development (avoiding privileged ports):

```yaml
ninemail:
  smtp:
    port: 25
  imap:
    port: 143
```

Specify the domain name to use 
```yaml
ninemail:
  # Domain Configuration
  domain: example.com
```

### 3) Execution

From the project root:

```bash
mvn spring-boot:run
```

Or after packaging:

```bash
mvn -DskipTests package
java -jar target/ninemail-server-1.0.0-SNAPSHOT.jar
```

The following are automatically initialized upon execution:

- Creation of the `data/` directory
- Creation of the SQLite schema based on `schema.sql` (ignored if it already exists)

## Usage

### A. Account Creation (REST API)

After running the server (default `server.port=8080`):

```bash
curl -X POST http://localhost:8080/api/accounts \
  -H "Content-Type: application/json" \
  -d '{"username":"user1","email":"user1@localhost","password":"pass123"}'
```

- Local Domain Constraint: The email domain must match `ninemail.domain` for the account to be created.

### B. Sending Mail via SMTP

Sending to a local user (e.g., if set to `localhost:25`):

```text
$ telnet localhost 25
EHLO client
AUTH LOGIN
(base64 username)
(base64 password)
MAIL FROM:<user1@localhost>
RCPT TO:<user@localhost>
DATA
Subject: hello

This is a test.
.
QUIT
```

> Authentication methods and allowed relay policies vary depending on `ninemail.security.relay-ips`, `ninemail.smtp.require-auth`, etc.

### C. Checking Mail via IMAP

IMAP(e.q., `localhost:143`):

```text
$ telnet localhost 143
a1 LOGIN user1@localhost pass123
a2 LIST "" "*"
a3 SELECT INBOX
a4 FETCH 1:* (FLAGS BODY[HEADER.FIELDS (SUBJECT FROM DATE)])
a5 LOGOUT
```

## Monitoring(Actuator)

- Health: `http://localhost:8080/actuator/health`
- Prometheus: `http://localhost:8080/actuator/prometheus`

## Data/Storage Structure

- SQLite DB: `data/mailserver.db`
  - Initial Schema: `src/main/resources/schema.sql`
- Raw EML Storage Root: `ninemail.storage.base-path` (Default `data/maildir`)
  - Raw EML: `${base-path}/YYYY/MM/DD/*.eml`

## Troubleshooting

- **Port Binding Failure (25/143, etc.)**: Run with administrator privileges or change to a port above 1024 in application.yml.
- **DB File Locked/Permission Issues**: Check if you have write permissions for the data/ path.
- **External Domain Transmission Failure**: Check if outbound port 25 is blocked in your network environment and if DNS (MX) lookup is possible.

---

### Implementation Points (Code Reference)

- Application Entry: `src/main/java/com/ninemail/NineMailApplication.java`
- SMTP Server: `src/main/java/com/ninemail/smtp/*`
- IMAP Server: `src/main/java/com/ninemail/imap/*`
- DB/MyBatis: `src/main/java/com/ninemail/mapper/*`, `src/main/resources/mybatis/mapper/*`
- Queue(ActiveMQ/JMS): `src/main/java/com/ninemail/queue/*`


### License
- MIT
