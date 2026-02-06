package fingrid.service

import jakarta.persistence.{EntityManagerFactory, Persistence}
import zio.*
import zio.hibernate.Hibernate
import zio.logging.backend.SLF4J
import zio.test.*

object SeederTest extends ZIOSpecDefault:

  override val bootstrap: ZLayer[Any, Any, TestEnvironment] =
    Runtime.setConfigProvider(ConfigProvider.envProvider) >>>
      Runtime.removeDefaultLoggers >>>
      SLF4J.slf4j >>>
      testEnvironment

  def spec = suite("SeederTest")(
    test("create users with namespaces, categories, and transactions") {
      for _ <- Seeder().seed
      yield assertCompletes
    }
  ).provide(
    Scope.default,
    persistenceLayerPG >>> Hibernate.live
  ) @@ TestAspect.sequential @@ TestAspect.withLiveSystem @@ TestAspect.withLiveClock

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
      // HikariCP pool settings from config
      _          = props.put("hibernate.hikari.minimumIdle", appConfig.databasePoolMinIdle.toString)
      _          = props.put("hibernate.hikari.maximumPoolSize", appConfig.databasePoolMaxSize.toString)
      _          = props.put("hibernate.hikari.connectionTimeout", appConfig.databasePoolConnectionTimeout.toString)
      _          = props.put("hibernate.hikari.idleTimeout", appConfig.databasePoolIdleTimeout.toString)
      _          = props.put("hibernate.hikari.maxLifetime", appConfig.databasePoolMaxLifetime.toString)
      factory   <- ZIO.fromAutoCloseable(ZIO.attempt(Persistence.createEntityManagerFactory("Fingrid", props)))
    yield factory
