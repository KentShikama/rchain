package coop.rchain.casper

import cats.{Applicative, Monad}
import cats.implicits._
import com.google.protobuf.ByteString
import coop.rchain.casper.Casper.BlockHash
import coop.rchain.casper.protocol.Resource.ResourceClass.ProduceResource
import coop.rchain.casper.protocol._
import coop.rchain.casper.util.{DagOperations, ProtoUtil}
import coop.rchain.casper.util.ProtoUtil._
import coop.rchain.casper.util.comm.CommUtil
import coop.rchain.casper.util.comm.CommUtil.sendBlock
import coop.rchain.catscontrib.{Capture, IOUtil}
import coop.rchain.p2p.Network.{ErrorHandler, KeysStore, frameMessage}
import coop.rchain.p2p.NetworkProtocol
import coop.rchain.p2p.effects._

import scala.annotation.tailrec
import scala.collection.mutable
import scala.collection.immutable
import scala.collection.immutable.{HashMap, HashSet}
import scala.concurrent.Channel

trait CasperOperations[F[_], A] {
  def addBlock(casperState: CasperState, b: BlockMessage): F[CasperState]
  def contains(casperState: CasperState, b: BlockMessage): F[Boolean]
  def deploy(casperState: CasperState, d: Deploy): F[CasperState]
  def estimator(casperState: CasperState): F[A]
  def proposeBlock: F[(CasperState, Option[BlockMessage])]
  def sendBlockWhenReady: F[Unit]
}

trait MultiParentCasperOperations[F[_]] extends CasperOperations[F, IndexedSeq[BlockMessage]]

object MultiParentCasperOperations extends MultiParentCasperInstances {
  def apply[F[_]](implicit instance: MultiParentCasperOperations[F]): MultiParentCasperOperations[F] = instance
}

sealed abstract class MultiParentCasperInstances {
  def noOpsCasper[F[_]: Applicative]: MultiParentCasperOperations[F] =
    new MultiParentCasperOperations[F] {
      def addBlock(casperState: CasperState, b: BlockMessage): F[CasperState] = CasperState().pure[F]
      def contains(casperState: CasperState, b: BlockMessage): F[Boolean] = false.pure[F]
      def deploy(casperState: CasperState, d: Deploy): F[CasperState] = CasperState().pure[F]
      def estimator(casperState: CasperState): F[IndexedSeq[BlockMessage]] = Applicative[F].pure[IndexedSeq[BlockMessage]](Vector(BlockMessage()))
      def proposeBlock: F[Option[BlockMessage]] = Applicative[F].pure[Option[BlockMessage]](None)
      def sendBlockWhenReady: F[Unit] = ().pure[F]
    }

