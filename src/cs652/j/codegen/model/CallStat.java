package cs652.j.codegen.model;

/**
 * Created by xuekang on 4/4/17.
 */
public class CallStat extends Stat {
    @ModelElement
    public Expr call;

    public CallStat(Expr call){
        this.call = call;
    }

}
