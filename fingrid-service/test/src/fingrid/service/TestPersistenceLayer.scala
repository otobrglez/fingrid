package fingrid.service

import jakarta.persistence.{EntityManagerFactory, Persistence}
import zio.{RLayer, Scope, ZIO, ZLayer}

object TestPersistenceLayer:
  private def make = for
    appConfig <- AppConfig.readTest
    props     <- ZIO.succeed(new java.util.Properties())
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

  def live: RLayer[Scope, EntityManagerFactory] = ZLayer.fromZIO(make)
