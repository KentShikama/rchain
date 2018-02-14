package coop.rchain.rholang.intepreter

import coop.rchain.rholang.syntax.rholang_mercury
import coop.rchain.rholang.syntax.rholang_mercury.Absyn.{Ground => AbsynGround, _}

sealed trait VarSort
case object ProcSort extends VarSort
case object NameSort extends VarSort

object BoolNormalizeMatcher {
  def normalizeMatch(b: Bool): GBool = {
    b match {
      case b: BoolTrue => GBool(true)
      case b: BoolFalse => GBool(false)
    }
  }
}

object GroundNormalizeMatcher {
  def normalizeMatch(g: AbsynGround): Ground = {
    g match {
      case gb: GroundBool => BoolNormalizeMatcher.normalizeMatch(gb.bool_)
      case gi: GroundInt => GInt(gi.integer_)
      case gs: GroundString => GString(gs.string_)
      case gu: GroundUri => GUri(gu.uri_)
    }
  }
}

object NameNormalizeMatcher {
  def normalizeMatch(n: Name, input: NameVisitInputs): NameVisitOutputs = {
    n match {
      case n: NameWildcard =>
        val wildcardBindResult = input.knownFree.setWildcardUsed(1)
        NameVisitOutputs(ChanVar(FreeVar(wildcardBindResult._2)), wildcardBindResult._1)
      case n: NameVar =>
        input.env.get(n.var_) match {
          case Some((level, NameSort)) => {
            NameVisitOutputs(
              ChanVar(BoundVar(level)),
              input.knownFree)
          }
          case Some((level, ProcSort)) => {
            throw new Error("Proc variable used in name context.")
          }
          case None => {
            input.knownFree.get(n.var_) match {
              case None =>
                val newBindingsPair = 
                  input.knownFree.newBindings(List((Some(n.var_), NameSort)))
                NameVisitOutputs(
                  ChanVar(FreeVar(newBindingsPair._2(0))),
                  newBindingsPair._1)
              case _ => throw new Error(
                "Free variable used as binder may not be used twice.")
            }
          }
        }

      case n: NameQuote => {
        def collapseQuoteEval(p: Par): Channel = {
          p.singleEval() match {
            case Some(Eval(chanNew)) => chanNew
            case _ => Quote(p)
          }
        }

        val procVisitResult: ProcVisitOutputs = ProcNormalizeMatcher.normalizeMatch(
            n.proc_,
            ProcVisitInputs(Par(), input.env, input.knownFree))

        NameVisitOutputs(collapseQuoteEval(procVisitResult.par),
          procVisitResult.knownFree)
      }
    }
  }
}

object ProcNormalizeMatcher {
  def normalizeMatch(p: Proc, input: ProcVisitInputs): ProcVisitOutputs = {
    def unaryExp(
        subProc: Proc, input: ProcVisitInputs, constructor: Par => Expr): ProcVisitOutputs = {
      val subResult = normalizeMatch(subProc, input.copy(par = Par()))
      ProcVisitOutputs(
          input.par.copy(exprs = constructor(subResult.par) :: input.par.exprs),
          subResult.knownFree)
    }
    def binaryExp(
        subProcLeft: Proc,
        subProcRight: Proc,
        input: ProcVisitInputs,
        constructor: (Par, Par) => Expr): ProcVisitOutputs = {
      val leftResult = normalizeMatch(subProcLeft, input.copy(par = Par()))
      val rightResult = normalizeMatch(
          subProcRight, input.copy(par = Par(), knownFree = leftResult.knownFree))
      ProcVisitOutputs(
          input.par.copy(exprs = constructor(leftResult.par, rightResult.par) :: input.par.exprs),
          rightResult.knownFree)
    }

    p match {
      case p: PGround => ProcVisitOutputs(
          input.par.copy(exprs = GroundNormalizeMatcher.normalizeMatch(p.ground_) :: input.par.exprs),
          input.knownFree)

      case p: PVar => input.env.get(p.var_) match {
        case Some((level, ProcSort)) => {
          ProcVisitOutputs(
            input.par.copy(exprs = EVar(BoundVar(level))
                           :: input.par.exprs),
            input.knownFree)
        }
        case Some((level, NameSort)) => {
          throw new Error("Name variable used in process context.")
        }
        case None => {
          input.knownFree.get(p.var_) match {
            case None =>
              val newBindingsPair = 
                input.knownFree.newBindings(List((Some(p.var_), ProcSort)))
              ProcVisitOutputs(
                input.par.copy(exprs = EVar(FreeVar(newBindingsPair._2(0)))
                               :: input.par.exprs),
                newBindingsPair._1)
            case _ => throw new Error(
              "Free variable used as binder may not be used twice.")
          }
        }
      }

      case p: PNil => ProcVisitOutputs(input.par, input.knownFree)

      case p: PEval => {
        def collapseEvalQuote(chan: Channel): Par = {
          chan match {
            case Quote(p) => p
            case _ => Par().copy(evals = List(Eval(chan)))
          }
        }

        val nameMatchResult = NameNormalizeMatcher.normalizeMatch(
          p.name_,
          NameVisitInputs(input.env, input.knownFree))
        ProcVisitOutputs(
          input.par.merge(collapseEvalQuote(nameMatchResult.chan)),
          nameMatchResult.knownFree)
      }

      case p: PNot => unaryExp(p.proc_, input, ENot)
      case p: PNeg => unaryExp(p.proc_, input, ENeg)

      case p: PMult => binaryExp(p.proc_1, p.proc_2, input, EMult)
      case p: PDiv => binaryExp(p.proc_1, p.proc_2, input, EDiv)
      case p: PAdd => binaryExp(p.proc_1, p.proc_2, input, EPlus)
      case p: PMinus => binaryExp(p.proc_1, p.proc_2, input, EMinus)

      case p: PLt => binaryExp(p.proc_1, p.proc_2, input, ELt)
      case p: PLte => binaryExp(p.proc_1, p.proc_2, input, ELte)
      case p: PGt => binaryExp(p.proc_1, p.proc_2, input, EGt)
      case p: PGte => binaryExp(p.proc_1, p.proc_2, input, EGte)

      case p: PEq => binaryExp(p.proc_1, p.proc_2, input, EEq)
      case p: PNeq => binaryExp(p.proc_1, p.proc_2, input, ENeq)

      case p: PAnd => binaryExp(p.proc_1, p.proc_2, input, EAnd)
      case p: POr => binaryExp(p.proc_1, p.proc_2, input, EOr)

      case p: PSend => {
        import scala.collection.JavaConverters._
        val nameMatchResult = NameNormalizeMatcher.normalizeMatch(
          p.name_,
          NameVisitInputs(input.env, input.knownFree))
        val initAcc = (List[Par](), ProcVisitInputs(Par(), input.env, nameMatchResult.knownFree))
        val dataResults = (initAcc /: p.listproc_.asScala.toList.reverse)(
          (acc, e) => {
            val procMatchResult = normalizeMatch(e, acc._2)
            (procMatchResult.par :: acc._1,
             ProcVisitInputs(Par(), input.env, procMatchResult.knownFree))
          }
        )
        val persistent = p.send_ match {
          case _: SendSingle => false
          case _: SendMultiple => true
        }
        ProcVisitOutputs(
            input.par.copy(sends = Send(nameMatchResult.chan, dataResults._1, persistent) :: input.par.sends),
            dataResults._2.knownFree)
      }

      case p: PPar => {
        val result = normalizeMatch(p.proc_1, input)
        val chainedInput = input.copy(
          knownFree = result.knownFree,
          par = result.par)
        normalizeMatch(p.proc_2, chainedInput)
      }

      case _ => throw new Error("Compilation of construct not yet supported.")
    }
  }
}

