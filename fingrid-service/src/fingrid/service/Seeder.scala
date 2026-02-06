package fingrid.service

import fingrid.persistence.entities.*
import zio.*
import zio.hibernate.Hibernate
import zio.hibernate.syntax.*

import java.math.BigDecimal
import java.time.{LocalDateTime, YearMonth}
import scala.util.Random

final case class Seeder(
  numberOfUsers: Int = 50,
  numberOfTransactionsPerUser: Int = 300
):
  private val categoryNames = List("Groceries", "Utilities", "Entertainment", "Transportation", "Healthcare")

  private def generateUser(index: Int): User =
    val rgbColor = f"#${Random.nextInt(256)}%02x${Random.nextInt(256)}%02x${Random.nextInt(256)}%02x"
    val hash     = (rgbColor * 10).take(60)
    User(s"User $index", s"user$index@example.com", rgbColor, hash)

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
    Random.shuffle(users).take(numUsers).foreach(transaction.getUsers.add)

    transaction

  def seed = Hibernate.inTransaction { session =>
    for
      _ <- ZIO.logInfo("Starting data generation...")

      users <-
        ZIO.foreach((1 to numberOfUsers).toList) { i =>
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
               _ <- ZIO.foreachDiscard(1 to numberOfTransactionsPerUser) { txIndex =>
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
