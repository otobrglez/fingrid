package fingrid.service

import fingrid.service.clients.Keycloak
import jakarta.persistence.{EntityManagerFactory, Persistence}
import zio.*
import zio.Runtime.{removeDefaultLoggers, setConfigProvider}
import zio.hibernate.Hibernate
import zio.http.*
import zio.logging.backend.SLF4J

object Main extends ZIOAppDefault:
  private val servicePort: Int = 7778

  override val bootstrap = setConfigProvider(ConfigProvider.envProvider) >>> removeDefaultLoggers >>> SLF4J.slf4j

  private def program = for
    _    <- ZIO.logInfo(s"Starting application on port $servicePort")
    sFib <- FingridServer.run.fork
    fib  <- Seeder(numberOfUsers = 1).seed.fork

    _ <- sFib.join
    _ <- fib.join
  yield ()

  def run = program.provide(
    Scope.default,
    Server.defaultWithPort(servicePort),
    Client.default,
    AppConfig.live,
    persistenceLayer >>> Hibernate.live,
    Keycloak.live,
    Runtime.enableRuntimeMetrics
  )

  private def persistenceLayer: RLayer[Scope & AppConfig, EntityManagerFactory] = ZLayer.fromZIO:
    for
      appConfig <- ZIO.service[AppConfig]
      props      = new java.util.Properties()
      _          = props.put("jakarta.persistence.jdbc.user", appConfig.databaseUser)
      _          = props.put("jakarta.persistence.jdbc.password", appConfig.databasePassword)
      _          = props.put("jakarta.persistence.jdbc.url", appConfig.databaseUrl.toString)
      _          = props.put("hibernate.hikari.minimumIdle", appConfig.databasePoolMinIdle.toString)
      _          = props.put("hibernate.hikari.maximumPoolSize", appConfig.databasePoolMaxSize.toString)
      _          = props.put("hibernate.hikari.connectionTimeout", appConfig.databasePoolConnectionTimeout.toString)
      _          = props.put("hibernate.hikari.idleTimeout", appConfig.databasePoolIdleTimeout.toString)
      _          = props.put("hibernate.hikari.maxLifetime", appConfig.databasePoolMaxLifetime.toString)
      factory   <- ZIO.fromAutoCloseable(ZIO.attempt(Persistence.createEntityManagerFactory("Fingrid", props)))
    yield factory