// Parameterized over T, the kind of typing discipline we are enforcing.
class DebruijnLevelMap[T](val next: Int, val env: Map[String, (Int, T)]) {
  def this() = this(0, Map[String, (Int, T)]())

  def newBindings(bindings: List[(Option[String], T)]): (DebruijnLevelMap[T], List[Int]) = {
    val result = bindings.foldLeft((this, List[Int]())) {
      (acc: (DebruijnLevelMap[T], List[Int]), binding: (Option[String], T)) => {
        val newMap = binding._1 match {
          case None => acc._1.env
          case Some(varName) => acc._1.env + (varName -> ((acc._1.next, binding._2)))
        }
        (DebruijnLevelMap(
            acc._1.next + 1,
            newMap),
         acc._1.next :: acc._2)
      }
    }
    (result._1, result._2.reverse)
  }

  // Returns the new map, and the starting level of the newly "bound" wildcards
  def setWildcardUsed(count: Int): (DebruijnLevelMap[T], Int) = {
    (DebruijnLevelMap(next + count, env), next)
  }

  def getBinding(varName: String): Option[T] = {
    for (pair <- env.get(varName)) yield pair._2
  }
  def getLevel(varName: String): Option[Int] = {
    for (pair <- env.get(varName)) yield pair._1
  }
  def get(varName: String): Option [(Int, T)] = env.get(varName)
  def isEmpty() = next == 0

  override def equals(that: Any): Boolean = {
    that match {
      case that: DebruijnLevelMap[T] =>
        next == that.next &&
        env == that.env
      case _ => false
    }
  }

  override def hashCode(): Int = {
    (next.hashCode() * 37 + env.hashCode)
  }
}

object DebruijnLevelMap{
  def apply[T](
      next: Int, env: Map[String, (Int, T)]): DebruijnLevelMap[T] = {
    new DebruijnLevelMap(next, env)
  }

  def apply[T](): DebruijnLevelMap[T] = new DebruijnLevelMap[T]()

  def unapply[T](db: DebruijnLevelMap[T]): Option[(Int, Map[String,(Int, T)])] = {
    Some((db.next, db.env))
  }
}

case class ProcVisitInputs(
  par: Par,
  env: DebruijnLevelMap[VarSort],
  knownFree: DebruijnLevelMap[VarSort])
// Returns the update Par and an updated map of free variables.
case class ProcVisitOutputs(par: Par, knownFree: DebruijnLevelMap[VarSort])

sealed trait ChanPosition
case object BindingPosition extends ChanPosition
case object UsePosition extends ChanPosition

case class NameVisitInputs(
  env: DebruijnLevelMap[VarSort],
  knownFree: DebruijnLevelMap[VarSort])
case class NameVisitOutputs(chan: Channel, knownFree: DebruijnLevelMap[VarSort])
