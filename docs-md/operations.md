---
layout: page
title: KSQL Operations
tagline: Administer KSQL clusters
description: Describes KSQL cluster administration
keywords: ksql, administer, operate
---

KSQL Operations
===============

Watch the [screencast of Taking KSQL to
Production](https://www.youtube.com/embed/f3wV8W_zjwE) on YouTube.

Local Development and Testing with Confluent CLI
------------------------------------------------

For development and testing purposes, you can use {{ site.confluentcli }}
to spin up services on a single host. For more information, see the [Confluent
Platform Quick Start](https://docs.confluent.io/current/quickstart/index.html).

!!! important
    The [confluent local](https://docs.confluent.io/current/cli/) commands are
    intended for a single-node development environment and are not suitable
    for a production environment. The data that are produced are transient
    and are intended to be temporary. For production-ready workflows, see
    [Install and Upgrade](https://docs.confluent.io/current/installation/index.html).

Installing and Configuring KSQL
-------------------------------

You have a number of options when you set up KSQL Server. For more
information on installing and configuring KSQL, see the following
topics.

-   [Installation and Configuration](installation/index.md)
-   [Configuring KSQL Server](installation/server-config/index.md)
-   [KSQL Configuration Parameter Reference](installation/server-config/config-reference.md)

Starting and Stopping KSQL Clusters
-----------------------------------

KSQL provides start and stop scripts.

- ksql-server-start: This script starts the KSQL server. It requires a server
configuration file as an argument and is located in the `/bin`
directory of your {{ site.cp }} installation. For more information,
see [Start the KSQL Server](installation/installing.md#start-the-ksql-server).
- ksql-server-stop: This script stops the KSQL server. It is located in the
`/bin` directory of your {{ site.cp }} installation.

Health Checks
-------------

-   The KSQL REST API supports a "server info" request at
    `http://<server>:8088/info` and a basic server health check endpoint at
    `http://<server>:8088/healthcheck`.
-   Check runtime stats for the KSQL server that you are connected to
    via `DESCRIBE EXTENDED <stream or table>` and
    `EXPLAIN <name of query>`.
-   Run `ksql-print-metrics` on a KSQL server. For example, see this
    [blog post](https://www.confluent.io/blog/ksql-january-release-streaming-sql-apache-kafka/).

Monitoring and Metrics
----------------------

KSQL includes JMX (Java Management Extensions) metrics which give
insights into what is happening inside your KSQL servers. These metrics
include the number of messages, the total throughput, throughput
distribution, error rate, and more.

To enable JMX metrics, set `JMX_PORT` before starting the KSQL server:

```bash
export JMX_PORT=1099 && \
<path-to-confluent>/bin/ksql-server-start <path-to-confluent>/etc/ksql/ksql-server.properties
```

The `ksql-print-metrics` command line utility collects these metrics and
prints them to the console. You can invoke this utility from your
terminal:

```bash
<path-to-confluent>/bin/ksql-print-metrics
```

Your output should resemble:

```
messages-consumed-avg: 96416.96196183885
messages-consumed-min: 88900.3329377909
error-rate: 0.0
num-persistent-queries: 2.0
messages-consumed-per-sec: 193024.78294586178
messages-produced-per-sec: 193025.4730374501
num-active-queries: 2.0
num-idle-queries: 0.0
messages-consumed-max: 103397.81191436431
```

For more information about {{ site.kstreams }} metrics, see
[Monitoring Streams Applications](https://docs.confluent.io/current/streams/monitoring.html).

Capacity Planning
-----------------

The [Capacity Planning guide](capacity-planning.md)
describes how to size your KSQL clusters.

Troubleshooting
---------------

### SELECT query hangs and doesn't stop?

Queries in KSQL, including non-persistent queries such as
`SELECT * FROM myTable EMIT CHANGES`, are continuous streaming queries.
Streaming queries will not stop unless explicitly terminated. To terminate
a non-persistent query in the KSQL CLI you must type Ctrl+C.

### No results from `SELECT * FROM` table or stream?

This is typically caused by the query being configured to process only
newly arriving data instead, and no new input records are being
received. To fix, do one of the following:

-   Run this command: `SET 'auto.offset.reset' = 'earliest';`. For more
    information, see [Configure KSQL CLI](installation/cli-config.md#configure-ksql-cli) and
    [Configuring KSQL Server](installation/server-config/index.md).
-   Write new records to the input topics.

### Can't create a stream from the output of windowed aggregate?

KSQL doesn't support structured keys, so you can't create a stream
from a windowed aggregate.

### KSQL doesn't clean up its internal topics?

Make sure that your {{ site.aktm }} cluster is configured with
`delete.topic.enable=true`. For more information, see
[deleteTopics](https://docs.confluent.io/{{ site.release }}/clients/javadocs/org/apache/kafka/clients/admin/AdminClient.html).

### KSQL CLI doesn't connect to KSQL server?

The following warning may occur when you start the KSQL CLI.

```
**************** WARNING ******************
Remote server address may not be valid:
Error issuing GET to KSQL server

Caused by: java.net.SocketException: Connection reset
Caused by: Connection reset
*******************************************
```

Also, you may see a similar error when you create a KSQL query by using
the CLI.

```
Error issuing POST to KSQL server
Caused by: java.net.SocketException: Connection reset
Caused by: Connection reset
```

In both cases, the CLI can't connect to the KSQL server, which may be
caused by one of the following conditions.

-   KSQL CLI isn't connected to the correct KSQL server port.
-   KSQL server isn't running.
-   KSQL server is running but listening on a different port.

#### Check the port that KSQL CLI is using

Ensure that the KSQL CLI is configured with the correct KSQL server
port. By default, the server listens on port `8088`. For more info, see
[Starting the KSQL CLI](installation/installing.md#start-the-ksql-cli).

#### Check the KSQL server configuration

In the KSQL server configuration file, check that the list of listeners
has the host address and port configured correctly. Look for the
`listeners` setting:

```
listeners=http://0.0.0.0:8088
```

Or if you are running over IPv6:

```
listeners=http://[::]:8088
```

For more info, see
[Start the KSQL Server](installation/installing.md#start-the-ksql-server).

#### Check for a port conflict

There may be another process running on the port that the KSQL server
listens on. Use the following command to check the process that's
running on the port assigned to the KSQL server. This example checks the
default port, which is `8088`.

```bash
netstat -anv | egrep -w .*8088.*LISTEN
```

Your output should resemble:

```
tcp4  0 0  *.8088       *.*    LISTEN      131072 131072    46314      0
```

In this example, `46314` is the PID of the process that\'s listening on
port `8088`. Run the following command to get info on the process.

```bash
ps -wwwp <pid>
```

Your output should resemble:

```
io.confluent.ksql.rest.server.KsqlServerMain ./config/ksql-server.properties
```

If the `KsqlServerMain` process isn't shown, a different process has
taken the port that `KsqlServerMain` would normally use. Check the
assigned listeners in the KSQL server configuration, and restart the
KSQL CLI with the correct port.

### Replicated topic with Avro schema causes errors?

Confluent Replicator renames topics during replication, and if there are
associated Avro schemas, they aren't automatically matched with the
renamed topics.

In the KSQL CLI, the `PRINT` statement for a replicated topic works,
which shows that the Avro schema ID exists in {{ site.sr }}, and KSQL
can deserialize the Avro message. But `CREATE STREAM` fails with a
deserialization error:

```sql
CREATE STREAM pageviews_original (viewtime bigint, userid varchar, pageid varchar) WITH (kafka_topic='pageviews.replica', value_format='AVRO');

[2018-06-21 19:12:08,135] WARN task [1_6] Skipping record due to deserialization error. topic=[pageviews.replica] partition=[6] offset=[1663] (org.apache.kafka.streams.processor.internals.RecordDeserializer:86)
org.apache.kafka.connect.errors.DataException: pageviews.replica
        at io.confluent.connect.avro.AvroConverter.toConnectData(AvroConverter.java:97)
        at io.confluent.ksql.serde.connect.KsqlConnectDeserializer.deserialize(KsqlConnectDeserializer.java:48)
        at io.confluent.ksql.serde.connect.KsqlConnectDeserializer.deserialize(KsqlConnectDeserializer.java:27)
```

The solution is to register schemas manually against the replicated
subject name for the topic:

```bash
# Original topic name = pageviews
# Replicated topic name = pageviews.replica
curl -X POST -H "Content-Type: application/vnd.schemaregistry.v1+json" --data "{\"schema\": $(curl -s http://localhost:8081/subjects/pageviews-value/versions/latest | jq '.schema')}" http://localhost:8081/subjects/pageviews.replica-value/versions
```

### Check KSQL server logs

If you're still having trouble, check the KSQL server logs for errors.

```bash
confluent log ksql-server
```

Look for logs in the default directory at `/usr/local/logs` or in the
`LOG_DIR` that you assign when you start the KSQL CLI. For more info,
see [Starting the KSQL CLI](installation/installing.md#start-the-ksql-cli).

Page last revised on: {{ git_revision_date }}
