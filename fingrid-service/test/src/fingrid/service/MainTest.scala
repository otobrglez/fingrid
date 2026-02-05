package fingrid.service

import zio.*
import zio.test.*

object MainTest extends ZIOSpecDefault:

  def spec = suite("MainTest")(
    test("pleaceholder") {
      assertCompletes
    }
  )
