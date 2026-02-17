package fingrid.service.services

import fingrid.persistence.entities
import fingrid.persistence.entities.User
import org.hibernate.Session
import zio.RIO
import zio.hibernate.Hibernate
import zio.hibernate.syntax.*

import java.util.UUID

object UsersRepository:
  private def generateColorFromEmail(email: String): String =
    val hash = email.hashCode.abs
    val r    = (hash & 0xff0000) >> 16
    val g    = (hash & 0x00ff00) >> 8
    val b    = hash & 0x0000ff
    f"#$r%02X$g%02X$b%02X"

  private def createUser(session: Session, keycloakId: UUID, email: String, username: String) =
    val rgbColor     = generateColorFromEmail(email)
    val passwordHash = "0" * 60 // Keycloak handles auth, no password needed

    val newUser = entities.User(keycloakId, username, email, rgbColor, passwordHash)
    session.persist(newUser)
    session.flush()
    newUser

  def findOrCreate(
    keycloakId: UUID,
    email: String,
    username: String
  ): RIO[Hibernate, User] = Hibernate.attemptInTransaction: session =>
    session
      .maybeFind[entities.User](keycloakId)
      .fold(createUser(session, keycloakId, email, username))(identity)
