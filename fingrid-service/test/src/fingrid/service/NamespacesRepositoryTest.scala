package fingrid.service

import fingrid.persistence.entities
import jakarta.persistence.EntityManagerFactory
import zio.*
import zio.hibernate.Hibernate
import zio.hibernate.syntax.*
import zio.logging.backend.SLF4J
import zio.test.*
import zio.test.Assertion.*

import java.util.UUID

object NamespacesRepositoryTest extends ZIOSpecDefault:

  override val bootstrap: ZLayer[Any, Any, TestEnvironment] =
    Runtime.setConfigProvider(ConfigProvider.envProvider) >>>
      Runtime.removeDefaultLoggers >>>
      SLF4J.slf4j >>>
      testEnvironment

  // Helper to create a test user
  private def createTestUser(username: String, email: String): RIO[Hibernate, UUID] =
    Hibernate.attemptInTransaction: session =>
      val user = entities.User(username, email, "#FF0000", "0" * 60)
      session.persist(user)
      session.flush()
      user.id

  // Helper to add collaborator to namespace
  private def addCollaborator(namespaceId: UUID, userId: UUID): RIO[Hibernate, Unit] =
    Hibernate.attemptInTransaction: session =>
      val namespace = session.find(classOf[entities.Namespace], namespaceId)
      val user      = session.find(classOf[entities.User], userId)

      // Use reflection to access private collaborators field
      val field         = classOf[entities.Namespace].getDeclaredField("collaborators")
      field.setAccessible(true)
      val collaborators = field.get(namespace).asInstanceOf[java.util.Set[entities.User]]
      collaborators.add(user)

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
    }
  ).provideShared(
    Scope.default,
    TestPersistenceLayer.live >>> Hibernate.live
  ) @@ TestAspect.sequential
    @@ TestAspect.withLiveSystem
    @@ TestAspect.withLiveClock
    @@ TestAspect.silentLogging
