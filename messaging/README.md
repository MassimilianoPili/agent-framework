# Messaging — SPI e Provider

Layer di messaggistica transport-agnostic. Definisce interfacce comuni (SPI) e fornisce
3 implementazioni intercambiabili: JMS/Artemis (sviluppo), Redis Streams (staging),
Azure Service Bus (produzione).

## Moduli

| Modulo | Scopo |
|--------|-------|
| `messaging-api` | Interfacce SPI + `MessageEnvelope` record |
| `messaging-redis` | Implementazione Redis Streams — **default** |
| `messaging-jms` | Implementazione Apache Artemis (JMS) |
| `messaging-servicebus` | Implementazione Azure Service Bus |

## Core SPI (`messaging-api`)

### Interfacce

| Interfaccia | Metodo | Descrizione |
|-------------|--------|-------------|
| `MessageSender` | `send(MessageEnvelope)` | Invia messaggio a topic/stream |
| `MessageListenerContainer` | `subscribe(dest, group, handler)` | Sottoscrive a topic con consumer group |
| `MessageHandler` | `handle(body, ack)` | Callback per messaggi ricevuti (@FunctionalInterface) |
| `MessageAcknowledgment` | `complete()` / `reject(reason)` | Conferma/rifiuta messaggio |

### MessageEnvelope (record)

```java
public record MessageEnvelope(
    String messageId,                    // ID univoco (deduplica)
    String destination,                  // Topic/stream di destinazione
    String body,                         // Payload JSON
    Map<String, String> properties       // Proprieta' per routing (workerType, workerProfile, etc.)
) {}
```

## Provider Selection

Il provider si seleziona via property `messaging.provider` in `application.yml`:

```yaml
# JMS/Artemis (default — nessuna property necessaria)
messaging:
  provider: jms

# Redis Streams
messaging:
  provider: redis

# Azure Service Bus
messaging:
  provider: servicebus
```

Ogni provider usa `@AutoConfiguration` con `@ConditionalOnProperty`. Se nessun provider
e' specificato, viene attivato JMS.

## JMS / Apache Artemis (`messaging-jms`)

Provider di default per sviluppo locale. Usa `DefaultMessageListenerContainer` di Spring
con sessioni transazionali.

### Caratteristiche
- **Durable subscriptions**: i messaggi persistono anche se il consumer e' offline
- **Session-transacted**: commit automatico su successo, rollback su eccezione
- **Redelivery**: max 5 tentativi prima di scartare

### Configuration

```yaml
spring:
  artemis:
    mode: native
    broker-url: tcp://${ARTEMIS_HOST:localhost}:61616
    user: ${ARTEMIS_USER:admin}
    password: ${ARTEMIS_PASSWORD:admin}

messaging:
  jms:
    pub-sub-domain: true     # Topic mode (default)
    concurrency: "1-3"       # Min-max consumer threads
    max-redelivery-attempts: 5
```

### Setup locale

```bash
docker compose -f docker/docker-compose.dev.yml up -d redis
```

## Redis Streams (`messaging-redis`)

Usa `XADD`/`XREADGROUP` con consumer groups. Adatto per ambienti dove Redis e' gia'
disponibile.

### Caratteristiche
- **Consumer groups**: distribuzione messaggi tra consumer dello stesso gruppo
- **DLQ**: messaggi rifiutati spostati a stream `{key}:dlq`
- **Consumer ID**: `hostname-PID` per identificazione univoca

### Configuration

```yaml
messaging:
  provider: redis
  redis:
    host: ${REDIS_HOST:localhost}
    port: ${REDIS_PORT:6379}
    database: 3               # DB dedicato per messaging
    poll-timeout-ms: 2000     # Timeout polling
    batch-size: 10            # Messaggi per batch
```

## Azure Service Bus (`messaging-servicebus`)

Provider di produzione. Usa SDK Azure nativo con `ServiceBusSenderClient` (cached per topic)
e `ServiceBusProcessorClient` per consumo.

### Caratteristiche
- **SQL filters**: routing basato su proprieta' messaggio (`workerProfile = 'be-java'`)
- **DLQ nativa**: dead-letter queue per subscription con max delivery count
- **Connection caching**: un `SenderClient` per topic, riusato tra invocazioni

### Configuration

```yaml
messaging:
  provider: servicebus

azure:
  servicebus:
    connection-string: "Endpoint=sb://...;SharedAccessKeyName=...;SharedAccessKey=..."
    max-concurrent-calls: 1   # Consumer concorrenti per subscription
```

## Tabella comparativa

| Feature | JMS/Artemis | Redis Streams | Azure Service Bus |
|---------|-------------|---------------|-------------------|
| **Uso tipico** | Sviluppo locale | Staging | Produzione |
| **DLQ** | Rollback + redeliver | Stream `:dlq` | DLQ nativa |
| **Routing** | Topic + selector | Stream key | SQL filter |
| **Transazionalita'** | Session-transacted | XACK manuale | Complete/DeadLetter |
| **Persistenza** | Broker journal | Append-only stream | Cloud-managed |
| **Setup** | Docker Artemis | Docker Redis | Azure subscription |

## Come funziona il routing

L'orchestrator invia messaggi al topic `agent-tasks` con properties:

```
workerType: "BE"
workerProfile: "be-java"
planId: "abc-123"
```

Ogni worker ha una subscription con SQL filter:

| Subscription | SQL Filter |
|-------------|-----------|
| `be-java-worker-sub` | `workerProfile = 'be-java'` |
| `be-go-worker-sub` | `workerProfile = 'be-go'` |
| `ai-task-worker-sub` | `workerType = 'AI_TASK'` |

In JMS locale, il routing avviene tramite JMS message selector.
In Redis, ogni consumer group corrisponde a una subscription.

## Testing

Per test di integrazione, usare JMS embedded:

```xml
<dependency>
    <groupId>org.apache.activemq</groupId>
    <artifactId>artemis-jakarta-server</artifactId>
    <scope>test</scope>
</dependency>
```

```yaml
# application-test.yml
spring:
  artemis:
    mode: embedded
```
