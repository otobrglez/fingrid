package fingrid.service.auth

import fingrid.persistence.entities.User
import zio.*
import zio.hibernate.Hibernate
import zio.http.*
import zio.json.*

object AuthRoutes:

  val routes: Routes[AuthService & Hibernate, Response] =
    Routes(
      // Register endpoint
      Method.POST / "auth" / "register" -> handler { (req: Request) =>
        (for
          body         <- req.body.asString
          registerReq  <-
            ZIO.fromEither(body.fromJson[RegisterRequest]).mapError(err => new Exception(s"Invalid request: $err"))
          authResponse <- AuthService.register(registerReq)
        yield Response.json(authResponse.toJson)).catchAll {
          case e if e.getMessage.contains("Email already exists") =>
            ZIO.succeed(Response(status = Status.Conflict, body = Body.fromString(e.getMessage)))
          case e                                                  =>
            ZIO.succeed(Response.badRequest(e.getMessage))
        }
      },

      // Login endpoint
      Method.POST / "auth" / "login" -> handler { (req: Request) =>
        (for
          body     <- req.body.asString
          loginReq <-
            ZIO
              .fromEither(body.fromJson[LoginRequest])
              .mapError(err => new Exception(s"Invalid request: $err"))

          authResponse <- AuthService.login(loginReq)
        yield Response.json(authResponse.toJson)).catchAll {
          case e if e.getMessage.contains("Invalid credentials") =>
            ZIO.succeed(Response.unauthorized(e.getMessage))
          case e                                                 =>
            ZIO.succeed(Response.badRequest(e.getMessage))
        }
      }
    )

  // Protected routes example
  val protectedRoutes: Routes[Hibernate & AuthService & JwtService, Response] =
    Routes(
      Method.GET / "auth" / "me" -> handler { (req: Request) =>
        (for
          authHeader <- ZIO
                          .fromOption(req.header(Header.Authorization))
                          .orElseFail(new Exception("Missing authorization header"))
          token      <- ZIO.succeed(authHeader.renderedValue.stripPrefix("Bearer ").trim)
          payload    <- JwtService.validateToken(token)
          user       <- AuthService.getUserById(payload.userId).someOrFail(new Exception("User not found"))
        yield Response.json(s"""{"id":${user.id},"name":"${user.name}","email":"${user.email}"}""")).catchAll(e =>
          ZIO.succeed(Response.badRequest(e.getMessage))
        )
      }
    )
