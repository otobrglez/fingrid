package fingrid.service.services

import fingrid.persistence.entities
import fingrid.persistence.entities.Category
import zio.RIO
import zio.hibernate.Hibernate
import zio.hibernate.syntax.*
import zio.schema.{DeriveSchema, Schema}

import java.time.Instant
import java.util.UUID

object CategoriesRepository:
  object DTO:
    final case class Category(id: UUID, name: String, namespaceId: UUID, deletedAt: Option[Instant])

    object Category:
      given schema: Schema[Category] = DeriveSchema.gen

    final case class CreateCategory(name: String, namespaceId: UUID)

    object CreateCategory:
      given schema: Schema[CreateCategory] = DeriveSchema.gen

    final case class UpdateCategory(name: String)

    object UpdateCategory:
      given schema: Schema[UpdateCategory] = DeriveSchema.gen

  given categoryDBtoDTO: Conversion[entities.Category, DTO.Category] = c =>
    DTO.Category(c.id, c.name, c.namespace.id, Option(c.deletedAt))

  def findByNamespace(namespaceId: NamespaceID, userId: UserID): RIO[Hibernate, List[DTO.Category]] =
    Hibernate.attemptInTransaction: session =>
      session.enableFilter("deletedCategoryFilter")
      session.enableFilter("deletedNamespaceFilter")

      // First verify the user has access to the namespace
      val hasAccess = session
        .createQuery[entities.Namespace](
          """FROM fingrid.persistence.entities.Namespace n
             WHERE n.id = :namespace_id
             AND (n.owner.id = :user_id OR :user_id IN (SELECT c.id FROM n.collaborators c))"""
        )
        .setParameter("namespace_id", namespaceId)
        .setParameter("user_id", userId)
        .maybeUniqueResult
        .isDefined

      if !hasAccess then
        throw new IllegalArgumentException(s"User does not have access to namespace: $namespaceId")

      session
        .createQuery[entities.Category](
          """FROM fingrid.persistence.entities.Category c
             WHERE c.namespace.id = :namespace_id"""
        )
        .setParameter("namespace_id", namespaceId)
        .asList
        .map(categoryDBtoDTO)

  def findById(id: CategoryID, userId: UserID): RIO[Hibernate, Option[DTO.Category]] =
    Hibernate.attemptInTransaction: session =>
      session.enableFilter("deletedCategoryFilter")
      session.enableFilter("deletedNamespaceFilter")
      session
        .createQuery[entities.Category](
          """FROM fingrid.persistence.entities.Category c
             WHERE c.id = :category_id
             AND (c.namespace.owner.id = :user_id
                  OR :user_id IN (SELECT collab.id FROM c.namespace.collaborators collab))"""
        )
        .setParameter("category_id", id)
        .setParameter("user_id", userId)
        .maybeUniqueResult
        .map(categoryDBtoDTO)

  def create(data: DTO.CreateCategory, userId: UserID): RIO[Hibernate, DTO.Category] =
    Hibernate.attemptInTransaction: session =>
      session.enableFilter("deletedCategoryFilter")
      session.enableFilter("deletedNamespaceFilter")

      // Verify the user has access to the namespace
      val namespace = session
        .createQuery[entities.Namespace](
          """FROM fingrid.persistence.entities.Namespace n
             WHERE n.id = :namespace_id
             AND (n.owner.id = :user_id OR :user_id IN (SELECT c.id FROM n.collaborators c))"""
        )
        .setParameter("namespace_id", data.namespaceId)
        .setParameter("user_id", userId)
        .maybeUniqueResult

      if namespace.isEmpty then
        throw new IllegalArgumentException(
          s"Namespace not found or user does not have access: ${data.namespaceId}"
        )

      // Check if a category with the same name already exists in this namespace (case-insensitive)
      val existing = session
        .createQuery[entities.Category](
          """FROM fingrid.persistence.entities.Category c
             WHERE LOWER(c.name) = LOWER(:name) AND c.namespace.id = :namespace_id"""
        )
        .setParameter("name", data.name)
        .setParameter("namespace_id", data.namespaceId)
        .maybeUniqueResult

      if existing.isDefined then
        throw new IllegalArgumentException(
          s"Category with name '${data.name}' already exists in this namespace"
        )

      val entity = entities.Category(data.name, namespace.get)
      session.persist(entity)
      session.flush()
      categoryDBtoDTO(entity)

  def update(id: CategoryID, data: DTO.UpdateCategory, userId: UserID): RIO[Hibernate, Option[DTO.Category]] =
    Hibernate.attemptInTransaction: session =>
      session.enableFilter("deletedCategoryFilter")
      session.enableFilter("deletedNamespaceFilter")

      // Get the category and verify access
      val maybeCategory = session
        .createQuery[entities.Category](
          """FROM fingrid.persistence.entities.Category c
             WHERE c.id = :category_id
             AND c.namespace.owner.id = :user_id"""
        )
        .setParameter("category_id", id)
        .setParameter("user_id", userId)
        .maybeUniqueResult

      maybeCategory.map: category =>
        // Check if another category with the same name exists in this namespace (case-insensitive)
        val existing = session
          .createQuery[entities.Category](
            """FROM fingrid.persistence.entities.Category c
               WHERE LOWER(c.name) = LOWER(:name)
               AND c.namespace.id = :namespace_id
               AND c.id != :category_id"""
          )
          .setParameter("name", data.name)
          .setParameter("namespace_id", category.namespace.id)
          .setParameter("category_id", id)
          .maybeUniqueResult

        if existing.isDefined then
          throw new IllegalArgumentException(
            s"Category with name '${data.name}' already exists in this namespace"
          )

        category.name = data.name
        session.merge(category)
        session.flush()
        categoryDBtoDTO(category)

  def delete(id: CategoryID, userId: UserID): RIO[Hibernate, Boolean] =
    Hibernate.attemptInTransaction: session =>
      session.enableFilter("deletedCategoryFilter")
      session.enableFilter("deletedNamespaceFilter")

      val maybeCategory = session
        .createQuery[entities.Category](
          """FROM fingrid.persistence.entities.Category c
             WHERE c.id = :category_id AND c.namespace.owner.id = :user_id"""
        )
        .setParameter("category_id", id)
        .setParameter("user_id", userId)
        .maybeUniqueResult

      maybeCategory match
        case None           => false
        case Some(category) =>
          session.remove(category)
          session.flush()
          true
