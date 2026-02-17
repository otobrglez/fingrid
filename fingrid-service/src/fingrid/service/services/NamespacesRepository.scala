package fingrid.service.services

import fingrid.persistence.entities
import fingrid.persistence.entities.Namespace
import fingrid.service.authentication.AuthUser
import zio.hibernate.Hibernate
import zio.hibernate.syntax.*
import zio.schema.{DeriveSchema, Schema}
import zio.{RIO, ZLayer}

import java.time.Instant
import java.util.UUID
import scala.jdk.CollectionConverters.*

object NamespacesRepository extends UserScopedRepository[NamespacesRepository.DTO.Namespace, Any]:
  object DTO:
    final case class Namespace(id: UUID, name: String, deletedAt: Option[Instant])

    object Namespace:
      given schema: Schema[Namespace] = DeriveSchema.gen

    final case class CreateNamespace(name: String)

    object CreateNamespace:
      given schema: Schema[CreateNamespace] = DeriveSchema.gen

    final case class UpdateNamespace(name: String)

    object UpdateNamespace:
      given schema: Schema[UpdateNamespace] = DeriveSchema.gen

  given namespaceDBtoDTO: Conversion[entities.Namespace, DTO.Namespace] = ns =>
    DTO.Namespace(ns.id, ns.name, Option(ns.deletedAt))

  def findByUser(id: UserID): RIO[Hibernate, List[DTO.Namespace]] =
    Hibernate.attemptInTransaction: session =>
      session.enableFilter("deletedNamespaceFilter")
      session
        .createQuery[entities.Namespace](
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
      session.enableFilter("deletedNamespaceFilter")
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
      session.enableFilter("deletedNamespaceFilter")

      // Check if a namespace with the same name already exists for this user (case-insensitive)
      val existing = Option(
        session
          .createQuery[entities.Namespace](
            """FROM fingrid.persistence.entities.Namespace n
               WHERE LOWER(n.name) = LOWER(:name) AND n.owner.id = :user_id"""
          )
          .setParameter("name", data.name)
          .setParameter("user_id", userId)
          .uniqueResult()
      )

      if existing.isDefined then
        throw new IllegalArgumentException(s"Namespace with name '${data.name}' already exists")

      val user   = session.getReference(classOf[entities.User], userId)
      val entity = entities.Namespace(data.name, user)
      session.persist(entity)
      session.flush()
      namespaceDBtoDTO(entity)

  def update(id: NamespaceID, data: DTO.UpdateNamespace, userId: UserID): RIO[Hibernate, Option[DTO.Namespace]] =
    Hibernate.attemptInTransaction: session =>
      session.enableFilter("deletedNamespaceFilter")

      // Check if another namespace with the same name exists for this user (case-insensitive)
      val existing = Option(
        session
          .createQuery[entities.Namespace](
            """FROM fingrid.persistence.entities.Namespace n
               WHERE LOWER(n.name) = LOWER(:name) AND n.owner.id = :user_id AND n.id != :namespace_id"""
          )
          .setParameter("name", data.name)
          .setParameter("user_id", userId)
          .setParameter("namespace_id", id)
          .uniqueResult()
      )

      if existing.isDefined then
        throw new IllegalArgumentException(s"Namespace with name '${data.name}' already exists")

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
      session.enableFilter("deletedNamespaceFilter")
      Option(
        session
          .createQuery[entities.Namespace](
            """FROM fingrid.persistence.entities.Namespace n
               WHERE n.id = :namespace_id AND n.owner.id = :user_id"""
          )
          .setParameter("namespace_id", id)
          .setParameter("user_id", userId)
          .uniqueResult()
      ) match
        case Some(entity) =>
          session.remove(entity)
          session.flush()
          true
        case None         => false
