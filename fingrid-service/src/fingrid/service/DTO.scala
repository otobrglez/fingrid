package fingrid.service

import fingrid.persistence.entities
import fingrid.persistence.entities.Namespace
import zio.hibernate.Hibernate
import zio.hibernate.syntax.*
import zio.{RIO, ZLayer}
import zio.schema.{DeriveSchema, Schema}

import java.util.UUID
import scala.jdk.CollectionConverters.*

object DTO:
  final case class Namespace(id: UUID, name: String, deleted: Boolean)
  object Namespace:
    given schema: Schema[Namespace] = DeriveSchema.gen

  final case class CreateNamespace(name: String)
  object CreateNamespace:
    given schema: Schema[CreateNamespace] = DeriveSchema.gen

  final case class UpdateNamespace(name: String)
  object UpdateNamespace:
    given schema: Schema[UpdateNamespace] = DeriveSchema.gen

object Mapping:
  given namespaceDBtoDTO: Conversion[entities.Namespace, DTO.Namespace] = ns =>
    DTO.Namespace(ns.id, ns.name, ns.deleted)

type UserID      = UUID
type NamespaceID = UUID

trait UserScopedRepository[T, R]:
  def findByAuth(authUser: AuthUser): RIO[R & Hibernate, List[T]] = findByUser(authUser.userID)
  def findByUser(id: UserID): RIO[R & Hibernate, List[T]]

object NamespacesRepository extends UserScopedRepository[DTO.Namespace, Any]:
  import Mapping.given

  def findByUser(id: UserID): RIO[Hibernate, List[DTO.Namespace]] =
    Hibernate.attemptInTransaction:
      _.createQuery[entities.Namespace](
        """FROM fingrid.persistence.entities.Namespace n
           WHERE n.owner.id = :user_id
           OR :user_id IN (SELECT c.id FROM n.collaborators c)"""
      )
        .setParameter("user_id", id)
        .getResultList
        .asScala
        .toList
        .map(namespaceDBtoDTO)

  def findById(id: NamespaceID, userId: UserID): RIO[Hibernate, Option[DTO.Namespace]] =
    Hibernate.attemptInTransaction: session =>
      Option(
        session
          .createQuery[entities.Namespace](
            """FROM fingrid.persistence.entities.Namespace n
               WHERE n.id = :namespace_id
               AND (n.owner.id = :user_id OR :user_id IN (SELECT c.id FROM n.collaborators c))"""
          )
          .setParameter("namespace_id", id)
          .setParameter("user_id", userId)
          .uniqueResult()
      ).map(namespaceDBtoDTO)

  def create(data: DTO.CreateNamespace, userId: UserID): RIO[Hibernate, DTO.Namespace] =
    Hibernate.attemptInTransaction: session =>
      val user   = session.getReference(classOf[entities.User], userId)
      val entity = entities.Namespace(data.name, user)
      session.persist(entity)
      session.flush()
      namespaceDBtoDTO(entity)

  def update(id: NamespaceID, data: DTO.UpdateNamespace, userId: UserID): RIO[Hibernate, Option[DTO.Namespace]] =
    Hibernate.attemptInTransaction: session =>
      Option(
        session
          .createQuery[entities.Namespace](
            """FROM fingrid.persistence.entities.Namespace n
               WHERE n.id = :namespace_id AND n.owner.id = :user_id"""
          )
          .setParameter("namespace_id", id)
          .setParameter("user_id", userId)
          .uniqueResult()
      ).map: entity =>
        entity.name = data.name
        session.merge(entity)
        session.flush()
        namespaceDBtoDTO(entity)

  def delete(id: NamespaceID, userId: UserID): RIO[Hibernate, Boolean] =
    Hibernate.attemptInTransaction: session =>
      val updated = session
        .createQuery(
          """UPDATE fingrid.persistence.entities.Namespace n
             SET n.deleted = true
             WHERE n.id = :namespace_id AND n.owner.id = :user_id"""
        )
        .setParameter("namespace_id", id)
        .setParameter("user_id", userId)
        .executeUpdate()
      updated > 0
