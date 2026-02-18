package fingrid.service

import fingrid.persistence.entities
import jakarta.persistence.EntityManagerFactory
import zio.*
import zio.hibernate.Hibernate
import zio.hibernate.syntax.*
import zio.logging.backend.SLF4J
import zio.test.*
import fingrid.service.services.{CategoriesRepository, NamespacesRepository}
import CategoriesRepository.DTO
import fingrid.persistence.entities.{Category, Namespace, User}

import java.util.UUID

object CategoriesRepositoryTest extends ZIOSpecDefault:

  override val bootstrap: ZLayer[Any, Any, TestEnvironment] =
    Runtime.setConfigProvider(ConfigProvider.envProvider) >>>
      Runtime.removeDefaultLoggers >>>
      SLF4J.slf4j >>>
      testEnvironment

  // Helper to create a test user with unique email
  private def createTestUser(username: String, email: String): RIO[Hibernate, UUID] =
    Hibernate.attemptInTransaction: session =>
      val userId = UUID.randomUUID()
      // Add UUID suffix to email to ensure uniqueness across all test files
      val uniqueEmail = email.replace("@", s"+${userId.toString.take(8)}@")
      val user        = entities.User(userId, username, uniqueEmail, "#FF0000", "0" * 60)
      session.persist(user)
      session.flush()
      user.id

  // Helper to create a namespace
  private def createNamespace(name: String, ownerId: UUID): RIO[Hibernate, UUID] =
    Hibernate.attemptInTransaction: session =>
      val owner     = session.getReference(classOf[entities.User], ownerId)
      val namespace = entities.Namespace(name, owner)
      session.persist(namespace)
      session.flush()
      namespace.id

  // Helper to add collaborator to namespace
  private def addCollaborator(namespaceId: UUID, userId: UUID): RIO[Hibernate, Unit] =
    Hibernate.attemptInTransaction: session =>
      for
        namespace <- session.maybeFind[entities.Namespace](namespaceId)
        user      <- session.maybeFind[entities.User](userId)
      yield
        namespace.collaborators.add(user)
        session.merge(namespace)
        session.flush()

  def spec = suite("CategoriesRepositoryTest")(
    test("create - should create a new category in a namespace") {
      for
        userId      <- createTestUser("alice", "alice@example.com")
        namespaceId <- createNamespace("Alice's Namespace", userId)
        data         = DTO.CreateCategory("Groceries", namespaceId)
        result      <- CategoriesRepository.create(data, userId)
      yield assertTrue(
        result.name == "Groceries",
        result.namespaceId == namespaceId,
        result.deletedAt.isEmpty,
        result.id != null
      )
    },
    test("create - should fail if namespace doesn't exist") {
      for
        userId <- createTestUser("bob", "bob@example.com")
        data    = DTO.CreateCategory("Food", UUID.randomUUID())
        result <- CategoriesRepository.create(data, userId).exit
      yield assertTrue(result.isFailure)
    },
    test("create - should fail if user doesn't have access to namespace") {
      for
        ownerId     <- createTestUser("charlie", "charlie@example.com")
        otherId     <- createTestUser("dave", "dave@example.com")
        namespaceId <- createNamespace("Charlie's Namespace", ownerId)
        data         = DTO.CreateCategory("Forbidden", namespaceId)
        result      <- CategoriesRepository.create(data, otherId).exit
      yield assertTrue(result.isFailure)
    },
    test("create - should fail with duplicate category name in same namespace (case-insensitive)") {
      for
        userId      <- createTestUser("eve", "eve@example.com")
        namespaceId <- createNamespace("Eve's Namespace", userId)
        _           <- CategoriesRepository.create(DTO.CreateCategory("Food", namespaceId), userId)
        result      <- CategoriesRepository.create(DTO.CreateCategory("food", namespaceId), userId).exit
      yield assertTrue(result.isFailure)
    },
    test("create - should allow same category name in different namespaces") {
      for
        userId       <- createTestUser("frank", "frank@example.com")
        namespace1Id <- createNamespace("NS1", userId)
        namespace2Id <- createNamespace("NS2", userId)
        cat1         <- CategoriesRepository.create(DTO.CreateCategory("Food", namespace1Id), userId)
        cat2         <- CategoriesRepository.create(DTO.CreateCategory("Food", namespace2Id), userId)
      yield assertTrue(
        cat1.name == "Food",
        cat2.name == "Food",
        cat1.namespaceId == namespace1Id,
        cat2.namespaceId == namespace2Id,
        cat1.id != cat2.id
      )
    },
    test("findByNamespace - should find all categories in a namespace") {
      for
        userId      <- createTestUser("grace", "grace@example.com")
        namespaceId <- createNamespace("Grace's Namespace", userId)
        _           <- CategoriesRepository.create(DTO.CreateCategory("Food", namespaceId), userId)
        _           <- CategoriesRepository.create(DTO.CreateCategory("Travel", namespaceId), userId)
        _           <- CategoriesRepository.create(DTO.CreateCategory("Entertainment", namespaceId), userId)
        found       <- CategoriesRepository.findByNamespace(namespaceId, userId)
      yield assertTrue(
        found.length == 3,
        found.map(_.name).toSet == Set("Food", "Travel", "Entertainment")
      )
    },
    test("findByNamespace - should fail if user doesn't have access to namespace") {
      for
        ownerId     <- createTestUser("henry", "henry@example.com")
        otherId     <- createTestUser("iris", "iris@example.com")
        namespaceId <- createNamespace("Henry's Namespace", ownerId)
        result      <- CategoriesRepository.findByNamespace(namespaceId, otherId).exit
      yield assertTrue(result.isFailure)
    },
    test("findByNamespace - should work for collaborators") {
      for
        ownerId     <- createTestUser("jack", "jack@example.com")
        collabId    <- createTestUser("kate", "kate@example.com")
        namespaceId <- createNamespace("Shared Namespace", ownerId)
        _           <- CategoriesRepository.create(DTO.CreateCategory("Shared Cat", namespaceId), ownerId)
        _           <- addCollaborator(namespaceId, collabId)
        found       <- CategoriesRepository.findByNamespace(namespaceId, collabId)
      yield assertTrue(
        found.length == 1,
        found.head.name == "Shared Cat"
      )
    },
    test("findById - should find category by id for owner") {
      for
        userId      <- createTestUser("leo", "leo@example.com")
        namespaceId <- createNamespace("Leo's Namespace", userId)
        created     <- CategoriesRepository.create(DTO.CreateCategory("Food", namespaceId), userId)
        found       <- CategoriesRepository.findById(created.id, userId)
      yield assertTrue(
        found.isDefined,
        found.get.id == created.id,
        found.get.name == "Food"
      )
    },
    test("findById - should find category for collaborator") {
      for
        ownerId     <- createTestUser("maya", "maya@example.com")
        collabId    <- createTestUser("nina", "nina@example.com")
        namespaceId <- createNamespace("Shared NS", ownerId)
        created     <- CategoriesRepository.create(DTO.CreateCategory("Shared", namespaceId), ownerId)
        _           <- addCollaborator(namespaceId, collabId)
        found       <- CategoriesRepository.findById(created.id, collabId)
      yield assertTrue(
        found.isDefined,
        found.get.id == created.id
      )
    },
    test("findById - should return None for non-existent category") {
      for
        userId <- createTestUser("oscar", "oscar@example.com")
        found  <- CategoriesRepository.findById(UUID.randomUUID(), userId)
      yield assertTrue(found.isEmpty)
    },
    test("findById - should return None for unauthorized user") {
      for
        ownerId     <- createTestUser("paul", "paul@example.com")
        otherId     <- createTestUser("quinn", "quinn@example.com")
        namespaceId <- createNamespace("Paul's NS", ownerId)
        created     <- CategoriesRepository.create(DTO.CreateCategory("Private", namespaceId), ownerId)
        found       <- CategoriesRepository.findById(created.id, otherId)
      yield assertTrue(found.isEmpty)
    },
    test("update - should update category name for owner") {
      for
        userId      <- createTestUser("rachel", "rachel@example.com")
        namespaceId <- createNamespace("Rachel's NS", userId)
        created     <- CategoriesRepository.create(DTO.CreateCategory("Old Name", namespaceId), userId)
        updated     <- CategoriesRepository.update(created.id, DTO.UpdateCategory("New Name"), userId)
      yield assertTrue(
        updated.isDefined,
        updated.get.id == created.id,
        updated.get.name == "New Name"
      )
    },
    test("update - should return None for non-owner") {
      for
        ownerId     <- createTestUser("sam", "sam@example.com")
        collabId    <- createTestUser("tom", "tom@example.com")
        namespaceId <- createNamespace("Sam's NS", ownerId)
        created     <- CategoriesRepository.create(DTO.CreateCategory("Original", namespaceId), ownerId)
        _           <- addCollaborator(namespaceId, collabId)
        updated     <- CategoriesRepository.update(created.id, DTO.UpdateCategory("Hacked"), collabId)
      yield assertTrue(updated.isEmpty)
    },
    test("update - should return None for non-existent category") {
      for
        userId  <- createTestUser("uma", "uma@example.com")
        updated <- CategoriesRepository.update(UUID.randomUUID(), DTO.UpdateCategory("New"), userId)
      yield assertTrue(updated.isEmpty)
    },
    test("update - should fail when new name conflicts with existing category") {
      for
        userId      <- createTestUser("victor", "victor@example.com")
        namespaceId <- createNamespace("Victor's NS", userId)
        cat1        <- CategoriesRepository.create(DTO.CreateCategory("Food", namespaceId), userId)
        cat2        <- CategoriesRepository.create(DTO.CreateCategory("Travel", namespaceId), userId)
        result      <- CategoriesRepository.update(cat2.id, DTO.UpdateCategory("Food"), userId).exit
      yield assertTrue(result.isFailure)
    },
    test("update - should fail with case-insensitive conflict") {
      for
        userId      <- createTestUser("wendy", "wendy@example.com")
        namespaceId <- createNamespace("Wendy's NS", userId)
        cat1        <- CategoriesRepository.create(DTO.CreateCategory("Food", namespaceId), userId)
        cat2        <- CategoriesRepository.create(DTO.CreateCategory("Travel", namespaceId), userId)
        result      <- CategoriesRepository.update(cat2.id, DTO.UpdateCategory("food"), userId).exit
      yield assertTrue(result.isFailure)
    },
    test("update - should allow renaming to same name (same category)") {
      for
        userId      <- createTestUser("xander", "xander@example.com")
        namespaceId <- createNamespace("Xander's NS", userId)
        category    <- CategoriesRepository.create(DTO.CreateCategory("SameName", namespaceId), userId)
        updated     <- CategoriesRepository.update(category.id, DTO.UpdateCategory("SameName"), userId)
      yield assertTrue(
        updated.isDefined,
        updated.get.name == "SameName"
      )
    },
    test("update - should allow renaming to same name with different case") {
      for
        userId      <- createTestUser("yara", "yara@example.com")
        namespaceId <- createNamespace("Yara's NS", userId)
        category    <- CategoriesRepository.create(DTO.CreateCategory("food", namespaceId), userId)
        updated     <- CategoriesRepository.update(category.id, DTO.UpdateCategory("Food"), userId)
      yield assertTrue(
        updated.isDefined,
        updated.get.name == "Food"
      )
    },
    test("delete - should soft delete category for owner") {
      for
        userId      <- createTestUser("zara", "zara@example.com")
        namespaceId <- createNamespace("Zara's NS", userId)
        created     <- CategoriesRepository.create(DTO.CreateCategory("To Delete", namespaceId), userId)
        deleted     <- CategoriesRepository.delete(created.id, userId)
        // After deletion, the entity should not be found with the filter active
        found       <- CategoriesRepository.findById(created.id, userId)
        // Verify it's actually soft deleted by checking without filter
        softDeleted <- Hibernate.attemptInTransaction: session =>
                         val entity = session.find(classOf[entities.Category], created.id)
                         Option(entity).map(_.deletedAt != null)
      yield assertTrue(
        deleted,
        found.isEmpty, // Should not be found with filter active
        softDeleted.contains(true) // But should exist with deletedAt set
      )
    },
    test("delete - should return false for non-owner") {
      for
        ownerId     <- createTestUser("adam", "adam@example.com")
        collabId    <- createTestUser("beth", "beth@example.com")
        namespaceId <- createNamespace("Adam's NS", ownerId)
        created     <- CategoriesRepository.create(DTO.CreateCategory("Protected", namespaceId), ownerId)
        _           <- addCollaborator(namespaceId, collabId)
        deleted     <- CategoriesRepository.delete(created.id, collabId)
      yield assertTrue(!deleted)
    },
    test("delete - should return false for non-existent category") {
      for
        userId  <- createTestUser("carl", "carl@example.com")
        deleted <- CategoriesRepository.delete(UUID.randomUUID(), userId)
      yield assertTrue(!deleted)
    }
  ).provideShared(
    Scope.default,
    TestPersistenceLayer.live >>> Hibernate.live
  ) @@ TestAspect.sequential
    @@ TestAspect.withLiveSystem
    @@ TestAspect.withLiveClock
    @@ TestAspect.silentLogging
