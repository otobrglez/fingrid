package fingrid.service

import zio.config.ConfigOps
import zio.{Config, IO, TaskLayer, ZIO, ZLayer}

import java.net.URI

final case class AppConfig(
  databaseUrl: URI,
  databaseUser: String,
  databasePassword: String,
  databasePoolMinIdle: Int,
  databasePoolMaxSize: Int,
  databasePoolConnectionTimeout: Long,
  databasePoolIdleTimeout: Long,
  databasePoolMaxLifetime: Long,
  keycloakUrl: URI,
  keycloakRealm: String,
  keycloakClientId: String
)
object AppConfig:
  private val config: Config[AppConfig]     = configVia("DATABASE_URL", "KEYCLOAK_URL")
  private val testConfig: Config[AppConfig] = configVia("DATABASE_URL_TEST", "KEYCLOAK_URL_TEST")

  private def configVia(
    databaseKey: String,
    keycloakKey: String
  ): Config[AppConfig] =
    (
      Config.uri(databaseKey) ++
        Config.string("POSTGRES_USER") ++
        Config.string("POSTGRES_PASSWORD") ++
        Config.int("DATABASE_POOL_MIN_IDLE").withDefault(5) ++
        Config.int("DATABASE_POOL_MAX_SIZE").withDefault(10) ++
        Config.long("DATABASE_POOL_CONNECTION_TIMEOUT").withDefault(20000L) ++
        Config.long("DATABASE_POOL_IDLE_TIMEOUT").withDefault(300000L) ++
        Config.long("DATABASE_POOL_MAX_LIFETIME").withDefault(1200000L) ++
        Config.uri(keycloakKey) ++
        Config.string("KEYCLOAK_REALM").withDefault("fingrid") ++
        Config.string("KEYCLOAK_CLIENT_ID").withDefault("fingrid-platform")
    ).to[AppConfig]

  def read: IO[Config.Error, AppConfig]     = ZIO.config(config)
  def readTest: IO[Config.Error, AppConfig] = ZIO.config(testConfig)
  def live: TaskLayer[AppConfig]            = ZLayer.fromZIO(read)
  def liveTest: TaskLayer[AppConfig]        = ZLayer.fromZIO(readTest)
