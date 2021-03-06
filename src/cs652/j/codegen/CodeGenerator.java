package cs652.j.codegen;

import cs652.j.codegen.model.*;
import cs652.j.parser.JBaseVisitor;
import cs652.j.parser.JParser;
import cs652.j.semantics.JClass;
import cs652.j.semantics.JField;
import cs652.j.semantics.JMethod;
import cs652.j.semantics.JPrimitiveType;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.symtab.*;
import org.antlr.v4.runtime.ParserRuleContext;
import org.stringtemplate.v4.STGroup;
import org.stringtemplate.v4.STGroupFile;

import java.util.*;

public class CodeGenerator extends JBaseVisitor<OutputModelObject> {
	public STGroup templates;
	public String fileName;

	public Scope currentScope;
	public JClass currentClass;

	public CFile cFile;

	public CodeGenerator(String fileName) {
		this.fileName = fileName;
		templates = new STGroupFile("cs652/j/templates/C.stg");
	}

	public CFile generate(ParserRuleContext tree) {
		cFile = (CFile)visit(tree);
		return cFile;
	}

	@Override
	public OutputModelObject visitFile(JParser.FileContext ctx){
		currentScope = ctx.scope;
		cFile = new CFile(fileName);
		cFile.main = (MainMethod)visit(ctx.main());
		for(JParser.ClassDeclarationContext classDef : ctx.classDeclaration()){
			OutputModelObject a = visit(classDef);
			cFile.addClass((ClassDef) a);
		}
		currentScope = currentScope.getEnclosingScope();
		return cFile;
	}

	@Override
	public OutputModelObject visitClassDeclaration(JParser.ClassDeclarationContext ctx) {
		currentScope = ctx.scope;
		currentClass = ctx.scope;
		ClassDef classDef = new ClassDef(currentClass);

		for(FieldSymbol sc : ctx.scope.getFields()){
			String varname = sc.getName();
			TypeSpec vartype;
			if(sc.getType() instanceof JPrimitiveType){
				vartype = new PrimitiveTypeSpec(sc.getType().getName());
			}
			else{
				vartype = new ObjectTypeSpec(sc.getType().getName());
			}
			classDef.fields.add((new VarDef(varname,vartype)));
		}

		Set<MethodSymbol> jMethods = currentClass.getMethods();
		List<FuncName> VtableList = new ArrayList<>();
		for(MethodSymbol jMethod : jMethods){
			FuncName fm = new FuncName((JMethod)jMethod);
			fm.slotNumber = fm.method.getSlotNumber();
			VtableList.add(fm);
		}
		Collections.sort(VtableList, new Comparator<FuncName>() {
			@Override
			public int compare(FuncName o1, FuncName o2) {
				return (o1.slotNumber - o2.slotNumber);
			}
		});

		classDef.vtable = VtableList;

		for(ParseTree child : ctx.classBody().children){
			OutputModelObject omo = visit(child);
			if(omo instanceof MethodDef){
				// omo instance of MethodDef
				classDef.methods.add((MethodDef) omo);
			}

		}
		currentScope = currentScope.getEnclosingScope();
		return classDef;
	}

	@Override
	public OutputModelObject visitFieldDeclaration(JParser.FieldDeclarationContext ctx) {
		return new VarDef(ctx.ID().getText(), (TypeSpec) visit(ctx.jType()));
	}

	@Override
	public OutputModelObject visitMethodDeclaration(JParser.MethodDeclarationContext ctx) {
		currentScope = ctx.scope;
		FuncName funcName = new FuncName(ctx.scope);
		MethodDef methodDef = new MethodDef(funcName);
		if(ctx.jType()!=null){
			methodDef.returnType = (TypeSpec) visit(ctx.jType());
		}
		else{
			methodDef.returnType = new PrimitiveTypeSpec(ctx.scope.getType().getName());
		}

		if(ctx.formalParameters().formalParameterList()!=null){
			for(ParseTree fp : ctx.formalParameters().formalParameterList().formalParameter()){
				OutputModelObject omo = visit(fp);
				methodDef.args.add((VarDef) omo);
			}
		}
		methodDef.body = (Block) visit(ctx.methodBody());
		currentScope = currentScope.getEnclosingScope();
		return methodDef;
	}

	@Override
	public OutputModelObject visitMethodBody(JParser.MethodBodyContext ctx) {
		return visit(ctx.block());
	}

