package fingrid.service

import fingrid.service.clients.Keycloak
import pdi.jwt.{JwtAlgorithm, JwtClaim, JwtZIOJson}
import zio.Config.Secret
import zio.{RIO, Task, ZIO}
import zio.ZIO.{fromTry, logError}
import zio.http.{Handler, HandlerAspect, Header, Request, Response}
import zio.json.*
import zio.json.ast.{Json, JsonCursor}
import ZIO.{attempt, from, fromEither, getOrFail, serviceWith}

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

object Authentication:
  private def decodeToken(secret: Secret): RIO[Keycloak & AppConfig, JwtClaim] = for
    expectedClient <- serviceWith[AppConfig](_.keycloakClientId)
    publicKey      <- Keycloak.rs256Key
    token           = secret.stringValue
    claim          <- fromTry(JwtZIOJson.decode(token, publicKey, Seq(JwtAlgorithm.RS256)))
    contentJSON    <-
      from(claim.content.fromJson[Json]).mapError(err => new RuntimeException(s"Failed parsing token: ${err}"))
    azp            <- getAs[String](contentJSON, "azp")
    _              <-
      ZIO.fail(new RuntimeException(s"Invalid client: expected $expectedClient, got $azp")) when (azp != expectedClient)
  yield claim

  private def getAs[T](
    json: Json,
    key: String
  )(implicit decoder: JsonDecoder[T]): Task[T] =
    fromEither(
      json.get(JsonCursor.field(key)).flatMap(_.as[T])
    ).mapError(err => new RuntimeException(s"Failed getting ${key} with ${err}"))

  private def userFromClaim(claim: JwtClaim): Task[AuthUser] = for
    userID      <- getOrFail(claim.subject).map(UUID.fromString)
    contentJSON <-
      from(claim.content.fromJson[Json])
        .mapError(err => new RuntimeException(s"Failed decoding claim: ${err}"))
    name        <- getAs[String](contentJSON, "name")
    givenName   <- getAs[String](contentJSON, "given_name")
    familyName  <- getAs[String](contentJSON, "family_name")
    email       <- getAs[String](contentJSON, "email")
    username    <- getAs[String](contentJSON, "preferred_username")
    scope       <- getAs[String](contentJSON, "scope")
  yield AuthUser(
    userID,
    username,
    name,
    givenName,
    familyName,
    email,
    scope
  )

  private val unauthorized = Response.unauthorized("Invalid or expired token.")

  val protect: HandlerAspect[Keycloak & AppConfig, AuthUser] =
    HandlerAspect.interceptIncomingHandler(Handler.fromFunctionZIO[Request] { request =>
      request.header(Header.Authorization) match
        case Some(Header.Authorization.Bearer(token)) =>
          decodeToken(token)
            .flatMap(userFromClaim)
            .tapError(e => logError(s"Authentication error: ${e.getMessage}"))
            .mapBoth(_ => unauthorized, request -> _)

        case _ => ZIO.fail(unauthorized)
    })
