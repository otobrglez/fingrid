package fingrid.service.authentication

import pdi.jwt.JwtClaim
import zio.{RIO, Task, ZIO}
import zio.hibernate.Hibernate
import zio.json.*
import zio.json.ast.{Json, JsonCursor}
import ZIO.{from, fromEither, fromTry, serviceWith}
import fingrid.persistence.entities.User
import fingrid.service.AppConfig
import fingrid.service.services.UsersRepository

import java.util.UUID

final case class AuthUser(
  userID: UUID,
  username: String,
  name: String,
  givenName: String,
  familyName: String,
  email: String,
  scope: String
) derives JsonCodec

object AuthUser:

  private def getAs[T](
    json: Json,
    key: String
  )(implicit decoder: JsonDecoder[T]): Task[T] =
    fromEither(
      json.get(JsonCursor.field(key)).flatMap(_.as[T])
    ).mapError(err => new RuntimeException(s"Failed getting ${key} with ${err}"))

  def fromClaim(claim: JwtClaim): RIO[AppConfig & Hibernate, (authUser: AuthUser, user: User)] = for
    expectedClient <- serviceWith[AppConfig](_.keycloakClientId)

    userID      <- ZIO.getOrFail(claim.subject).map(UUID.fromString)
    contentJSON <-
      from(claim.content.fromJson[Json])
        .mapError(err => new RuntimeException(s"Failed decoding claim: ${err}"))

    azp <- getAs[String](contentJSON, "azp")
    _   <-
      ZIO.fail(new RuntimeException(s"Invalid client: expected $expectedClient, got $azp")) when (azp != expectedClient)

    name       <- getAs[String](contentJSON, "name")
    givenName  <- getAs[String](contentJSON, "given_name")
    familyName <- getAs[String](contentJSON, "family_name")
    email      <- getAs[String](contentJSON, "email")
    username   <- getAs[String](contentJSON, "preferred_username")
    scope      <- getAs[String](contentJSON, "scope")
    user       <- UsersRepository.findOrCreate(userID, email, username)
  yield AuthUser(
    userID,
    username,
    name,
    givenName,
    familyName,
    email,
    scope
  ) -> user