	@Override
	public OutputModelObject visitFormalParameter(JParser.FormalParameterContext ctx) {
		return new VarDef(ctx.ID().getText(), (TypeSpec) visit(ctx.jType()));
	}

	@Override
	public OutputModelObject visitMain(JParser.MainContext ctx){
		FuncName funcName = new FuncName(ctx.scope);
		MainMethod mainMethod = new MainMethod(funcName);
		mainMethod.returnType = new PrimitiveTypeSpec("int");
		mainMethod.args.add(new VarDef("argc", new PrimitiveTypeSpec("int")));
		mainMethod.args.add(new VarDef("*argv[]",new PrimitiveTypeSpec("char"))); // you cannot have C strings in the model
		mainMethod.body = (Block)visit(ctx.block());
		return mainMethod;
	}

	@Override
	public OutputModelObject visitBlockStat(JParser.BlockStatContext ctx) {
		return visit(ctx.block());
	}

	@Override
	public OutputModelObject visitBlock(JParser.BlockContext ctx){
		currentScope = ctx.scope;
		Block block = new Block();
		for(JParser.StatementContext stat : ctx.statement()){
			OutputModelObject omo = visit(stat);
			if(omo instanceof VarDef){
				block.locals.add(omo);
			}
			else{
				block.instrs.add(omo);
			}
		}
		currentScope = currentScope.getEnclosingScope();
		return block;
	}

	@Override
	public OutputModelObject visitLocalVarStat(JParser.LocalVarStatContext ctx){
		return visit(ctx.localVariableDeclaration());
	}

	@Override
	public OutputModelObject visitLocalVariableDeclaration(JParser.LocalVariableDeclarationContext ctx){
		String varname = ctx.ID().getText();
		TypeSpec type = (TypeSpec) visit(ctx.jType());
		return new VarDef(varname,type);
	}

	@Override
	public OutputModelObject visitCallStat(JParser.CallStatContext ctx)
	{
		return new CallStat((Expr) visit(ctx.expression()));
	}


	@Override
	public OutputModelObject visitMethodCall(JParser.MethodCallContext ctx) {
		String methodname = ctx.ID().getText();
		MethodCall methodCall = new MethodCall(methodname);
		Expr receiver;

		JClass jClass = (JClass) currentScope.resolve(currentClass.getName());

		TypeSpec returnType = new PrimitiveTypeSpec(ctx.type.getName());
		JMethod jMethod = (JMethod) jClass.resolveMethod(methodname);
		FuncName funcName = new FuncName(jMethod);
		String receiverclass = funcName.getClassName();
        String currentclass = currentClass.getName();
        methodCall.currentclassname = currentclass;
        ObjectTypeSpec receiverType= new ObjectTypeSpec(receiverclass);

		receiver = new VarRef("this", receiverType);

		TypeCast implicit = new TypeCast(receiver,receiverType);

		FuncPtrType fpt = new FuncPtrType(returnType);
		methodCall.fptrType = fpt;
		methodCall.args.add(implicit);
		fpt.argTypes.add(implicit.type);
// whoa. where are the arguments on this method call? you are qualified method call below adds them so this should as well
		if(ctx.expressionList()!=null){
			for(ParseTree a : ctx.expressionList().expression()){
				OutputModelObject vr = visit(a);
				TypeCast tc;
				if(vr instanceof LiteralRef){
					tc = new TypeCast((Expr) vr,null);
					fpt.argTypes.add(((LiteralRef) vr).type);
					methodCall.args.add(tc);
				}
				else if(vr instanceof VarRef){
					tc = new TypeCast(((VarRef) vr),((VarRef) vr).vartype);
					fpt.argTypes.add(((VarRef) vr).vartype);
					methodCall.args.add(tc);
				}
				else if(vr instanceof CtorCall){
					TypeSpec ctorType = new ObjectTypeSpec(((CtorCall) vr).id);
//					tc = new TypeCast((CtorCall) vr, ctorType);
					tc = new TypeCast((CtorCall)vr,null);
					fpt.argTypes.add(((CtorCall) vr).type);
					methodCall.args.add(tc);
				}
				else if(vr instanceof FieldRef){
					tc = new TypeCast(((FieldRef) vr).object,((FieldRef) vr).type);
					fpt.argTypes.add(((VarRef) vr).vartype);
					methodCall.args.add(tc);
				}
			}
		}
		methodCall.fptrType = fpt;

		methodCall.receiver = receiver;
		methodCall.receiverType = receiverType;
		return methodCall;
	}

