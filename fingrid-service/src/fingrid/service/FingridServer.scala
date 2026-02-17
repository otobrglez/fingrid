package fingrid.service

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
import fingrid.service.services.*
import fingrid.service.authentication.*
import fingrid.service.clients.Keycloak
import fingrid.persistence.entities.User
import java.util.UUID

@jsonHintNames(SnakeCase)
@jsonDiscriminator("type")
@discriminatorName("type")
enum BaseError(error: String) extends Throwable(error):
  case AppError(error: String)            extends BaseError(error)
  case AuthenticationError(error: String) extends BaseError(error)

object BaseError:
  given schema: Schema[BaseError]                              = DeriveSchema.gen[BaseError]
  given appErrorSchema: Schema[AppError]                       = DeriveSchema.gen[AppError]
  given authenticationErrorSchema: Schema[AuthenticationError] = DeriveSchema.gen[AuthenticationError]

  final def toAppError(th: Throwable): AppError = AppError(th.getMessage)

object FingridServer:
  import BaseError.*

  private val corsConfig: CorsConfig = CorsConfig(
    allowedOrigin = _ => Some(AccessControlAllowOrigin.All),
    allowedMethods = Header.AccessControlAllowMethods.All
  )

  private val getMe = Endpoint(RoutePattern.GET / "me" ?? Doc.p("Me"))
    .auth(AuthType.Bearer)
    .out[String]

  private val getNamespacesEndpoint = Endpoint(RoutePattern.GET / "namespaces" ?? Doc.p("Get all namespaces"))
    .auth(AuthType.Bearer)
    .out[List[NamespacesRepository.DTO.Namespace]]
    .outErrors[BaseError](
      HttpCodec.error[AppError](Status.BadRequest),
      HttpCodec.error[AuthenticationError](Status.Unauthorized)
    )

  private val getNamespaceEndpoint =
    Endpoint(RoutePattern.GET / "namespaces" / PathCodec.uuid("id") ?? Doc.p("Get namespace by ID"))
      .auth(AuthType.Bearer)
      .out[NamespacesRepository.DTO.Namespace]
      .outErrors[BaseError](
        HttpCodec.error[AppError](Status.NotFound),
        HttpCodec.error[AuthenticationError](Status.Unauthorized)
      )

  private val createNamespaceEndpoint = Endpoint(RoutePattern.POST / "namespaces" ?? Doc.p("Create namespace"))
    .auth(AuthType.Bearer)
    .in[NamespacesRepository.DTO.CreateNamespace]
    .out[NamespacesRepository.DTO.Namespace](Status.Created)
    .outErrors[BaseError](
      HttpCodec.error[AppError](Status.BadRequest),
      HttpCodec.error[AuthenticationError](Status.Unauthorized)
    )

  private val updateNamespaceEndpoint =
    Endpoint(RoutePattern.PATCH / "namespaces" / PathCodec.uuid("id") ?? Doc.p("Update namespace"))
      .auth(AuthType.Bearer)
      .in[NamespacesRepository.DTO.UpdateNamespace]
      .out[NamespacesRepository.DTO.Namespace]
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
    withContext((user: User) => user.toString)

  private def getNamespaces = getNamespacesEndpoint.implement: (_: Unit) =>
    withContext((u: User) => NamespacesRepository.findByUser(u)).mapError(toAppError)

  private def getNamespace = getNamespaceEndpoint.implement: (id: NamespaceID) =>
    withContext: (user: User) =>
      NamespacesRepository
        .findById(id, user.id)
        .mapError(toAppError)
        .flatMap:
          case Some(ns) => ZIO.succeed(ns)
          case None     => ZIO.fail(BaseError.AppError(s"Namespace not found: $id"))

  private def createNamespace = createNamespaceEndpoint.implement: (data: NamespacesRepository.DTO.CreateNamespace) =>
    withContext: (user: User) =>
      NamespacesRepository.create(data, user.id).mapError(toAppError)

  private def updateNamespace = updateNamespaceEndpoint.implement:
    (id: NamespaceID, data: NamespacesRepository.DTO.UpdateNamespace) =>
      withContext: (user: User) =>
        NamespacesRepository
          .update(id, data, user.id)
          .mapError(toAppError)
          .flatMap:
            case Some(ns) => ZIO.succeed(ns)
            case None     => ZIO.fail(BaseError.AppError(s"Namespace not found or not owned: $id"))

  private def deleteNamespace = deleteNamespaceEndpoint.implement: (id: UUID) =>
    withContext: (user: User) =>
      NamespacesRepository
        .delete(id, user.id)
        .mapError(toAppError)
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
