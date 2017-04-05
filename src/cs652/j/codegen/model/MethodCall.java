package cs652.j.codegen.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Created by xuekang on 4/4/17.
 */
public class MethodCall extends Expr {
    //receiver, receiverType, fptrType, args
    public String name;

    @ModelElement
    public VarRef receiver;

    @ModelElement
    public TypeSpec receiverType;

    @ModelElement
    public FuncPtrType fptrType;

    @ModelElement
    public List<TypeCast> args = new ArrayList<>();

    public MethodCall(String name){
        this.name = name;
    }


}
