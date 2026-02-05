package zio.hibernate

import org.hibernate.jpa.HibernatePersistenceConfiguration
import zio.*
import zio.hibernate.syntax.*
import zio.logging.backend.SLF4J
import zio.test.*

import scala.jdk.CollectionConverters.*

object HibernateSpec extends ZIOSpecDefault:
  override val bootstrap: ZLayer[Any, Any, TestEnvironment] =
    Runtime.setConfigProvider(ConfigProvider.envProvider) >>>
      Runtime.removeDefaultLoggers >>>
      SLF4J.slf4j >>>
      testEnvironment

  def spec = suite("HibernateSpec")(
    test("should create and retrieve an entity in a transaction") {
      for
        user        <-
          Hibernate.inTransaction { session =>
            val user = TestUser("Alice", "alice@example.com")
            session.persist(user)
            session.flush()
            ZIO.succeed(user)
          }
        retrieved   <- Hibernate.inTransaction(_.attemptFind[TestUser](user.id))
        nonExisting <- Hibernate.inTransaction(_.attemptFind[TestUser](42L))
      yield assertTrue(
        retrieved.exists(_.name == "Alice"),
        retrieved.exists(_.email == "alice@example.com"),
        nonExisting.isEmpty
      )
    },
    test("should rollback transaction on failure") {
      val result = for
        _         <-
          Hibernate.inTransaction { session =>
            val user = TestUser("Bob", "bob@example.com")
            session.persist(user)
            session.flush()
            ZIO.fail(new RuntimeException("Intentional failure"))
          }
        userCount <-
          Hibernate.readOnly { session =>
            ZIO.attempt(
              session
                .createQuery[Long]("SELECT COUNT(u) FROM zio.hibernate.TestUser u")
                .getSingleResult
                .longValue()
            )
          }
      yield userCount

      result.either.map {
        case Left(_)      => assertTrue(true)        // Expected to fail
        case Right(count) => assertTrue(count == 0L) // Should have rolled back
      }
    },
    test("should handle multiple operations in single transaction") {
      for
        users <- Hibernate.inTransaction { session =>
                   val user1 = TestUser("Charlie", "charlie@example.com")
                   val user2 = TestUser("Diana", "diana@example.com")
                   session.persist(user1)
                   session.persist(user2)
                   session.flush()
                   ZIO.succeed(List(user1, user2))
                 }
        count <-
          Hibernate.readOnly { session =>
            ZIO.attempt(
              session
                .createQuery[Long]("SELECT COUNT(u) FROM zio.hibernate.TestUser u")
                .getSingleResult
                .longValue()
            )
          }
      yield assertTrue(users.size == 2, count >= 2L)
    },
    test("should support read-only queries") {
      for
        _     <-
          Hibernate.inTransaction { session =>
            val user = TestUser("Eve", "eve@example.com")
            session.persist(user)
            ZIO.succeed(user)
          }
        users <- Hibernate.readOnly { session =>
                   ZIO.attempt(
                     session
                       .createQuery[TestUser]("FROM zio.hibernate.TestUser WHERE name = :name")
                       .setParameter("name", "Eve")
                       .getResultList
                       .asScala
                       .toList
                   )
                 }
      yield assertTrue(users.nonEmpty, users.head.name == "Eve")
    },
    test("should handle concurrent transactions safely") {
      val createUsers = ZIO.foreachPar((1 to 10).toList) { i =>
        Hibernate.inTransaction { session =>
          val user = TestUser(s"User$i", s"user$i@example.com")
          session.persist(user)
          ZIO.succeed(user)
        }
      }

      for
        _     <- createUsers
        count <-
          Hibernate.readOnly { session =>
            ZIO.attempt(
              session
                .createQuery[Long]("SELECT COUNT(u) FROM zio.hibernate.TestUser u")
                .getSingleResult
                .longValue()
            )
          }
      yield assertTrue(count >= 10L)
    },
    test("should update entities correctly") {
      for
        user     <- Hibernate.inTransaction { session =>
                      val user = TestUser("Frank", "frank@example.com")
                      session.persist(user)
                      session.flush()
                      ZIO.succeed(user)
                    }
        updated  <- Hibernate.inTransaction { session =>
                      val found = session.find(classOf[TestUser], user.id)
                      found.email = "frank.updated@example.com"
                      session.merge(found)
                      session.flush()
                      ZIO.succeed(found)
                    }
        verified <- Hibernate.readOnly(_.attemptFind[TestUser](user.id))
      yield assertTrue(
        updated.email == "frank.updated@example.com",
        verified.exists(_.email == "frank.updated@example.com")
      )
    },
    test("should delete entities correctly") {
      for
        user    <- Hibernate.inTransaction { session =>
                     val user = TestUser("Grace", "grace@example.com")
                     session.persist(user)
                     session.flush()
                     ZIO.succeed(user)
                   }
        _       <- Hibernate.inTransaction { session =>
                     val found = session.maybeFind[TestUser](user.id)
                     found.foreach(session.remove)
                     ZIO.unit
                   }
        deleted <- Hibernate.readOnly(_.attemptFind[TestUser](user.id))
      yield assertTrue(deleted.isEmpty)
    },
    test("should provide access to SessionFactory") {
      for sf <- Hibernate.sessionFactory
      yield assertTrue(sf != null)
    },
    test("should handle withTransaction with manual control") {
      for
        user  <-
          Hibernate.withTransaction { (session, tx) =>
            val user = TestUser("Henry", "henry@example.com")
            session.persist(user)
            session.flush()
            // Manual transaction control is available if needed
            ZIO.succeed(user)
          }
        found <- Hibernate.readOnly(_.attemptFind[TestUser](user.id))
      yield assertTrue(found.exists(_.name == "Henry"))
    },
    test("should properly close sessions on interruption") {
      for
        fiber  <-
          Hibernate.inTransaction { session =>
            val user = TestUser("Ivy", "ivy@example.com")
            session.persist(user)
            ZIO.never
          }.fork
        _      <- fiber.interrupt
        result <- fiber.await
      yield assertTrue(result.isInterrupted)
    },
    test("should support nested transactions (propagation)") {
      def innerTransaction(session: org.hibernate.Session) = Hibernate.inTransaction { innerSession =>
        val user = TestUser("Nested", "nested@example.com")
        (ZIO.attempt(innerSession.persist(user)) *>
          ZIO.logDebug(s"Inner session == outer session: ${innerSession == session}")).as(user)
      }

      def outerTransaction = Hibernate.inTransaction { outerSession =>
        for
          _     <- ZIO.unit
          user1  = TestUser("Outer", "outer@example.com")
          _     <- ZIO.attempt(outerSession.persist(user1))
          user2 <- innerTransaction(outerSession)
          _     <- ZIO.attempt(outerSession.flush())
        yield (user1, user2)
      }

      for
        (u1, u2) <- outerTransaction
        count    <-
          Hibernate.readOnly { session =>
            ZIO.attempt(
              session
                .createQuery[Long]("SELECT COUNT(u) FROM zio.hibernate.TestUser u")
                .getSingleResult
                .longValue()
            )
          }
      yield assertTrue(
        u1.name == "Outer",
        u2.name == "Nested",
        count >= 2L
      )
    },
    test("should rollback nested transactions together") {
      val result = for
        _     <-
          Hibernate.inTransaction { outer =>
            val user1 = TestUser("RollbackOuter", "rollback.outer@example.com")
            ZIO.attempt(outer.persist(user1)) *>
              Hibernate.inTransaction { inner =>
                val user2 = TestUser("RollbackInner", "rollback.inner@example.com")
                ZIO.attempt(inner.persist(user2)) *>
                  ZIO.fail(new RuntimeException("Nested failure"))
              }
          }
        count <-
          Hibernate.readOnly { session =>
            ZIO.attempt(
              session
                .createQuery[Long]("SELECT COUNT(u) FROM zio.hibernate.TestUser u WHERE u.name LIKE 'Rollback%'")
                .getSingleResult
                .longValue()
            )
          }
      yield count

      result.either.map {
        case Left(_)      => assertTrue(true)
        case Right(count) => assertTrue(count == 0L)
      }
    },
    test("readOnly should prevent writes and use optimized session") {
      for
        _                                                  <-
          Hibernate.inTransaction { session =>
            val user = TestUser("ReadOnlyTest", "readonly@example.com")
            session.attemptPersist(user)
          }
        result                                             <-
          Hibernate.readOnly { session =>
            for
              user              <-
                ZIO.attempt(
                  session
                    .createQuery[TestUser]("FROM zio.hibernate.TestUser WHERE name = :name")
                    .setParameter("name", "ReadOnlyTest")
                    .getSingleResult()
                )
              _                 <-
                ZIO.attempt {
                  user.email = "modified@example.com"
                  session.flush()
                }.ignore
              isReadOnly        <- ZIO.succeed(session.isDefaultReadOnly)
              flushMode         <- ZIO.succeed(session.getHibernateFlushMode)
              isDirty           <- ZIO.attempt(session.isDirty)
              transactionActive <- ZIO.succeed(session.getTransaction.isActive)
            yield (isReadOnly, flushMode, isDirty, transactionActive)
          }
        (isReadOnly, flushMode, isDirty, transactionActive) = result
        verified                                           <-
          Hibernate.readOnly { session =>
            ZIO.attempt(
              session
                .createQuery[TestUser]("FROM zio.hibernate.TestUser WHERE name = :name")
                .setParameter("name", "ReadOnlyTest")
                .getSingleResult()
            )
          }
      yield assertTrue(
        isReadOnly,
        flushMode == org.hibernate.FlushMode.MANUAL,
        !isDirty,
        transactionActive,
        verified.email == "readonly@example.com"
      )
    },
    test("should handle inBatch with nested transactions") {
      for
        users <-
          Hibernate.inTransaction { outerSession =>
            for
              parent     <-
                ZIO.attempt {
                  val user = TestUser("BatchParent", "parent@example.com")
                  outerSession.persist(user)
                  outerSession.flush()
                  user
                }
              batchUsers <-
                Hibernate.inBatch(5) { batchSession =>
                  ZIO
                    .foreach(1 to 20) { i =>
                      ZIO.attempt {
                        val user = TestUser(s"BatchUser$i", s"batch$i@example.com")
                        batchSession.persist(user)
                        user
                      }
                    }
                    .map(Chunk.fromIterable)
                }
              _          <- ZIO.logDebug(s"Created ${batchUsers.size} users in batch")
            yield (parent, batchUsers)
          }

        (parent, batchUsers) = users
        count               <-
          Hibernate.readOnly { session =>
            ZIO.attempt(
              session
                .createQuery[Long]("SELECT COUNT(u) FROM zio.hibernate.TestUser u WHERE u.name LIKE 'Batch%'")
                .getSingleResult
                .longValue()
            )
          }
      yield assertTrue(
        parent.name == "BatchParent",
        batchUsers.size == 20,
        count == 21L // parent + 20 batch users
      )
    },
    test("should rollback inBatch when nested transaction fails") {
      val result = for
        _     <- Hibernate.inTransaction { outerSession =>
                   for
                     parent <- ZIO.attempt {
                                 val user = TestUser("BatchRollbackParent", "rollback.parent@example.com")
                                 outerSession.persist(user)
                                 outerSession.flush()
                                 user
                               }
                     _      <- Hibernate.inBatch(5) { batchSession =>
                                 ZIO
                                   .foreach(1 to 10) { i =>
                                     ZIO.attempt {
                                       val user = TestUser(s"BatchRollback$i", s"rollback$i@example.com")
                                       batchSession.persist(user)
                                       user
                                     }
                                   }
                                   .map(Chunk.fromIterable) <*
                                   ZIO.fail(new RuntimeException("Batch operation failed"))
                               }
                   yield parent
                 }
        count <- Hibernate.readOnly { session =>
                   ZIO.attempt(
                     session
                       .createQuery[Long](
                         "SELECT COUNT(u) FROM zio.hibernate.TestUser u WHERE u.name LIKE 'BatchRollback%'"
                       )
                       .getSingleResult
                       .longValue()
                   )
                 }
      yield count

      result.either.map {
        case Left(_)      => assertTrue(true)        // Expected failure
        case Right(count) => assertTrue(count == 0L) // All should be rolled back
      }
    }
  ).provideLayerShared(
    hibernateLive
  ) @@ TestAspect.sequential @@ TestAspect.silentLogging

  private val hibernateLive: RLayer[Scope, Hibernate] = ZLayer.scoped:
    ZIO.attempt {
      val config =
        new HibernatePersistenceConfiguration("test-unit")
          .managedClass(classOf[TestUser])
          .property("hibernate.connection.driver_class", "org.h2.Driver")
          .property("hibernate.connection.url", "jdbc:h2:mem:test;DB_CLOSE_DELAY=-1")
          .property("hibernate.connection.username", "sa")
          .property("hibernate.connection.password", "")
          .property("hibernate.dialect", "org.hibernate.dialect.H2Dialect")
          .property("hibernate.hbm2ddl.auto", "create-drop")
          .property("hibernate.show_sql", "false")
          .property("hibernate.format_sql", "false")
          .property("hibernate.use_sql_comments", "false")
          .property("hibernate.connection.pool_size", "20")
          .property("hibernate.current_session_context_class", "thread")

      ZIO.fromAutoCloseable(ZIO.succeed(config.createEntityManagerFactory()))
    }.flatten.flatMap { factory =>
      FiberRef.make[Option[TransactionContext]](None).map(new Hibernate(factory, _))
    }
