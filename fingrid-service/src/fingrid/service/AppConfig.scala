package fingrid.service

import zio.{Config, IO, ZIO}
import zio.config.ConfigOps
import java.net.URI

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
