package fingrid.service

import fingrid.persistence.entities
import jakarta.persistence.EntityManagerFactory
import zio.*
import zio.hibernate.Hibernate
import zio.hibernate.syntax.*
import zio.logging.backend.SLF4J
import zio.test.*
import fingrid.service.services.NamespacesRepository
import NamespacesRepository.DTO
import fingrid.persistence.entities.{Namespace, User}

import java.util.UUID

object NamespacesRepositoryTest extends ZIOSpecDefault:

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
      val user   = entities.User(userId, username, uniqueEmail, "#FF0000", "0" * 60)
      session.persist(user)
      session.flush()
      user.id

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

  def spec = suite("NamespacesRepositoryTest")(
    test("create - should create a new namespace for a user") {
      for
        userId <- createTestUser("alice", "alice@example.com")
        data    = DTO.CreateNamespace("Test Namespace")
        result <- NamespacesRepository.create(data, userId)
      yield assertTrue(
        result.name == "Test Namespace",
        result.deletedAt.isEmpty,
        result.id != null
      )
    },
    test("create - should create two default categories (Income and Expense) for new namespace") {
      for
        userId     <- createTestUser("bob_cat", "bob_cat@example.com")
        data        = DTO.CreateNamespace("Bob's Namespace")
        namespace  <- NamespacesRepository.create(data, userId)
        // Verify categories were created by querying them
        categories <- Hibernate.attemptInTransaction: session =>
                        session.enableFilter("deletedCategoryFilter")
                        session
                          .createQuery[entities.Category](
                            """FROM fingrid.persistence.entities.Category c
                               WHERE c.namespace.id = :namespace_id
                               ORDER BY c.name"""
                          )
                          .setParameter("namespace_id", namespace.id)
                          .asList
      yield assertTrue(
        categories.length == 2,
        categories(0).name == "Expense",
        categories(1).name == "Income"
      )
    },
    test("findById - should find namespace by id for owner") {
      for
        userId  <- createTestUser("bob", "bob@example.com")
        data     = DTO.CreateNamespace("Bob's Namespace")
        created <- NamespacesRepository.create(data, userId)
        found   <- NamespacesRepository.findById(created.id, userId)
      yield assertTrue(
        found.isDefined,
        found.get.id == created.id,
        found.get.name == "Bob's Namespace"
      )
    },
    test("findById - should find namespace by id for collaborator") {
      for
        ownerId  <- createTestUser("charlie", "charlie@example.com")
        collabId <- createTestUser("dave", "dave@example.com")
        data      = DTO.CreateNamespace("Shared Namespace")
        created  <- NamespacesRepository.create(data, ownerId)
        _        <- addCollaborator(created.id, collabId)
        found    <- NamespacesRepository.findById(created.id, collabId)
      yield assertTrue(
        found.isDefined,
        found.get.id == created.id,
        found.get.name == "Shared Namespace"
      )
    },
    test("findById - should return None for non-existent namespace") {
      for
        userId <- createTestUser("eve", "eve@example.com")
        found  <- NamespacesRepository.findById(UUID.randomUUID(), userId)
      yield assertTrue(found.isEmpty)
    },
    test("findById - should return None for unauthorized user") {
      for
        ownerId <- createTestUser("frank", "frank@example.com")
        otherId <- createTestUser("grace", "grace@example.com")
        data     = DTO.CreateNamespace("Frank's Private Namespace")
        created <- NamespacesRepository.create(data, ownerId)
        found   <- NamespacesRepository.findById(created.id, otherId)
      yield assertTrue(found.isEmpty)
    },
    test("findByUser - should find all owned namespaces") {
      for
        userId <- createTestUser("henry", "henry@example.com")
        _      <- NamespacesRepository.create(DTO.CreateNamespace("NS1"), userId)
        _      <- NamespacesRepository.create(DTO.CreateNamespace("NS2"), userId)
        _      <- NamespacesRepository.create(DTO.CreateNamespace("NS3"), userId)
        found  <- NamespacesRepository.findByUser(userId)
      yield assertTrue(
        found.length == 3,
        found.map(_.name).toSet == Set("NS1", "NS2", "NS3")
      )
    },
    test("findByUser - should find both owned and collaborating namespaces") {
      for
        ownerId  <- createTestUser("iris", "iris@example.com")
        collabId <- createTestUser("jack", "jack@example.com")
        owned1   <- NamespacesRepository.create(DTO.CreateNamespace("Jack's Own"), collabId)
        owned2   <- NamespacesRepository.create(DTO.CreateNamespace("Iris's NS"), ownerId)
        _        <- addCollaborator(owned2.id, collabId)
        found    <- NamespacesRepository.findByUser(collabId)
      yield assertTrue(
        found.length == 2,
        found.map(_.name).toSet == Set("Jack's Own", "Iris's NS")
      )
    },
    test("update - should update namespace name for owner") {
      for
        userId  <- createTestUser("kate", "kate@example.com")
        created <- NamespacesRepository.create(DTO.CreateNamespace("Original Name"), userId)
        updated <- NamespacesRepository.update(created.id, DTO.UpdateNamespace("Updated Name"), userId)
      yield assertTrue(
        updated.isDefined,
        updated.get.id == created.id,
        updated.get.name == "Updated Name"
      )
    },
    test("update - should return None for non-owner") {
      for
        ownerId  <- createTestUser("leo", "leo@example.com")
        collabId <- createTestUser("maya", "maya@example.com")
        created  <- NamespacesRepository.create(DTO.CreateNamespace("Leo's NS"), ownerId)
        _        <- addCollaborator(created.id, collabId)
        updated  <- NamespacesRepository.update(created.id, DTO.UpdateNamespace("Hacked"), collabId)
      yield assertTrue(updated.isEmpty)
    },
    test("update - should return None for non-existent namespace") {
      for
        userId  <- createTestUser("nina", "nina@example.com")
        updated <- NamespacesRepository.update(UUID.randomUUID(), DTO.UpdateNamespace("New Name"), userId)
      yield assertTrue(updated.isEmpty)
    },
    test("delete - should soft delete namespace for owner") {
      for
        userId      <- createTestUser("oscar", "oscar@example.com")
        created     <- NamespacesRepository.create(DTO.CreateNamespace("To Delete"), userId)
        deleted     <- NamespacesRepository.delete(created.id, userId)
        // After deletion, the entity should not be found with the filter active
        found       <- NamespacesRepository.findById(created.id, userId)
        // Verify it's actually soft deleted by checking without filter
        softDeleted <- Hibernate.attemptInTransaction: session =>
                         val entity = session.find(classOf[entities.Namespace], created.id)
                         Option(entity).map(_.deletedAt != null)
      yield assertTrue(
        deleted,
        found.isEmpty, // Should not be found with filter active
        softDeleted.contains(true) // But should exist with deletedAt set
      )
    },
    test("delete - should return false for non-owner") {
      for
        ownerId  <- createTestUser("paul", "paul@example.com")
        collabId <- createTestUser("quinn", "quinn@example.com")
        created  <- NamespacesRepository.create(DTO.CreateNamespace("Paul's NS"), ownerId)
        _        <- addCollaborator(created.id, collabId)
        deleted  <- NamespacesRepository.delete(created.id, collabId)
      yield assertTrue(!deleted)
    },
    test("delete - should return false for non-existent namespace") {
      for
        userId  <- createTestUser("rachel", "rachel@example.com")
        deleted <- NamespacesRepository.delete(UUID.randomUUID(), userId)
      yield assertTrue(!deleted)
    },
    test("create - should fail when namespace name already exists for same owner") {
      for
        userId <- createTestUser("sam", "sam@example.com")
        _      <- NamespacesRepository.create(DTO.CreateNamespace("Duplicate"), userId)
        result <- NamespacesRepository.create(DTO.CreateNamespace("Duplicate"), userId).exit
      yield assertTrue(result.isFailure)
    },
    test("create - should allow same namespace name for different owners") {
      for
        user1Id <- createTestUser("tom", "tom@example.com")
        user2Id <- createTestUser("uma", "uma@example.com")
        ns1     <- NamespacesRepository.create(DTO.CreateNamespace("Common Name"), user1Id)
        ns2     <- NamespacesRepository.create(DTO.CreateNamespace("Common Name"), user2Id)
      yield assertTrue(
        ns1.name == "Common Name",
        ns2.name == "Common Name",
        ns1.id != ns2.id
      )
    },
    test("create - should fail with case-insensitive duplicate (uppercase)") {
      for
        userId <- createTestUser("victor", "victor@example.com")
        _      <- NamespacesRepository.create(DTO.CreateNamespace("testname"), userId)
        result <- NamespacesRepository.create(DTO.CreateNamespace("TESTNAME"), userId).exit
      yield assertTrue(result.isFailure)
    },
    test("create - should fail with case-insensitive duplicate (mixed case)") {
      for
        userId <- createTestUser("wendy", "wendy@example.com")
        _      <- NamespacesRepository.create(DTO.CreateNamespace("MyNamespace"), userId)
        result <- NamespacesRepository.create(DTO.CreateNamespace("mynamespace"), userId).exit
      yield assertTrue(result.isFailure)
    },
    test("update - should fail when new name conflicts with existing namespace") {
      for
        userId <- createTestUser("xavier", "xavier@example.com")
        ns1    <- NamespacesRepository.create(DTO.CreateNamespace("First"), userId)
        ns2    <- NamespacesRepository.create(DTO.CreateNamespace("Second"), userId)
        result <- NamespacesRepository.update(ns2.id, DTO.UpdateNamespace("First"), userId).exit
      yield assertTrue(result.isFailure)
    },
    test("update - should fail with case-insensitive conflict") {
      for
        userId <- createTestUser("yara", "yara@example.com")
        ns1    <- NamespacesRepository.create(DTO.CreateNamespace("ExistingName"), userId)
        ns2    <- NamespacesRepository.create(DTO.CreateNamespace("OtherName"), userId)
        result <- NamespacesRepository.update(ns2.id, DTO.UpdateNamespace("existingname"), userId).exit
      yield assertTrue(result.isFailure)
    },
    test("update - should allow renaming to same name (same namespace)") {
      for
        userId  <- createTestUser("zara", "zara@example.com")
        ns      <- NamespacesRepository.create(DTO.CreateNamespace("SameName"), userId)
        updated <- NamespacesRepository.update(ns.id, DTO.UpdateNamespace("SameName"), userId)
      yield assertTrue(
        updated.isDefined,
        updated.get.name == "SameName"
      )
    },
    test("update - should allow renaming to same name with different case") {
      for
        userId  <- createTestUser("adam", "adam@example.com")
        ns      <- NamespacesRepository.create(DTO.CreateNamespace("myspace"), userId)
        updated <- NamespacesRepository.update(ns.id, DTO.UpdateNamespace("MySpace"), userId)
      yield assertTrue(
        updated.isDefined,
        updated.get.name == "MySpace"
      )
    }
  ).provideShared(
    Scope.default,
    TestPersistenceLayer.live >>> Hibernate.live
  ) @@ TestAspect.sequential
    @@ TestAspect.withLiveSystem
    @@ TestAspect.withLiveClock
    @@ TestAspect.silentLogging