	@Override
	public OutputModelObject visitQMethodCall(JParser.QMethodCallContext ctx) {
		TypeSpec receiverType;
		String className = ctx.expression().type.getName();
		String methodname = ctx.ID().getText();
		JClass jClass = (JClass) currentScope.resolve(className);
		JMethod jMethod = (JMethod) jClass.resolveMethod(methodname);
		FuncName funcName = new FuncName(jMethod);
		String receiverclass = funcName.getClassName();
		Expr receiver = (Expr) visit(ctx.expression());
		ObjectTypeSpec receiveclassType = new ObjectTypeSpec(receiverclass);

// why are you working so hard to get the receiver type? is this not why we had the compute types phase?
		receiverType = receiver.type;

		String typename = ctx.type.getName();
		TypeSpec returnType;
// you should check whether the symbol table object is an object type not compare strings
// type instanceof JClass
		if(!(currentScope.resolve(typename) instanceof JClass)){
			returnType = new PrimitiveTypeSpec(typename);
		}else{
			returnType = new ObjectTypeSpec(typename);
		}
		FuncPtrType fpt = new FuncPtrType(returnType);
		MethodCall methodCall = new MethodCall(methodname);
		methodCall.currentclassname = className;
		TypeCast implicit = new TypeCast(receiver,receiveclassType);
		methodCall.args.add(implicit);
		fpt.argTypes.add(implicit.type);
		if(ctx.expressionList()!=null){
			for(ParseTree a : ctx.expressionList().expression()){
				OutputModelObject vr = visit(a);
				TypeCast tc;
// why are you checking to see what these objects are?
// It's like parsing again?
// visiting the child should simply give you the right object
// and add that to the method call list.
// if type instanceof JClass then at a typecast otherwise just add

				if(vr instanceof LiteralRef){
					tc = new TypeCast((Expr) vr,null);
					fpt.argTypes.add(((LiteralRef) vr).type);
					methodCall.args.add(tc);
				}
				else if(vr instanceof VarRef){
					tc = new TypeCast(((VarRef) vr),((VarRef) vr).vartype);
					fpt.argTypes.add(((VarRef) vr).vartype);
					methodCall.args.add(tc);
				}
				else if(vr instanceof CtorCall){
					TypeSpec ctorType = new ObjectTypeSpec(((CtorCall) vr).id);
//					tc = new TypeCast((CtorCall) vr, ctorType);
					tc = new TypeCast((CtorCall)vr,null);
					fpt.argTypes.add(((CtorCall) vr).type);
					methodCall.args.add(tc);
				}
				else if(vr instanceof FieldRef){
					tc = new TypeCast(((FieldRef) vr).object,((FieldRef) vr).type);
					fpt.argTypes.add(((VarRef) vr).vartype);
					methodCall.args.add(tc);
				}
			}
		}
		methodCall.fptrType = fpt;
		methodCall.receiver = receiver;
		methodCall.receiverType = receiverType;
		return methodCall;
	}

	@Override
	public OutputModelObject visitThisRef(JParser.ThisRefContext ctx) {
		TypeSpec thisType = new ObjectTypeSpec(ctx.type.getName());
		return new ThisRef("this", thisType);
	}

	@Override
	public OutputModelObject visitReturnStat(JParser.ReturnStatContext ctx) {
		return new ReturnStat((Expr) visit(ctx.expression()));
	}

	@Override
	public OutputModelObject visitIfStat(JParser.IfStatContext ctx) {
		Expr condition = (Expr) visit(ctx.parExpression());
		String cond = ctx.parExpression().getText();
		if(ctx.statement(1)==null){
			IfStat ifStat = new IfStat(cond,condition);
			ifStat.stat = (Stat) visit(ctx.statement(0));
			return ifStat;
		}
		else if(ctx.statement(1)!=null){
			IfElseStat ifElseStat = new IfElseStat(cond, condition);
			ifElseStat.stat = (Stat) visit(ctx.statement(0));
			ifElseStat.elseStat = (Stat) visit(ctx.statement(1));
			return ifElseStat;
		}
		else return null;
	}

