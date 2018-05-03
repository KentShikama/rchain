package coop.rchain.casper

import cats.Monad
import cats.implicits._
import com.google.protobuf.ByteString
import coop.rchain.casper.protocol.{BlockMessage, Deploy}
import coop.rchain.casper.util.ProtoUtil
import coop.rchain.casper.util.comm.CommUtil
import coop.rchain.catscontrib.Capture
import coop.rchain.p2p.Network.{ErrorHandler, KeysStore, frameMessage}
import coop.rchain.p2p.NetworkProtocol
import coop.rchain.p2p.effects._

import scala.collection.immutable.{HashMap, HashSet}
import scala.collection.mutable
import scala.concurrent.Channel

sealed trait CasperEvent
case class DeployEvent(d: Deploy) extends CasperEvent
case class BlockReceiveEvent(b: BlockMessage) extends CasperEvent

case class CasperState(blockDag: BlockDag, blockBuffer: HashSet[BlockMessage], deployHist: HashSet[Deploy], deployBuff: HashSet[Deploy])

object CasperState {
  def apply(): CasperState = CasperState(BlockDag(), HashSet.empty[BlockMessage], HashSet.empty[Deploy], HashSet.empty[Deploy])
}

object Casper {
  type BlockHash = ByteString
  type Validator = ByteString

  //  TODO: figure out actual validator identities...
  val id = ByteString.copyFrom(Array((scala.util.Random.nextInt(10) + 1).toByte))
  val blockDag: BlockDag = BlockDag().copy(
    blockLookup = HashMap[BlockHash, BlockMessage](ProtoUtil.genesisBlock.blockHash -> ProtoUtil.genesisBlock))
  var casperState: CasperState = CasperState(blockDag = blockDag, blockBuffer = HashSet.empty[BlockMessage], deployHist = HashSet.empty[Deploy], deployBuff = HashSet.empty[Deploy])
  val eventQueue = new Channel[CasperEvent]()

  def stateLoop[F[_]: Capture: MultiParentCasperOperations]() = {
    for {
      event <- Capture[F].capture { eventQueue.read }
      newCasperState <- event match {
        case DeployEvent(d) =>
          MultiParentCasperOperations[F].deploy(casperState, d)
        case BlockReceiveEvent(b) =>
          handleBlock[F](casperState, b)
      }
      casperState = newCasperState
    } yield ()
  }

  private def handleBlock[F[_] : Monad : MultiParentCasperOperations : NodeDiscovery : TransportLayer : Log : Time : Encryption : KeysStore : ErrorHandler](casperState: CasperState, b: BlockMessage) = {
    for {
      isOldBlock <- MultiParentCasperOperations[F].contains(casperState, b)
      newCasperState <- if (isOldBlock) {
        Log[F].info(s"CASPER: Received ${PrettyPrinter.buildString(b)} again.") *> Capture[F].capture { casperState }
      } else {
        handleNewBlock[F](casperState, b)
      }
    } yield newCasperState
  }

  private def handleNewBlock[F[_]: Monad: MultiParentCasperOperations: NodeDiscovery: TransportLayer: Log: Time: Encryption: KeysStore: ErrorHandler](casperState: CasperState, b: BlockMessage) =
    for {
      newCasperState          <- MultiParentCasperOperations[F].addBlock(casperState, b)
      forkchoice <- MultiParentCasperOperations[F].estimator(newCasperState).map(_.head)
      _ <- Log[F].info(s"CASPER: Received ${PrettyPrinter.buildString(b)}. New fork-choice is ${PrettyPrinter.buildString(forkchoice)}")
      _          <- CommUtil.sendBlock[F](b)
    } yield
      s"CASPER: Received ${PrettyPrinter.buildString(b)}. New fork-choice is ${PrettyPrinter.buildString(forkchoice)}"
}
