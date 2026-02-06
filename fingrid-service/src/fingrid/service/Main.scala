package fingrid.service

import fingrid.service.auth.*
import jakarta.persistence.{EntityManagerFactory, Persistence}
import zio.*
import zio.Runtime.{removeDefaultLoggers, setConfigProvider}
import zio.hibernate.Hibernate
import zio.http.*
import zio.logging.backend.SLF4J

object Main extends ZIOAppDefault:
  override val bootstrap = setConfigProvider(ConfigProvider.envProvider) >>> removeDefaultLoggers >>> SLF4J.slf4j

  private def program = for
    _   <- ZIO.logInfo("Starting application...")
    fib <- Seeder(numberOfUsers = 5).seed.forkScoped
    _   <- Server.serve(app)
    _   <- fib.join
  yield ()

  private val app: Routes[AuthService & JwtService & Hibernate, Response] =
    AuthRoutes.routes ++ AuthRoutes.protectedRoutes

  def run = program.provide(
    Server.defaultWithPort(7778),
    Scope.default,
    persistenceLayer >>> Hibernate.live,
    ZLayer.fromZIO(JwtConfig.read.orDie) >>> JwtService.live,
    AuthService.live
  )

  private def persistenceLayer: RLayer[Scope, EntityManagerFactory] = ZLayer.fromZIO:
    for
      appConfig <- AppConfig.read
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
