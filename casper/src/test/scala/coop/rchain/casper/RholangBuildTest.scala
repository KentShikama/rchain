package coop.rchain.casper

import cats.implicits._
import coop.rchain.casper.helper.HashSetCasperTestNode
import coop.rchain.casper.util.ProtoUtil
import coop.rchain.crypto.signatures.Ed25519
import coop.rchain.rholang.collection.ListOps
import coop.rchain.rholang.interpreter.accounting
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import org.scalatest.{FlatSpec, Matchers}

import scala.concurrent.duration._

class RholangBuildTest extends FlatSpec with Matchers {

  val (validatorKeys, validators) = (1 to 4).map(_ => Ed25519.newKeyPair).unzip
  val bonds                       = HashSetCasperTest.createBonds(validators)
  val genesis                     = HashSetCasperTest.createGenesis(bonds)

  //put a new casper instance at the start of each
  //test since we cannot reset it
  "Our build system" should "allow import of rholang sources into scala code" in {
    val node = HashSetCasperTestNode.standalone(genesis, validatorKeys.last)
    import node._

    val code =
      """new double, dprimes, rl(`rho:registry:lookup`), ListOpsCh, time(`rho:block:timestamp`), timeRtn, timeStore, stdout(`rho:io:stdout`) in {
        |  contract double(@x, ret) = { ret!(2 * x) } |
        |  rl!(`rho:id:dputnspi15oxxnyymjrpu7rkaok3bjkiwq84z7cqcrx4ktqfpyapn4`, *ListOpsCh) |
        |  for(@(_, ListOps) <- ListOpsCh) {
        |    @ListOps!("map", [2, 3, 5, 7], *double, *dprimes)
        |  } |
        |  time!(*timeRtn) |
        |  for (@timestamp <- timeRtn) {
        |    timeStore!("The timestamp is ${timestamp}" %% {"timestamp" : timestamp})
        |  }
        |}""".stripMargin

    val deploy = ProtoUtil.sourceDeploy(code, 1L, accounting.MAX_VALUE)
    val Created(signedBlock) =
      (MultiParentCasper[Task].deploy(deploy) *> MultiParentCasper[Task].createBlock)
        .runSyncUnsafe(1.second)
    val _ = MultiParentCasper[Task].addBlock(signedBlock).runSyncUnsafe(1.second)

    val storage = HashSetCasperTest.blockTuplespaceContents(signedBlock)

    logEff.warns should be(Nil)
    storage.contains("!([4, 6, 10, 14])") should be(true)
    storage.contains("!(\"The timestamp is 2\")") should be(true)

    node.tearDown()
  }

}