	@Override
	public OutputModelObject visitWhileStat(JParser.WhileStatContext ctx) {
		Expr condition = (Expr) visit(ctx.parExpression());
		String cond = ctx.parExpression().getText();
		WhileStat whileStat = new WhileStat(cond,condition);
		whileStat.stat = (Block) visit(ctx.statement());
		return whileStat;
	}

	@Override
	public OutputModelObject visitNullRef(JParser.NullRefContext ctx) {
		return new NullRef("NULL", null);
	}

	@Override
	public OutputModelObject visitJType(JParser.JTypeContext ctx) {
		String typename = ctx.getText();
// check type instanceof JClass; anytime you start comparing strings question what you are doing
		if (!((currentScope.resolve(typename)) instanceof JClass)) {
			return new PrimitiveTypeSpec(typename);
		} else {
			typename = ctx.ID().getText();
			return new ObjectTypeSpec(typename);
		}
	}
	@Override
	public OutputModelObject visitAssignStat(JParser.AssignStatContext ctx) {
		Expr leftvar = (Expr) visit(ctx.expression(0));
		OutputModelObject rightvar = visit(ctx.expression(1));
        AssignStat assignStat = new AssignStat(leftvar,rightvar);

        if((ctx.expression(0).type != ctx.expression(1).type)){
            Type righttype = ctx.expression(0).type;
            if(!(righttype instanceof JPrimitiveType) ){
                TypeCast tc = new TypeCast((Expr) rightvar, new ObjectTypeSpec(ctx.expression(0).type.getName()));
                assignStat.typeCast = tc;
            }
        }
		return assignStat;
	}

	@Override
	public OutputModelObject visitIdRef(JParser.IdRefContext ctx) {
		String varname = ctx.ID().getText();
		Symbol var = currentScope.resolve(varname);
		if(var instanceof JField){
			varname = "this->" + varname;
		}
// again don't compare strings
		TypeSpec vartype;
		if((currentScope.resolve(ctx.type.getName()) instanceof JClass)){
			vartype = new ObjectTypeSpec(ctx.type.getName());
		}
		else{
			vartype = new PrimitiveTypeSpec(ctx.type.getName());
		}
		return new VarRef(varname,vartype);
	}

	@Override
	public OutputModelObject visitLiteralRef(JParser.LiteralRefContext ctx) {
		PrimitiveTypeSpec lt = new PrimitiveTypeSpec(ctx.type.getName());
		if(ctx.INT() != null){
			return new LiteralRef(ctx.INT().getText(),lt);
		}
		else{
			return new LiteralRef(ctx.FLOAT().getText(),lt);
		}
	}

	@Override
	public OutputModelObject visitFieldRef(JParser.FieldRefContext ctx) {
		String name = ctx.ID().getText();
// you should use constructor arguments rather than setting fields as you do here
		FieldRef fieldRef = new FieldRef(name);
// why are you repeatedly visiting and also checking the results? you do not need to
		if(visit(ctx.expression())instanceof VarRef){
			fieldRef.object = (VarRef)visit(ctx.expression());
		}
		else if(visit(ctx.expression()) instanceof ThisRef){
			fieldRef.object = new ThisRef("this",new ObjectTypeSpec(ctx.expression().type.getName()));
		}
		else if(visit(ctx.expression()) instanceof MethodCall){
			fieldRef.object = (MethodCall) visit(ctx.expression());
		}
		else if(visit(ctx.expression()) instanceof FieldRef){
			fieldRef.object = (FieldRef) visit(ctx.expression());
		}
		return fieldRef;
	}

	@Override
	public OutputModelObject visitCtorCall(JParser.CtorCallContext ctx) {
		String ctorid = ctx.ID().getText();
		ObjectTypeSpec ctortype = new ObjectTypeSpec(ctx.type.getName());
		return new CtorCall(ctorid, ctortype);
	}

	@Override
	public OutputModelObject visitPrintStat(JParser.PrintStatContext ctx) {
		PrintStat printStat = new PrintStat(ctx.STRING().getText());
		for(JParser.ExpressionContext arg : ctx.expressionList().expression()){
			Expr a = (Expr) visit(arg);
			printStat.addArg(a);
		}
		return printStat;
	}

	@Override
	public OutputModelObject visitPrintStringStat(JParser.PrintStringStatContext ctx) {
		return new PrintStringStat(ctx.STRING().getText());
	}

}
