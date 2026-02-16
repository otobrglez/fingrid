package fingrid.service.clients

import fingrid.service.AppConfig
import zio.ZIO.{logInfo, serviceWithZIO}
import zio.http.{Client, Request, URL}
import zio.*
import zio.json.*

import java.math.BigInteger
import scala.io.AnsiColor.*
import java.security.spec.RSAPublicKeySpec
import java.security.{KeyFactory, PublicKey}
import java.util.Base64
import zio.schema.codec.JsonCodec.zioJsonBinaryCodec

final case class Key(
  kid: String,
  kty: String,
  alg: String,
  use: String,
  x5c: List[String],
  x5t: String,
  `x5t#S256`: String,
  n: String,
  e: String
) derives JsonCodec

final case class Certs(
  keys: List[Key]
) derives JsonCodec

object Certs:
  val empty: Certs = Certs(List.empty)

final class Keycloak private (
  private val client: Client,
  private val endpoint: URL,
  private val realm: String,
  private val currentCerts: Ref[Certs]
):
  private val base64UrlDecode: String => Array[Byte] = Base64.getUrlDecoder.decode
  private val keyFactory: KeyFactory                 = KeyFactory.getInstance("RSA")
  private def certs: UIO[Certs]                      = currentCerts.get

  private def rs256Key: Task[PublicKey] = certs
    .map(_.keys.find(_.alg == "RS256").get)
    .flatMap: key =>
      ZIO.attempt:
        val spec = new RSAPublicKeySpec(
          new BigInteger(1, base64UrlDecode(key.n)),
          new BigInteger(1, base64UrlDecode(key.e))
        )
        keyFactory.generatePublic(spec)

  private def refreshCerts = for
    _        <- logInfo(s"Refreshing certs from endpoint $endpoint, realm ${BOLD}${RED}$realm${RESET}")
    request   = Request.get(endpoint.addPath(s"/realms/$realm/protocol/openid-connect/certs"))
    response <- client.request(request)
    certs    <- response.bodyAs[Certs]
    _        <- currentCerts.set(certs) <* ZIO.logInfo(s"Certs refreshed successfully.")
  yield ()

object Keycloak:
  private val refreshInterval = Duration.fromSeconds(4 * 60L) // 4 minutes

  def certs: URIO[Keycloak, Certs]       = serviceWithZIO[Keycloak](_.certs)
  def rs256Key: RIO[Keycloak, PublicKey] = serviceWithZIO[Keycloak](_.rs256Key)

  private def make = for
    client            <- ZIO.service[Client]
    (endpoint, realm) <- ZIO.serviceWith[AppConfig](c => c.keycloakUrl -> c.keycloakRealm)
    endpointURL       <- ZIO.getOrFail(URL.fromURI(endpoint))
    certsRef          <- Ref.make(Certs.empty)
    keycloak           = new Keycloak(client, endpointURL, realm, certsRef)
    _                 <- keycloak.refreshCerts
    refreshFib        <-
      keycloak.refreshCerts
        .repeat(Schedule.spaced(refreshInterval))
        .delay(refreshInterval)
        .fork
    _                 <- Scope.addFinalizer(refreshFib.interrupt <* logInfo("Keycloak client refreshing stopped."))
  yield keycloak

  def live: RLayer[Scope & Client & AppConfig, Keycloak] = ZLayer.fromZIO(make)
  def liveScoped: RLayer[Client & AppConfig, Keycloak]   = ZLayer.scoped(make)
