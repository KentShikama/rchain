package rholang.parsing.rholang2.Absyn; // Java Package generated by the BNF Converter.

public abstract class PPattern implements java.io.Serializable {
  public abstract <R,A> R accept(PPattern.Visitor<R,A> v, A arg);
  public interface Visitor <R,A> {
    public R visit(rholang.parsing.rholang2.Absyn.PPtVar p, A arg);
    public R visit(rholang.parsing.rholang2.Absyn.PPtNil p, A arg);
    public R visit(rholang.parsing.rholang2.Absyn.PPtVal p, A arg);
    public R visit(rholang.parsing.rholang2.Absyn.PPtDrop p, A arg);
    public R visit(rholang.parsing.rholang2.Absyn.PPtInject p, A arg);
    public R visit(rholang.parsing.rholang2.Absyn.PPtOutput p, A arg);
    public R visit(rholang.parsing.rholang2.Absyn.PPtInput p, A arg);
    public R visit(rholang.parsing.rholang2.Absyn.PPtMatch p, A arg);
    public R visit(rholang.parsing.rholang2.Absyn.PPtNew p, A arg);
    public R visit(rholang.parsing.rholang2.Absyn.PPtConstr p, A arg);
    public R visit(rholang.parsing.rholang2.Absyn.PPtPar p, A arg);

  }

}
