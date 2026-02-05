package fingrid.service

import zio.*
import zio.Runtime.{removeDefaultLoggers, setConfigProvider}
import zio.logging.backend.SLF4J
import zio.hibernate.Hibernate
import zio.hibernate.syntax.*
import fingrid.persistence.entities.*
import jakarta.persistence.{EntityManagerFactory, Persistence}
import org.hibernate.Session
import zio.config.ConfigOps

import java.net.URI

object Main extends ZIOAppDefault:
  override val bootstrap = setConfigProvider(ConfigProvider.envProvider) >>> removeDefaultLoggers >>> SLF4J.slf4j

  private def seed(session: Session): RIO[Hibernate, Unit] = for
    _      <- ZIO.logInfo("Starting database seed...")
    author1 = new Author("Oto Brglez")
    author2 = new Author("Martina Brglez")
    _      <- session.attemptPersist(author1)
    _      <- session.attemptPersist(author2)
    _      <-
      Hibernate.inBatch(22) { bs =>
        ZIO
          .foreach(1 to 100) { i =>
            val book1 = new Book(s"Book $i by ${author1.name}", author1)
            val book2 = new Book(s"Book $i by ${author2.name}", author2)
            bs.attemptPersist(book1) *> bs.attemptPersist(book2)
          }
          .orDie
          .map(Chunk.fromIterable)
      }
    _      <- ZIO.attempt(session.flush())
  yield ()

  private def program = for
    _ <- ZIO.logInfo("Starting application...")
    _ <- Hibernate.inTransaction(seed)
    _ <- Hibernate.statistics.flatMap { stats =>
           ZIO.logInfo(s"""
                          |Batch Statistics:
                          |  - Entity inserts: ${stats.getEntityInsertCount}
                          |  - Prepared statements: ${stats.getPrepareStatementCount}
                          |  - Connection obtains: ${stats.getConnectCount}
       """.stripMargin)
         }
    _ <- ZIO.logInfo("Application startup complete!")
  yield ()

  def run = program.provide(
    Scope.default,
    persistenceLayer >>> Hibernate.live
  )

  private def persistenceLayer: RLayer[Scope, EntityManagerFactory] = ZLayer.fromZIO:
    for
      appConfig <- AppConfig.read
      props      = new java.util.Properties()
      _          = props.put("jakarta.persistence.jdbc.user", appConfig.databaseUser)
      _          = props.put("jakarta.persistence.jdbc.password", appConfig.databasePassword)
      _          = props.put("jakarta.persistence.jdbc.url", appConfig.databaseUrl.toString)
      factory   <- ZIO.fromAutoCloseable(ZIO.attempt(Persistence.createEntityManagerFactory("Bookshelf", props)))
    yield factory

final case class AppConfig(
  databaseUser: String,
  databasePassword: String,
  databaseUrl: URI
)
object AppConfig:
  def read: IO[Config.Error, AppConfig] = ZIO.config(config)

  private val config: Config[AppConfig] =
    (
      Config.string("POSTGRES_USER") ++
        Config.string("POSTGRES_PASSWORD") ++
        Config.uri("DATABASE_URL")
    ).to[AppConfig]
