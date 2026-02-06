package fingrid.service

import zio.{Config, IO, ZIO}
import zio.config.ConfigOps
import java.net.URI

final case class AppConfig(
  databaseUser: String,
  databasePassword: String,
  databaseUrl: URI,
  databasePoolMinIdle: Int,
  databasePoolMaxSize: Int,
  databasePoolConnectionTimeout: Long,
  databasePoolIdleTimeout: Long,
  databasePoolMaxLifetime: Long
)
object AppConfig:
  def read: IO[Config.Error, AppConfig] = ZIO.config(config)

  private val config: Config[AppConfig] =
    (
      Config.string("POSTGRES_USER") ++
        Config.string("POSTGRES_PASSWORD") ++
        Config.uri("DATABASE_URL") ++
        Config.int("DATABASE_POOL_MIN_IDLE").withDefault(5) ++
        Config.int("DATABASE_POOL_MAX_SIZE").withDefault(10) ++
        Config.long("DATABASE_POOL_CONNECTION_TIMEOUT").withDefault(20000L) ++
        Config.long("DATABASE_POOL_IDLE_TIMEOUT").withDefault(300000L) ++
        Config.long("DATABASE_POOL_MAX_LIFETIME").withDefault(1200000L)
    ).to[AppConfig]
