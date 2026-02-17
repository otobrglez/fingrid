package fingrid.service

import jakarta.persistence.EntityManagerFactory
import zio.*
import zio.hibernate.Hibernate
import zio.logging.backend.SLF4J
import zio.test.*

object SeederTest extends ZIOSpecDefault:

  override val bootstrap: ZLayer[Any, Any, TestEnvironment] =
    Runtime.setConfigProvider(ConfigProvider.envProvider) >>>
      Runtime.removeDefaultLoggers >>>
      SLF4J.slf4j >>>
      testEnvironment

  def spec = suite("SeederTest")(
    test("create users with namespaces, categories, and transactions") {
      for _ <- Seeder(numberOfUsers = 10).seed
      yield assertCompletes
    }
  ).provide(
    Scope.default,
    TestPersistenceLayer.live >>> Hibernate.live
  ) @@ TestAspect.sequential @@ TestAspect.withLiveSystem @@ TestAspect.withLiveClock @@ TestAspect.silentLogging
