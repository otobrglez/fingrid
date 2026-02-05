package fingrid.service

import zio.*
import zio.test.*
import zio.hibernate.Hibernate
import zio.hibernate.syntax.*
import fingrid.persistence.entities.*
import jakarta.persistence.{EntityManagerFactory, Persistence}
import zio.System.SystemLive
import zio.logging.backend.SLF4J
import zio.test.TestSystem.DefaultData

import java.math.BigDecimal
import java.net.URI
import java.time.{LocalDateTime, YearMonth}
import scala.util.Random

object DataGenerationTest extends ZIOSpecDefault:

  override val bootstrap: ZLayer[Any, Any, TestEnvironment] =
    Runtime.setConfigProvider(ConfigProvider.envProvider) >>>
      Runtime.removeDefaultLoggers >>>
      SLF4J.slf4j >>>
      testEnvironment

  private val categoryNames = List("Groceries", "Utilities", "Entertainment", "Transportation", "Healthcare")

  private def generateUser(index: Int): User =
    val rgbColor = f"#${Random.nextInt(256)}%02x${Random.nextInt(256)}%02x${Random.nextInt(256)}%02x"
    User(s"User $index", s"user$index@example.com", rgbColor)

  private def generateNamespaces(user: User): (Namespace, Namespace) =
    (Namespace("Family", user), Namespace("Business", user))

  private def generateCategories(namespace: Namespace): List[Category] =
    categoryNames.map(name => Category(name, namespace))

  private def generateTransaction(
    category: Category,
    creator: User,
    users: List[User],
    index: Int
  ): Transaction =
    val amount    = BigDecimal.valueOf(Random.nextDouble() * 1000 + 10)
    val currency  = if Random.nextBoolean() then Currency.EUR else Currency.USD
    val kind      = if Random.nextBoolean() then TransactionKind.CREDIT else TransactionKind.DEBIT
    val datetime  = LocalDateTime.now().minusDays(Random.nextInt(365))
    val yearMonth = YearMonth.of(2025 + Random.nextInt(2), Random.nextInt(12) + 1)

    val transaction = Transaction(amount, currency, kind, datetime, yearMonth, category, creator)

    // Assign transaction to some users
    val numUsers = Random.nextInt(users.length) + 1
    Random.shuffle(users).take(numUsers).foreach { user =>
      transaction.getUsers.add(user)
    }

    transaction

  private def createTestData = Hibernate.inTransaction { session =>
    for
      _ <- ZIO.logInfo("Starting data generation...")

      users <- ZIO.foreach((1 to 10).toList) { i =>
                 for
                   _   <- ZIO.unit
                   user = generateUser(i)
                   _   <- ZIO.logInfo(s"Creating user $i: ${user.name}")
                   _   <- session.attemptPersist(user)
                   _   <- ZIO.when(i % 10 == 0)(ZIO.logInfo(s"Created $i users") *> ZIO.attempt(session.flush()))
                 yield user
               }

      _ <- ZIO.logInfo("Creating namespaces and categories...")

      // For each user, create namespaces, categories, and transactions
      _ <- ZIO.foreachDiscard(users.zipWithIndex) { case (user, userIndex) =>
             for
               // Create 2 namespaces per user
               _                     <- ZIO.unit
               (familyNs, businessNs) = generateNamespaces(user)
               _                     <- session.attemptPersist(familyNs)
               _                     <- session.attemptPersist(businessNs)

               // Create 5 categories per namespace
               familyCategories   = generateCategories(familyNs)
               businessCategories = generateCategories(businessNs)
               _                 <- ZIO.foreachDiscard(familyCategories)(session.attemptPersist)
               _                 <- ZIO.foreachDiscard(businessCategories)(session.attemptPersist)

               allCategories = familyCategories ++ businessCategories

               // Create transactions per user
               _ <- ZIO.foreachDiscard(1 to 100) { txIndex =>
                      val category    = allCategories(Random.nextInt(allCategories.length))
                      val transaction = generateTransaction(category, user, List(user), txIndex)
                      session.attemptPersist(transaction)
                    }

               _ <- ZIO.when((userIndex + 1) % 10 == 0)(
                      ZIO.logInfo(s"Completed ${userIndex + 1} users with their data") *>
                        ZIO.attempt(session.flush())
                    )
             yield ()
           }

      _ <- ZIO.logInfo("Data generation complete!")

      // Flush and commit all pending changes
      _ <- ZIO.attempt(session.flush())
      _ <- ZIO.logInfo("Flushed all changes to database")

      // Show statistics
      _ <- Hibernate.statistics.flatMap { stats =>
             ZIO.logInfo(s"""
                            |Generation Statistics:
                            |  - Entity inserts: ${stats.getEntityInsertCount}
                            |  - Prepared statements: ${stats.getPrepareStatementCount}
                            |  - Connection obtains: ${stats.getConnectCount}
     """.stripMargin)
           }
    yield ()
  }

  /*
  private def persistenceLayer: RLayer[Scope, EntityManagerFactory] = ZLayer.fromZIO {
    for
      props <- ZIO.succeed(new java.util.Properties())
      _ = props.put("jakarta.persistence.jdbc.driver", "org.h2.Driver")
      _ = props.put("jakarta.persistence.jdbc.url", "jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1")
      _ = props.put("jakarta.persistence.jdbc.user", "sa")
      _ = props.put("jakarta.persistence.jdbc.password", "")
      _ = props.put("hibernate.dialect", "org.hibernate.dialect.H2Dialect")
      _ = props.put("hibernate.hbm2ddl.auto", "create-drop")
      _ = props.put("hibernate.show_sql", "false")
      _ = props.put("hibernate.format_sql", "false")
      factory <- ZIO.fromAutoCloseable(ZIO.attempt(Persistence.createEntityManagerFactory("Fingrid", props)))
    yield factory
  }
   */

  private def persistenceLayerPG: RLayer[Scope, EntityManagerFactory] = ZLayer.fromZIO:
    for
      appConfig <- AppConfig.read
      props     <- ZIO.succeed(new java.util.Properties())
      _          = props.put("jakarta.persistence.jdbc.user", appConfig.databaseUser)
      _          = props.put("jakarta.persistence.jdbc.password", appConfig.databasePassword)
      _          = props.put("jakarta.persistence.jdbc.url", appConfig.databaseUrl.toString)
      factory   <- ZIO.fromAutoCloseable(ZIO.attempt(Persistence.createEntityManagerFactory("Fingrid", props)))
    yield factory

  def spec = suite("DataGenerationTest")(
    test("create users with namespaces, categories, and transactions") {
      for _ <- createTestData
      yield assertCompletes
    }
  ).provide(
    Scope.default,
    persistenceLayerPG >>> Hibernate.live
  ) @@ TestAspect.sequential @@ TestAspect.withLiveSystem @@ TestAspect.withLiveClock
