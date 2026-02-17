# fingrid

Fingrid is a tool that helps people and small organizations with their finances and expenses.

## Playground for `zio-hibernate`

This project also explores the fusion of ZIO and [Hibernate](https://hibernate.org/) on the sholders of Scala 3, Java,
and JVM.

Please explore [`zio-hibernate`](./zio-hibernate) for some ideas and integrations and [
`fingrid-persistence`](./fingrid-persistence) for the entities definitions.

### Key Features of `zio-hibernate`

The `zio-hibernate` integration provides a composable, type-safe way to work with Hibernate sessions and transactions in
ZIO applications. Here are the main features:

#### 1. Transaction Management with `attemptInTransaction`

The most common way to interact with the database is using `attemptInTransaction`, which handles session lifecycle,
transaction boundaries, and automatic rollback on errors:

```scala
import zio.hibernate.Hibernate
import fingrid.persistence.entities // Your Hibernate (Java) entities

// Simple database operation wrapped in a transaction
def createUser(name: String, email: String): RIO[Hibernate, UUID] =
  Hibernate.attemptInTransaction: session =>
    val userId = UUID.randomUUID()
    val user = entities.User(userId, name, email, "#FF0000", "hash")
    session.persist(user)
    session.flush()
    userId
```

**Key benefits:**

- Automatically opens a session and begins a transaction
- Commits on success, rolls back on failure
- Converts blocking Hibernate operations to ZIO effects with `ZIO.attempt`
- Proper resource cleanup guaranteed

#### 2. Nested Transactions and Propagation with `inTransaction`

`zio-hibernate` supports nested transaction propagation - when you nest `inTransaction` calls, they automatically share
the same session and transaction:

```scala
// Outer transaction
def createNamespaceWithCategories(userId: UUID, namespaceName: String, categoryNames: List[String]): RIO[Hibernate, UUID] =
  Hibernate.attemptInTransaction: session =>
    // Create namespace
    val user = session.getReference(classOf[entities.User], userId)
    val namespace = entities.Namespace(namespaceName, user)
    session.persist(namespace)
    session.flush()

    // Create categories in the same transaction
    categoryNames.foreach: name =>
      val category = entities.Category(name, namespace)
      session.persist(category)

    session.flush()
    namespace.id

// This also works with nested calls
def complexOperation(): RIO[Hibernate, Unit] =
  for
    userId <- createUser("alice", "alice@example.com") // Transaction 1
    nsId <- createNamespaceWithCategories( // Transaction 2
      userId,
      "Personal",
      List("Groceries", "Transport")
    )
  yield ()
```

The nested transaction behavior:

- **First call** to `inTransaction` or `attemptInTransaction` creates a new session/transaction
- **Subsequent nested calls** within the same fiber reuse the existing session/transaction
- Transaction commits only when the outermost call completes successfully
- Any failure in nested operations causes the entire transaction to roll back

#### 3. Read-Only Transactions

For query-only operations, use `readOnly` to optimize performance:

```scala
def findUserByEmail(email: String): RIO[Hibernate, Option[entities.User]] =
  Hibernate.readOnly: session =>
    ZIO.attempt:
      session
        .createQuery("FROM User u WHERE u.email = :email", classOf[entities.User])
        .setParameter("email", email)
        .maybeUniqueResult
```

Read-only sessions:

- Disable automatic dirty checking for better performance
- Set flush mode to `MANUAL`
- Always rollback (never commit) since no changes should occur

#### 4. Batch Operations

For bulk inserts or updates, use batch operations to improve performance:

```scala
def seedUsers(count: Int): RIO[Hibernate, Unit] =
  val users = Chunk.fromIterable(
    (1 to count).map: i =>
      entities.User(
        UUID.randomUUID(),
        s"user$i",
        s"user$i@example.com",
        generateColor(i),
        "hash"
      )
  )

  Hibernate.batchPersist(users, batchSize = 50)
```

Batch operations:

- Automatically flush and clear the session every `batchSize` entities
- Prevents memory issues with large datasets
- Leverages JDBC batch inserts for better performance

#### 5. Transaction Composition

Because everything is a ZIO effect, transactions compose naturally:

```scala
def transferOwnership(namespaceId: UUID, fromUserId: UUID, toUserId: UUID): RIO[Hibernate, Unit] =
  Hibernate.attemptInTransaction: session =>
    val namespace = session.find(classOf[entities.Namespace], namespaceId)
    val toUser = session.getReference(classOf[entities.User], toUserId)

    if namespace.owner.id != fromUserId then
      throw new IllegalArgumentException("Not the owner")

    namespace.owner = toUser
    session.merge(namespace)
    session.flush()

// Compose with other effects
def transferWithNotification(namespaceId: UUID, fromUserId: UUID, toUserId: UUID): RIO[Hibernate & EmailService, Unit] =
  for
    _ <- transferOwnership(namespaceId, fromUserId, toUserId)
    email <- ZIO.serviceWithZIO[EmailService](_.getEmail(toUserId))
    _ <- ZIO.serviceWithZIO[EmailService](_.sendNotification(email, "Ownership transferred"))
  yield ()
```

#### 6. Error Handling

Transactions automatically handle errors and rollback:

```scala
def createUserWithValidation(name: String, email: String): RIO[Hibernate, UUID] =
  Hibernate.attemptInTransaction: session =>
    // Check for duplicate email
    val existing = session
      .createQuery("FROM User WHERE email = :email", classOf[entities.User])
      .setParameter("email", email)
      .uniqueResult()

    if existing != null then
      throw new IllegalArgumentException(s"Email $email already exists")

    val userId = UUID.randomUUID()
    val user = entities.User(userId, name, email, "#FF0000", "hash")
    session.persist(user)
    session.flush()
    userId

// Automatic rollback on failure
val result: ZIO[Hibernate, Throwable, UUID] =
  createUserWithValidation("bob", "duplicate@example.com")
    .catchAll: error =>
      ZIO.logError(s"Failed to create user: $error") *>
        ZIO.fail(error)
```

All database changes are automatically rolled back if any operation in the transaction fails.

For more examples, see the [repository implementations](./fingrid-service/src/fingrid/service/services/).

## Author

\- [Oto Brglez](https://github.com/otobrglez)
