package fingrid.service.auth

import pdi.jwt.{JwtAlgorithm, JwtClaim, JwtZIOJson}
import zio.*
import zio.json.*

import java.time.Clock

final case class JwtService(
  private val secret: String,
  private val expirationSeconds: Long
):
  private val clock: Clock = Clock.systemUTC
  private val algorithm    = JwtAlgorithm.HS256

  def generateToken(userId: Long, email: String): Task[String] = ZIO.attempt:
    val now = clock.instant.getEpochSecond
    val exp = now + expirationSeconds

    val payload = s"""{"userId":$userId,"email":"$email"}"""
    val claim   = JwtClaim(
      content = payload,
      expiration = Some(exp),
      issuedAt = Some(now)
    )

    JwtZIOJson.encode(claim, secret, algorithm)

  def validateToken(token: String): Task[JwtPayload] = ZIO.attempt:
    JwtZIOJson.decode(token, secret, Seq(algorithm)) match
      case scala.util.Success(claim) =>
        claim.content
          .fromJson[JwtPayload]
          .fold(error => throw new Exception(s"Invalid token payload: $error"), identity)
      case scala.util.Failure(error) =>
        throw new Exception(s"Invalid token: ${error.getMessage}")

object JwtService:
  def validateToken(token: String): RIO[JwtService, JwtPayload]           = ZIO.serviceWithZIO[JwtService](_.validateToken(token))
  def generateToken(userId: Long, email: String): RIO[JwtService, String] =
    ZIO.serviceWithZIO[JwtService](_.generateToken(userId, email))

  val live: URLayer[JwtConfig, JwtService] =
    ZLayer.fromFunction((config: JwtConfig) => JwtService(config.secret, config.expirationSeconds))

final case class JwtConfig(
  secret: String,
  expirationSeconds: Long
)

object JwtConfig:
  private val config: Config[JwtConfig] =
    (
      Config.string("JWT_SECRET") zip Config.long("JWT_EXPIRATION_SECONDS").withDefault(86400L)
    ).map(JwtConfig.apply)

  def read: IO[Config.Error, JwtConfig] = ZIO.config(config)
