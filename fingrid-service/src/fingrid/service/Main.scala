package fingrid.service

import zio.*
import zio.Runtime.{removeDefaultLoggers, setConfigProvider}
import zio.logging.backend.SLF4J
import zio.hibernate.Hibernate
import zio.hibernate.syntax.*
import fingrid.persistence.entities.*
import jakarta.persistence.{EntityManagerFactory, Persistence}
import org.hibernate.Session

import java.net.URI

object Main extends ZIOAppDefault:
  override val bootstrap = setConfigProvider(ConfigProvider.envProvider) >>> removeDefaultLoggers >>> SLF4J.slf4j

  private def program = for
    _ <- ZIO.logInfo("Starting application...")
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
      factory   <- ZIO.fromAutoCloseable(ZIO.attempt(Persistence.createEntityManagerFactory("Fingrid", props)))
    yield factory
