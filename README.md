# Spring-Boot Camel Narayana Quickstart (Scalable)

This quickstart uses Narayana TX manager with Spring Boot and Apache Camel on Openshift to test 2PC/XA transactions with a JMS resource (ActiveMQ) and a database (PostgreSQL).

The application uses a *partially out-of-process* recovery manager and a persistent volume to store transaction logs (the recovery manager 
is running in the leader instance, so it's `out-of-process` for the rest of the cluster).

The application **supports scaling**. A singleton recovery manager is kept active for the whole cluster using *leader election*.

The requirement for the leader election algorithm to work is that time in the nodes have a limited clock drift (shift < 15 seconds in the current configuration).
It's mandatory to use **NTP** for production usage. 

## Installation

The application uses **SNAPSHOT versions** of multiple libraries.

1. Build Spring Boot from [here](https://github.com/gytis/spring-boot/tree/1.5.x-narayana-connection-fixes) (1.5.5.BUILD-SNAPSHOT)

```
mvn clean install -DskipTests
```

2. Build Kubernetes Client from [here](https://github.com/nicolaferraro/kubernetes-client/tree/767-optimistic-lock)

```
mvn clean install -DskipTests
```

3. Build Camel from [here](https://github.com/nicolaferraro/camel/tree/CAMEL-11331-v4) (2.20.0-SNAPSHOT with CAMEL-11331)

```
mvn clean install -P fastinstall
```
 
4. While logged into a Openshift instance, execute the following command to deploy the quickstart: 

```
mvn clean fabric8:deploy
```

This command will deploy a PostgreSQL database, a ActiveMQ broker and a Spring-Boot application.

## Usage

Once the application is deployed you can get the base service URL using the following command:
 
```
NARAYANA_HOST=$(oc get route spring-boot-camel-narayana-scalable -o jsonpath={.spec.host})
```

The application exposes the following rest URLs:

- GET on `http://$NARAYANA_HOST/api/`: list all messages in the `audit_log` table (ordered)
- POST on `http://$NARAYANA_HOST/api/?entry=xxx`: put a message `xxx` in the `incoming` queue for processing

### Simple workflow

First get a list of messages in the `audit_log` table:

```
curl -w "\n" http://$NARAYANA_HOST/api/
```

The list should be empty at the beginning. Now you can put the first element.

```
curl -w "\n" -X POST http://$NARAYANA_HOST/api/?entry=hello
# wait a bit
curl -w "\n" http://$NARAYANA_HOST/api/
```

The new list should contain two messages: `hello-1` and `hello-1-ok`. The first part of each audit log 
is the message sent to the queue (`hello`), the second number is the progressive number of 
 delivery when it has been processed correctly (`1` is the first delivery attempt).
 The `hello-1-ok` confirms that the message has been sent to a `outgoing` queue and then logged.
 
You can add multiple messages and see the logs. The following actions force the application in some corner cases 
to examine the behavior.

#### Sporadic exception handling

Send a message named `failOnce`:

```
curl -w "\n" -X POST http://$NARAYANA_HOST/api/?entry=failOnce
# wait a bit
curl -w "\n" http://$NARAYANA_HOST/api/
```

This message produces an exception in the first delivery, so that the transaction is rolled back.
A subsequent redelivery (`JMSXDeliveryCount` > 1) is processed correctly.

In this case you should find **two log records** in the `audit_log` table: `failOnce-2`, `failOnce-2-ok` (the message is processed correctly at **delivery number 2**).

#### Repeatable exception handling

Send a message named `failForever`:

```
curl -w "\n" -X POST http://$NARAYANA_HOST/api/?entry=failForever
# wait a bit
curl -w "\n" http://$NARAYANA_HOST/api/
```

This message produces an exception in all redeliveries, so that the transaction is always rolled back.

You should **not** find any trace of the message in the `audit_log` table.
If you check the application log (you should find traces in all instances), you'll find out that the message has been sent to the dead letter queue.


#### Safe system crash

Send a message named `killOnce`:

```
curl -w "\n" -X POST http://$NARAYANA_HOST/api/?entry=killOnce
# wait a bit (the pod should be restarted)
curl -w "\n" http://$NARAYANA_HOST/api/
```

This message produces a **immediate crash** of the receiving pod during the first delivery, so that the transaction is not committed.
**Another pod** will process the message in a second delivery (`JMSXDeliveryCount` == 2), and this time it is processed correctly.

In this case you should find **two log records** in the `audit_log` table: `killOnce-2`, `killOnce-2-ok` (the message is processed correctly at **delivery number 2**).

#### Unsafe system crash

Send a message named `killBeforeCommit`:

```
curl -w "\n" -X POST http://$NARAYANA_HOST/api/?entry=killBeforeCommit
# wait a bit (the pod should be restarted)
curl -w "\n" http://$NARAYANA_HOST/api/
```

This message produces a **immediate crash after the first phase of the 2pc protocol and before the final commit (for some resources)**.
The message **must not** be processed again, but the transaction manager was not able to send a confirmation to all resources.
If you `curl -w "\n" http://$NARAYANA_HOST/api/`, you'll not find any trace of the message, or **you'll find inconsistent results, e.g. you may find the `killBeforeCommit-1-ok` log but not the previous `killBeforeCommit-1` (low isolation level)**.

The **recovery manager (you may have killed it) will recover all pending transactions by communicatng with the participating resources** (database and JMS broker).

When the recovery manager has finished processing failed transactions (**it may take time**), 
you should find **two log records** in the `audit_log` table (in order): `killBeforeCommit-1` (it will appear if it was missing), `killBeforeCommit-1-ok` (no redeliveries here, the message is processed correctly at **delivery number 1**).


## Credits

This quickstart is based on the work of:

- Christian Posta ([christian-posta/spring-boot-camel-narayana](https://github.com/christian-posta/spring-boot-camel-narayana))
- Gytis Trikleris ([gytis/spring-boot-narayana-stateful-set-example](https://github.com/gytis/spring-boot-narayana-stateful-set-example))