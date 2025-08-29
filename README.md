# Saga Spring Boot Demo — Orchestration-Based Saga with Kafka & JPA

A **multi-module** Spring Boot demo that implements the **Saga (orchestration) pattern** for an order flow using **Apache Kafka** for inter-service messaging and **JPA/Hibernate** for local transactions. It includes **compensating transactions**, **append-only order history**, and **transactional publishing** (JPA ↔ Kafka synchronization).

> Modules (typical):
>
> * `core` — shared commands/events DTOs
> * `orders-service` — REST API, order state, **OrderSaga** (the orchestrator)
> * `products-service` — inventory/reservations
> * `payments-service` — (optional in your setup) payment processing

---

## ✨ OVERVIEW

* **Order creation → reservation → payment** sequence driven by **events/commands** over Kafka
* **Compensations** on failure (reverse order), e.g. cancel product reservation if payment fails
* **Transactional outbox-like behavior** via Spring’s **transaction synchronization**
  (Kafka producer commits/aborts together with JPA transaction)
* **Append-only order history** table for audit/debug
* **Idempotent consumers** using event headers and a `processed_events` table
* Detailed **logging** for JPA and Kafka transaction managers

---

## 🧭 Architecture

**Pattern:** **Saga — Orchestration** (a central orchestrator drives the flow by emitting *commands* in response to *events*).

### High-level flow (happy path)

```
Client ──HTTP POST /orders──▶ Orders Service
Orders Service (JPA) ──persist CREATED──▶ publish OrderCreatedEvent (orders-events)
OrderSaga (orchestrator) ──ReserveProductCommand──▶ products-commands
Products Service (JPA) ──reserve stock──▶ publish ProductReservedEvent (products-events)
OrderSaga ──ProcessPaymentCommand (next lessons)──▶ payments-commands
Payments Service (JPA) ──charge──▶ publish PaymentProcessedEvent (payments-events)
OrderSaga ──update status APPROVED (next lessons)
```

### Compensation (reverse order)

If **payment fails**:

```
PaymentFailedEvent → OrderSaga → CancelProductReservationCommand
Products Service (JPA) cancels reservation → ProductReservationCanceledEvent
OrderSaga marks order REJECTED (+ append to order history)
```

If **reservation fails**:

```
ProductReservationFailedEvent → OrderSaga marks order REJECTED (no cancel needed)
```

---

## 🧩 Topics & Message Contracts

> Put these class definitions in `core` and depend on `core` from services.

**Events (examples)**

* `OrderCreatedEvent(orderId, customerId, productId, quantity, total)`
* `ProductReservedEvent(orderId, productId, quantity, unitPrice)`
* `ProductReservationFailedEvent(orderId, productId, quantity, reason)`
* `PaymentProcessedEvent(orderId, paymentId, amount)`
* `PaymentFailedEvent(orderId, productId, productQuantity)`
* `ProductReservationCanceledEvent(orderId, productId, quantity)` *(compensation)*

**Commands (examples)**

* `ReserveProductCommand(orderId, productId, quantity)`
* `CancelProductReservationCommand(orderId, productId, quantity)` *(compensation)*

> **Key rule:** use **`orderId` as the Kafka message key** so all messages of one saga go to the same partition (ordering guaranteed).

**Topic names (properties)**

```properties
orders.events.topic.name=orders-events
products.events.topic.name=products-events
payments.events.topic.name=payments-events

products.commands.topic.name=products-commands
# (payments.commands.topic.name etc. if/when needed)
```

---

## 🗃️ Data Model (simplified)

**Orders Service**

* `orders` table: `id (UUID)`, `customerId`, `productId`, `quantity`, `total`, `status (CREATED|APPROVED|REJECTED)`
* `order_history` table (**append-only**): `id (UUID)`, `orderId`, `statusSnapshot`, `changedAt`
* *(optional)* `processed_events` for idempotency: `eventId`, `eventType`, `processedAt`

**Products Service**

* `inventory` table: `productId (UUID)`, `available`, `reserved`
* *(optional)* `processed_events` idem.

**Payments Service**

* `payments` table: `id`, `orderId`, `amount`, `status`
* *(optional)* `processed_events` idem.

---

## ⚙️ Configuration (common highlights)

**Kafka Producer (transactional)**

