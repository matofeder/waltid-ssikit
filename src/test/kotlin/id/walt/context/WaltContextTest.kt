package id.walt.context

import id.walt.model.DidMethod
import id.walt.servicematrix.ServiceMatrix
import id.walt.services.context.ContextManager
import id.walt.services.did.DidService
import id.walt.test.RESOURCES_PATH
import io.kotest.core.spec.style.AnnotationSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.io.File

class WaltContextTest: AnnotationSpec() {

  @BeforeAll
  fun setup() {
    ServiceMatrix("$RESOURCES_PATH/service-matrix.properties")
    File(TEST_CONTEXT_DATA_ROOT).deleteRecursively()
  }

  @AfterAll
  fun cleanup() {
    File(TEST_CONTEXT_DATA_ROOT).deleteRecursively()
  }

  @Test
  fun testContext() {
    val userAContext = TestContext("userA")
    val userBContext = TestContext("userB")

    var did1: String? = null
    ContextManager.runWith(userAContext) {
      did1 = DidService.create(DidMethod.key)
    }

    ContextManager.runWith(userBContext) {
      val did2 = DidService.create(DidMethod.key)
      val didList2 = DidService.listDids()
      didList2 shouldHaveSize 1
      didList2.first() shouldBe did2
    }

    ContextManager.runWith(userAContext) {
      val didList1 = DidService.listDids()
      didList1 shouldHaveSize 1
      did1 shouldNotBe null
      didList1.first() shouldBe did1
    }
  }
}
