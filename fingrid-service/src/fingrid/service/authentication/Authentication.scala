package fingrid.service.authentication

import fingrid.persistence.entities.User
import fingrid.service.AppConfig
import fingrid.service.clients.Keycloak
import pdi.jwt.{JwtAlgorithm, JwtClaim, JwtZIOJson}
import zio.Config.Secret
import zio.ZIO.{fromTry, logError}
import zio.hibernate.Hibernate
import zio.http.*
import zio.{RIO, ZIO}

object Authentication:
  private def decodeToken(secret: Secret): RIO[Keycloak & AppConfig, JwtClaim] = for
    publicKey <- Keycloak.rs256Key
    token      = secret.stringValue
    claim     <- fromTry(JwtZIOJson.decode(token, publicKey, Seq(JwtAlgorithm.RS256)))
  yield claim

  private val unauthorized = Response.unauthorized("Invalid or expired token.")

  val protect: HandlerAspect[AppConfig & Hibernate & Keycloak, User] =
    HandlerAspect.interceptIncomingHandler(Handler.fromFunctionZIO[Request] { request =>
      request.header(Header.Authorization) match
        case Some(Header.Authorization.Bearer(token)) =>
          decodeToken(token)
            .flatMap(AuthUser.fromClaim(_).map(_.user))
            .tapError(e => logError(s"Authentication error: ${e.getMessage}"))
            .mapBoth(_ => unauthorized, request -> _)

        case _ => ZIO.fail(unauthorized)
    })