```properties
spring.kafka.producer.bootstrap-servers=localhost:9092
spring.kafka.producer.key-serializer=org.apache.kafka.common.serialization.StringSerializer
spring.kafka.producer.value-serializer=org.springframework.kafka.support.serializer.JsonSerializer
spring.kafka.producer.acks=all
spring.kafka.producer.properties.enable.idempotence=true
spring.kafka.producer.properties.max.in.flight.requests.per.connection=5
spring.kafka.producer.transaction-id-prefix=saga-${spring.application.name}-${random.value}-
```

**Kafka Consumer (JSON)**

```properties
spring.kafka.consumer.bootstrap-servers=localhost:9092
spring.kafka.consumer.key-deserializer=org.apache.kafka.common.serialization.StringDeserializer
spring.kafka.consumer.value-deserializer=org.springframework.kafka.support.serializer.JsonDeserializer
spring.kafka.consumer.properties.spring.json.trusted.packages=com.example.core.*
spring.kafka.listener.missing-topics-fatal=false
spring.kafka.consumer.auto-offset-reset=earliest
```

**Transaction Managers (per service)**

* `@Bean(name="transactionManager") JpaTransactionManager` → default for `@Transactional`
* `@Bean(name="kafkaTransactionManager") KafkaTransactionManager` → for Kafka-only TX (rare)

**Helpful logging**

```properties
logging.level.org.springframework.transaction=DEBUG
logging.level.org.springframework.orm.jpa.JpaTransactionManager=DEBUG
logging.level.org.springframework.kafka.transaction.KafkaTransactionManager=DEBUG
logging.level.org.apache.kafka.clients.producer.internals.TransactionManager=DEBUG
logging.level.org.hibernate.SQL=DEBUG
logging.level.org.hibernate.orm.jdbc.bind=TRACE
```

**H2 (for demos)**

```properties
spring.datasource.url=jdbc:h2:mem:<service-db>
spring.jpa.database-platform=org.hibernate.dialect.H2Dialect
spring.h2.console.enabled=true
```

---

## 🧪 Idempotency (recommended)

Kafka is *at-least-once*. Make consumers idempotent:

1. Add header **`event-id`** (UUID) when sending:

```java
ProducerRecord<String,Object> rec = new ProducerRecord<>(topic, key, payload);
rec.headers().add("event-id", UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8));
kafkaTemplate.send(rec);
```

2. Store processed IDs:

```java
@Entity @Table(name="processed_events")
class ProcessedEvent { @Id String eventId; String eventType; Instant processedAt; }
```

3. Check-then-handle in consumers:

```java
if (processedEventRepo.existsById(eventId)) return; // duplicate → ignore
processedEventRepo.save(...); // then execute business logic
```

---

## 🧱 Orchestrator (OrderSaga) — core responsibilities

* On `OrderCreatedEvent` → send `ReserveProductCommand`
* On `ProductReservedEvent` → (next lessons) send `ProcessPaymentCommand`
* On `ProductReservationFailedEvent` → mark **REJECTED** (+ history append)
* On `PaymentFailedEvent` → send `CancelProductReservationCommand`
* On `ProductReservationCanceledEvent` → mark **REJECTED** (+ history append `RESERVATION_CANCELED` then `REJECTED`)

**Why reverse order?** Because compensating transactions must undo preceding modifications **from last to first**.

---

## 🏃‍♂️ Run locally

### Prerequisites

* JDK 17+
* Maven 3.9+
* Docker (for Kafka/ZooKeeper or use Redpanda)

### 1) Start Kafka

You can use a minimal `docker-compose.yml`:

```yaml
version: "3.8"
services:
  zookeeper:
    image: confluentinc/cp-zookeeper:7.6.0
    environment:
      ZOOKEEPER_CLIENT_PORT: 2181
    ports: ["2181:2181"]

  kafka:
    image: confluentinc/cp-kafka:7.6.0
    depends_on: [zookeeper]
    ports: ["9092:9092"]
    environment:
      KAFKA_BROKER_ID: 1
      KAFKA_ZOOKEEPER_CONNECT: zookeeper:2181
      KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://localhost:9092
      KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1
```

Run:

```bash
docker compose up -d
```

### 2) Build

From the project root:

```bash
mvn clean install -DskipTests
```

