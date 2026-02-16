package fingrid.service

import fingrid.service.BaseError.{AppError, AuthenticationError}
import fingrid.service.clients.Keycloak
import zio.{durationInt, URIO, ZIO}
import zio.hibernate.Hibernate
import zio.http.*
import zio.http.Header.AccessControlAllowOrigin
import zio.http.Middleware.CorsConfig
import zio.http.codec.*
import zio.http.codec.PathCodec.*
import zio.http.endpoint.*
import zio.http.endpoint.openapi.*
import zio.json.{jsonDiscriminator, jsonHintNames, SnakeCase}
import zio.schema.{DeriveSchema, Schema}
import zio.schema.annotation.discriminatorName

import java.util.UUID

@jsonHintNames(SnakeCase)
@jsonDiscriminator("type")
@discriminatorName("type")
enum BaseError(error: String) extends Throwable(error):
  case AppError(error: String)            extends BaseError(error)
  case AuthenticationError(error: String) extends BaseError(error)

object BaseError:
  given schema: Schema[BaseError]   = DeriveSchema.gen
  given Schema[AppError]            = DeriveSchema.gen
  given Schema[AuthenticationError] = DeriveSchema.gen

object AppError:
  given schema: Schema[AppError]            = DeriveSchema.gen
  def fromThrowable(t: Throwable): AppError = BaseError.AppError(t.getMessage)

object FingridServer:
  import AppError.*, BaseError.*

  private val corsConfig: CorsConfig = CorsConfig(
    allowedOrigin = _ => Some(AccessControlAllowOrigin.All),
    allowedMethods = Header.AccessControlAllowMethods.All
  )

  private val getMe = Endpoint(RoutePattern.GET / "me" ?? Doc.p("Me"))
    .auth(AuthType.Bearer)
    .out[String]

  private val getNamespacesEndpoint = Endpoint(RoutePattern.GET / "namespaces" ?? Doc.p("Get all namespaces"))
    .auth(AuthType.Bearer)
    .out[List[DTO.Namespace]]
    .outErrors[BaseError](
      HttpCodec.error[AppError](Status.BadRequest),
      HttpCodec.error[AuthenticationError](Status.Unauthorized)
    )

  private val getNamespaceEndpoint =
    Endpoint(RoutePattern.GET / "namespaces" / PathCodec.uuid("id") ?? Doc.p("Get namespace by ID"))
      .auth(AuthType.Bearer)
      .out[DTO.Namespace]
      .outErrors[BaseError](
        HttpCodec.error[AppError](Status.NotFound),
        HttpCodec.error[AuthenticationError](Status.Unauthorized)
      )

  private val createNamespaceEndpoint = Endpoint(RoutePattern.POST / "namespaces" ?? Doc.p("Create namespace"))
    .auth(AuthType.Bearer)
    .in[DTO.CreateNamespace]
    .out[DTO.Namespace](Status.Created)
    .outErrors[BaseError](
      HttpCodec.error[AppError](Status.BadRequest),
      HttpCodec.error[AuthenticationError](Status.Unauthorized)
    )

  private val updateNamespaceEndpoint =
    Endpoint(RoutePattern.PATCH / "namespaces" / PathCodec.uuid("id") ?? Doc.p("Update namespace"))
      .auth(AuthType.Bearer)
      .in[DTO.UpdateNamespace]
      .out[DTO.Namespace]
      .outErrors[BaseError](
        HttpCodec.error[AppError](Status.NotFound),
        HttpCodec.error[AuthenticationError](Status.Unauthorized)
      )

  private val deleteNamespaceEndpoint =
    Endpoint(RoutePattern.DELETE / "namespaces" / PathCodec.uuid("id") ?? Doc.p("Delete namespace"))
      .auth(AuthType.Bearer)
      .out[Boolean]
      .outErrors[BaseError](
        HttpCodec.error[AppError](Status.NotFound),
        HttpCodec.error[AuthenticationError](Status.Unauthorized)
      )
  private def getMeRoute              = getMe.implement: (_: Unit) =>
    withContext((authUser: AuthUser) => authUser.toString)

  private def getNamespaces = getNamespacesEndpoint.implement: (_: Unit) =>
    withContext(NamespacesRepository.findByAuth).mapError(AppError.fromThrowable)

  private def getNamespace = getNamespaceEndpoint.implement: (id: UUID) =>
    withContext: (authUser: AuthUser) =>
      NamespacesRepository
        .findById(id, authUser.userID)
        .mapError(AppError.fromThrowable)
        .flatMap:
          case Some(ns) => ZIO.succeed(ns)
          case None     => ZIO.fail(BaseError.AppError(s"Namespace not found: $id"))

  private def createNamespace = createNamespaceEndpoint.implement: (data: DTO.CreateNamespace) =>
    withContext: (authUser: AuthUser) =>
      NamespacesRepository.create(data, authUser.userID).mapError(AppError.fromThrowable)

  private def updateNamespace = updateNamespaceEndpoint.implement: (id: UUID, data: DTO.UpdateNamespace) =>
    withContext: (authUser: AuthUser) =>
      NamespacesRepository
        .update(id, data, authUser.userID)
        .mapError(AppError.fromThrowable)
        .flatMap:
          case Some(ns) => ZIO.succeed(ns)
          case None     => ZIO.fail(BaseError.AppError(s"Namespace not found or not owned: $id"))

  private def deleteNamespace = deleteNamespaceEndpoint.implement: (id: UUID) =>
    withContext: (authUser: AuthUser) =>
      NamespacesRepository
        .delete(id, authUser.userID)
        .mapError(AppError.fromThrowable)
        .flatMap: success =>
          if success then ZIO.succeed(true)
          else ZIO.fail(BaseError.AppError(s"Namespace not found or not owned: $id"))

  private val openAPI =
    OpenAPIGen.fromEndpoints(
      title = "fingrid",
      version = fingrid.info.BuildInfo.version,
      getMe,
      getNamespacesEndpoint,
      getNamespaceEndpoint,
      createNamespaceEndpoint,
      updateNamespaceEndpoint,
      deleteNamespaceEndpoint
    )

  private def publicRoutes: Routes[Any, Nothing]   = Routes(Method.GET / "" -> handler(Response.text(".")))
  private def swaggerRoutes: Routes[Any, Response] = SwaggerUI.routes("docs" / "openapi", openAPI)

  private def protectedRoutes = Routes(
    getMeRoute,
    getNamespaces,
    getNamespace,
    createNamespace,
    updateNamespace,
    deleteNamespace
  ) @@ Middleware.debug @@ Authentication.protect @@ Middleware.timeout(3.seconds)

  private def serving: Routes[AppConfig & Hibernate & Keycloak, Response] =
    publicRoutes ++ swaggerRoutes ++ protectedRoutes

  def run: URIO[Server & Hibernate & Keycloak & AppConfig, Nothing] = Server.serve(serving)
