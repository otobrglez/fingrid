package fingrid.service

import fingrid.service.clients.Keycloak
import zio.URIO
import zio.http.*
import zio.http.Header.AccessControlAllowOrigin
import zio.http.Middleware.CorsConfig
import zio.http.codec.*
import zio.http.codec.PathCodec.*
import zio.http.endpoint.*
import zio.http.endpoint.openapi.*

object FingridServer:
  private val corsConfig: CorsConfig = CorsConfig(
    allowedOrigin = _ => Some(AccessControlAllowOrigin.All),
    allowedMethods = Header.AccessControlAllowMethods.All
  )

  private val getMe = Endpoint(RoutePattern.GET / "me" ?? Doc.p("Me"))
    .auth(AuthType.Bearer)
    .out[String]

  private def getMeRoute = getMe.implement: (_: Unit) =>
    withContext((authUser: AuthUser) => authUser.toString)

  private val openAPI =
    OpenAPIGen.fromEndpoints(
      title = "fingrid",
      version = fingrid.info.BuildInfo.version,
      getMe
    )

  private def publicRoutes: Routes[Any, Nothing]   = Routes(Method.GET / "" -> handler(Response.text(".")))
  private def swaggerRoutes: Routes[Any, Response] = SwaggerUI.routes("docs" / "openapi", openAPI)

  private def protectedRoutes = Routes(
    getMeRoute
  ) @@ Authentication.protect @@ Middleware.debug

  def serving: Routes[Keycloak & AppConfig, Response] =
    publicRoutes ++ swaggerRoutes ++ protectedRoutes

  def run: URIO[Server & Keycloak & AppConfig, Nothing] = Server.serve(serving)