### 3) Run services

In separate terminals:

```bash
cd orders-service    && mvn spring-boot:run
cd products-service  && mvn spring-boot:run
# cd payments-service && mvn spring-boot:run   # if included
```

*(Adjust `server.port` in each service if needed.)*

---

## 🔗 Quick API walkthrough

### Create an order

```bash
curl -X POST http://localhost:8080/api/v1/orders \
  -H "Content-Type: application/json" \
  -d '{
    "customerId":"11111111-1111-1111-1111-111111111111",
    "productId":"22222222-2222-2222-2222-222222222222",
    "quantity":2,
    "total":20.00
  }'
```

### List orders

```bash
curl http://localhost:8080/api/v1/orders
```

### Observe events

```bash
kafka-console-consumer --bootstrap-server localhost:9092 \
  --topic orders-events --from-beginning --property print.key=true
kafka-console-consumer --bootstrap-server localhost:9092 \
  --topic products-events --from-beginning --property print.key=true
# kafka-console-consumer --bootstrap-server localhost:9092 --topic payments-events ...
```

---

## 🩹 Failure testing (compensations)

### Reservation fails (OUT\_OF\_STOCK)

* Set `inventory.available < requested quantity`
* Create order
* Expected:

    * `ProductReservationFailedEvent`
    * Order status → **REJECTED**
    * Order history: `CREATED`, `REJECTED`
    * Inventory unchanged

### Payment fails (after reservation)

* Allow reservation (sufficient `available`)
* Force payment failure (e.g., throw in payment handler)
* Expected:

    * `PaymentFailedEvent`
    * `CancelProductReservationCommand` → `ProductReservationCanceledEvent`
    * Order status → **REJECTED**
    * Order history: `CREATED`, `RESERVATION_CANCELED`, `REJECTED`
    * Inventory `available` restored, `reserved` decreased

---

## 🛡️ Transaction semantics (JPA ↔ Kafka)

* Service methods that persist and then publish use **`@Transactional`** (JPA).
* With `spring.kafka.producer.transaction-id-prefix` set, **KafkaTemplate** joins the transaction via Spring’s **transaction synchronization**:

    * If JPA commits → Kafka transaction commits
    * If JPA rolls back → Kafka transaction aborts

*This is not XA/2PC; for production hardening consider an explicit Outbox pattern.*

---

## 🔍 Troubleshooting

* **No topics?** Use `NewTopic` beans or create via CLI. Locally use `replicas=1`.
* **JSON deserialization errors?** Ensure:

  ```
  spring.kafka.consumer.properties.spring.json.trusted.packages=com.example.core.*
  ```
* **Messages published but DB not saved (or vice-versa)?** Check `transaction-id-prefix` and that the method is annotated with `@Transactional`.
* **Duplicate processing?** Add idempotency (headers + `processed_events`).
* **Ordering issues?** Ensure key = `orderId`.

---

## 🧱 Project structure (example)

```
saga-spring-boot-app/
  core/
    src/main/java/com/example/core/commands/*.java
    src/main/java/com/example/core/events/*.java
  orders-service/
    src/main/java/com/example/orders/api/...
    src/main/java/com/example/orders/app/OrdersService.java
    src/main/java/com/example/orders/domain/...
    src/main/java/com/example/orders/history/...
    src/main/java/com/example/orders/saga/OrderSaga.java
    src/main/java/com/example/orders/config/KafkaConfig.java
    src/main/resources/application.properties
  products-service/
    src/main/java/com/example/products/app/...
    src/main/java/com/example/products/domain/...
    src/main/java/com/example/products/messaging/ProductCommandsHandler.java
    src/main/java/com/example/products/config/KafkaConfig.java
    src/main/resources/application.properties
  payments-service/ (optional)
```

---

## 🧪 Testing ideas

* **Unit:** mock `KafkaTemplate` and verify `send(topic, key, event)` after repository `save(...)`.
* **Integration:** use **Testcontainers Kafka** to assert events land on topics and DB state transitions occur.
* **Contract tests:** verify `core` DTOs serialize/deserialize consistently.

---

## 🙌 Credits

Inspired by common Saga patterns and Spring Kafka/JPA transaction synchronization. Tailored for a hands-on, **orchestration-first** saga demo with compensations and audit history.
