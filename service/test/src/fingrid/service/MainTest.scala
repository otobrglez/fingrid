package fingrid.service

import fingrid.persistence.entities.{Author, Book}
import org.hibernate.jpa.HibernatePersistenceConfiguration
import zio.*
import zio.hibernate.Hibernate
import zio.test.*

object MainTest extends ZIOSpecDefault:

  // A dedicated Hibernate layer for testing
  private val hibernateTestLayer =
    Hibernate.liveWithConfiguration(
      new HibernatePersistenceConfiguration("BookshelfTest")
        .managedClasses(classOf[Author], classOf[Book])
        .jdbcUrl("jdbc:h2:mem:test_db;DB_CLOSE_DELAY=-1")
        .jdbcCredentials("sa", "")
        .showSql(true, false, true) // Log SQL statements
    )

  def spec = suite("Hibernate.inTransaction")(
    test("should roll back changes on failure") {
      val failingAuthorName = "I Will Not Be Saved"
      val program           = for
        h            <- ZIO.service[Hibernate]
        _            <- Hibernate.schemaManager.map(_.create(true))
        failingAuthor = new Author(failingAuthorName)

        _ <-
          h.inTransaction { session =>
            for
              _ <- ZIO.attempt(session.persist(failingAuthor))
              _ <- ZIO.fail(new RuntimeException("Boom! This is a test failure."))
              _ <- ZIO.attempt(session.persist(failingAuthor))
            yield ()
          }.ignore // We expect a failure, so we catch and ignore it

        authorExists <-
          h.inTransaction { session =>
            ZIO.attempt {
              val query = session.createQuery("from Author where name = :name", classOf[Author])
              query.setParameter("name", failingAuthorName)
              !query.getResultList.isEmpty
            }
          }
      yield assertTrue(!authorExists)

      program
    }
  ).provide(Scope.default, hibernateTestLayer)