  def hashSetCasper[
  F[_]: Monad: Capture: NodeDiscovery: TransportLayer: Log: Time: Encryption: KeysStore: ErrorHandler]: MultiParentCasperOperations[F] =
    new MultiParentCasperOperations[F] {
      def addBlock(casperState: CasperState, b: BlockMessage): F[CasperState] =
        for {
          (casperStateWithBlockMaybeAdded, success) <- attemptAdd(casperState, b)
          resultCasperState <- if (success)
            Log[F].info(s"CASPER: Added ${PrettyPrinter.buildString(b)}") *> reAttemptBuffer(casperStateWithBlockMaybeAdded)
          else Capture[F].capture { casperStateWithBlockMaybeAdded.copy(blockBuffer = casperStateWithBlockMaybeAdded.blockBuffer + b) }
        } yield resultCasperState

      def contains(casperState: CasperState, b: BlockMessage): F[Boolean] =
        Capture[F].capture {
          val blockDag = casperState.blockDag
          blockDag.blockLookup.contains(b.blockHash)
        }

      def deploy(casperState: CasperState, d: Deploy): F[CasperState] =
        for {
          _ <- Log[F].info(s"CASPER: Received ${PrettyPrinter.buildString(d)}")
          casperStateWithDeploys <- Capture[F].capture {
            val newCasperState = casperState.copy(deployBuff = casperState.deployBuff + d)
            newCasperState.copy(deployHist = newCasperState.deployHist + d)
          }
          newCasperState <- sendBlockWhenReady(casperStateWithDeploys)
        } yield newCasperState

      def estimator(casperState: CasperState): F[IndexedSeq[BlockMessage]] =
        Capture[F].capture {
          val blockDag = casperState.blockDag
          Estimator.tips(blockDag, ProtoUtil.genesisBlock)
        }

      def proposeBlock(casperState: CasperState): F[(CasperState, Option[BlockMessage])] = {
        /*
         * Logic:
         *  -Score each of the blockDAG heads extracted from the block messages via GHOST
         *  -Let P = subset of heads such that P contains no conflicts and the total score is maximized
         *  -Let R = subset of deploy messages which are not included in DAG obtained by following blocks in P
         *  -If R is non-empty then create a new block with parents equal to P and (non-conflicting) txns obtained from R
         *  -Else if R is empty and |P| > 1 then create a block with parents equal to P and no transactions
         *  -Else None
         */

        val orderedHeads = estimator(casperState)
        val blockDag = casperState.blockDag

        // TODO: Fix mutable approach

        //TODO: define conflict model for blocks
        //(maybe wait until we are actually dealing with rholang terms)
        val p = orderedHeads.map(_.take(1)) //for now, just take top choice
        val r = p.map(blocks => {
          DagOperations
            .bfTraverse(blocks)(parents(_).iterator.map(blockDag.blockLookup))
            .foldLeft(casperState.deployHist) { case (remDeploys, b) => {
              b.body.map(_.newCode.foldLeft(remDeploys){ case (acc, deploy) => acc - deploy } ).get // TODO: Cleanup
            }}
        })

        val proposal = p.flatMap(parents => {
          //TODO: Compute this properly
          val parentPostState = parents.head.body.get.postState.get
          val justifications  = justificationProto(blockDag.latestMessages)
          r.map(requests => {
            if (requests.isEmpty) {
              if (parents.length > 1) {
                val body = Body()
                  .withPostState(parentPostState)
                val header = blockHeader(body, parents.map(_.blockHash))
                val block  = blockProto(body, header, justifications, id)

                Some(block)
              } else {
                None
              }
            } else {
              //TODO: only pick non-conflicting deploys
              val deploys = requests.take(10).toSeq
              //TODO: compute postState properly
              val newPostState = parentPostState
                .withBlockNumber(parentPostState.blockNumber + 1)
                .withResources(deploys.map(_.resource.get))
              //TODO: include reductions
              val body = Body()
                .withPostState(newPostState)
                .withNewCode(deploys)
              val header = blockHeader(body, parents.map(_.blockHash))
              val block  = blockProto(body, header, justifications, id)

              Some(block)
            }
          })
        })

        proposal.flatMap {
          case mb @ Some(block) =>
            for {
              _ <- Log[F].info(s"CASPER: Proposed ${PrettyPrinter.buildString(block)}")
              newCasperState <- addBlock(casperState, block)
              _ <- CommUtil.sendBlock[F](block)
              forkchoice <- estimator(casperState).map(_.head)
              _ <- Log[F].info(s"CASPER: New fork-choice is ${PrettyPrinter.buildString(forkchoice)}")
            } yield (newCasperState, mb)
          case _ => Monad[F].pure[(CasperState, Option[BlockMessage])]((casperState, None))
        }
      }

      def sendBlockWhenReady(casperState: CasperState): F[CasperState] =
        for {
          newCasperState <- if (casperState.deployBuff.size < 10) {
            casperState.pure[F]
          } else {
            val clearBuff = Capture[F].capture { casperState.copy(deployBuff = HashSet.empty[Deploy]) }
            proposeBlock(casperState) *> clearBuff
          }
        } yield newCasperState

      private def attemptAdd(casperState: CasperState, block: BlockMessage): F[(CasperState, Boolean)] =
        Monad[F]
          .pure[BlockMessage](block)
          .map(b => {

            // TODO: Cleanup var
            var blockDag = casperState.blockDag
            val hash     = b.blockHash
            val bParents = parents(b)

            if (bParents.exists(p => !blockDag.blockLookup.contains(p))) {
              //cannot add a block if not all parents are known
              (casperState, false)
            } else if (b.justifications.exists(j =>
              !blockDag.blockLookup.contains(j.latestBlockHash))) {
              //cannot add a block if not all justifications are known
              (casperState, false)
            } else {
              //TODO: check if block is an equivocation and update observed faults

              blockDag = blockDag.copy(blockLookup = blockDag.blockLookup + (hash -> b))

              //Assume that a non-equivocating validator must include
              //its own latest message in the justification. Therefore,
              //for a given validator the blocks are guaranteed to arrive in causal order.
              blockDag =
                blockDag.copy(latestMessages = blockDag.latestMessages.updated(b.sender, hash))

              //add current block as new child to each of its parents
              val newChildMap = bParents.foldLeft(blockDag.childMap) {
                case (acc, p) =>
                  val currChildren = acc.getOrElse(p, HashSet.empty[BlockHash])
                  acc.updated(p, currChildren + hash)
              }
              blockDag = blockDag.copy(childMap = newChildMap)
              (casperState.copy(blockDag = blockDag), true)
            }
          })

      // TODO: Make functional somehow
      private def reAttemptBuffer(casperState: CasperState): F[CasperState] = {
        val blockBuffer = casperState.blockBuffer
        val attempts   = blockBuffer.toList.traverse(b => attemptAdd(casperState, b).map { case (newCasperState, succ) => b -> succ } )

        // TODO: I don't know how to do this without using traverse...
        val init : (CasperState, List[(Boolean, BlockMessage)]) = (casperState, List.empty[(Boolean, BlockMessage)])
        val attempts = blockBuffer.foldLeft(init) { case (acc : (CasperState, List[(Boolean, BlockMessage)]), b) => attemptAdd(acc._1, b).flatMap {
          case (a, b) => (a,b)
        }.map {
          case (newCasperState : CasperState, succ : Boolean) =>
            val attempts = acc._2
            (newCasperState, attempts :+ (succ, b))
        } }


        val maybeAdded = attempts.map(_.find(_._2).map(_._1))

        maybeAdded.flatMap {
          case Some(added) =>
            Capture[F].capture { blockBuffer -= added } *> reAttemptBuffer(casperState)

          case None => ().pure[F]
        }
      }
    }
}
