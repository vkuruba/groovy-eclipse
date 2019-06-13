/*
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */
package org.codehaus.groovy.control;

import org.codehaus.groovy.ast.AnnotatedNode;
import org.codehaus.groovy.ast.AnnotationNode;
import org.codehaus.groovy.ast.ClassCodeExpressionTransformer;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.DynamicVariable;
import org.codehaus.groovy.ast.FieldNode;
import org.codehaus.groovy.ast.ImportNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.ModuleNode;
import org.codehaus.groovy.ast.Parameter;
import org.codehaus.groovy.ast.PropertyNode;
import org.codehaus.groovy.ast.Variable;
import org.codehaus.groovy.ast.expr.AnnotationConstantExpression;
import org.codehaus.groovy.ast.expr.ArgumentListExpression;
import org.codehaus.groovy.ast.expr.BinaryExpression;
import org.codehaus.groovy.ast.expr.ClassExpression;
import org.codehaus.groovy.ast.expr.ClosureExpression;
import org.codehaus.groovy.ast.expr.ConstantExpression;
import org.codehaus.groovy.ast.expr.ConstructorCallExpression;
import org.codehaus.groovy.ast.expr.EmptyExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.ListExpression;
import org.codehaus.groovy.ast.expr.MapEntryExpression;
import org.codehaus.groovy.ast.expr.MethodCall;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import org.codehaus.groovy.ast.expr.NamedArgumentListExpression;
import org.codehaus.groovy.ast.expr.PropertyExpression;
import org.codehaus.groovy.ast.expr.StaticMethodCallExpression;
import org.codehaus.groovy.ast.expr.TupleExpression;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.codehaus.groovy.ast.stmt.Statement;
import org.codehaus.groovy.syntax.Types;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.codehaus.groovy.runtime.MetaClassHelper.capitalize;

/**
 * Visitor to resolve constants and method calls from static imports.
 */
public class StaticImportVisitor extends ClassCodeExpressionTransformer {
    private ClassNode currentClass;
    private MethodNode currentMethod;
    private SourceUnit source;
    private boolean inSpecialConstructorCall;
    private boolean inClosure;
    private boolean inPropertyExpression;
    private Expression foundConstant;
    private Expression foundArgs;
    private boolean inAnnotation;
    private boolean inLeftExpression;

    // GRECLIPE-1371 and GRECLIPSE-1363 ability to toggle behavior based on reconcile or not
    boolean isReconcile;
    // GRECLIPSE end

    public void visitClass(ClassNode node, SourceUnit source) {
        this.currentClass = node;
        this.source = source;
        super.visitClass(node);
    }

    @Override
    protected void visitConstructorOrMethod(MethodNode node, boolean isConstructor) {
        this.currentMethod = node;
        super.visitConstructorOrMethod(node, isConstructor);
        this.currentMethod = null;
    }

    @Override
    public void visitAnnotations(AnnotatedNode node) {
        boolean oldInAnnotation = inAnnotation;
        inAnnotation = true;
        super.visitAnnotations(node);
        inAnnotation = oldInAnnotation;
    }

    public Expression transform(Expression exp) {
        if (exp == null) return null;
        if (exp.getClass() == VariableExpression.class) {
            return transformVariableExpression((VariableExpression) exp);
        }
        if (exp.getClass() == BinaryExpression.class) {
            return transformBinaryExpression((BinaryExpression) exp);
        }
        if (exp.getClass() == PropertyExpression.class) {
            return transformPropertyExpression((PropertyExpression) exp);
        }
        if (exp.getClass() == MethodCallExpression.class) {
            return transformMethodCallExpression((MethodCallExpression) exp);
        }
        if (exp.getClass() == ClosureExpression.class) {
            return transformClosureExpression((ClosureExpression) exp);
        }
        if (exp.getClass() == ConstructorCallExpression.class) {
            return transformConstructorCallExpression((ConstructorCallExpression) exp);
        }
        if (exp.getClass() == ArgumentListExpression.class) {
            Expression result = exp.transformExpression(this);
            if (inPropertyExpression) {
                foundArgs = result;
            }
            return result;
        }
        if (exp instanceof ConstantExpression) {
            Expression result = exp.transformExpression(this);
            if (inPropertyExpression) {
                foundConstant = result;
            }
            if (inAnnotation && exp instanceof AnnotationConstantExpression) {
                ConstantExpression ce = (ConstantExpression) result;
                if (ce.getValue() instanceof AnnotationNode) {
                    // replicate a little bit of AnnotationVisitor here
                    // because we can't wait until later to do this
                    AnnotationNode an = (AnnotationNode) ce.getValue();
                    Map<String, Expression> attributes = an.getMembers();
                    for (Map.Entry<String, Expression> entry : attributes.entrySet()) {
                        Expression attrExpr = transform(entry.getValue());
                        entry.setValue(attrExpr);
                    }

                }
            }
            return result;
        }
        return exp.transformExpression(this);
    }

    // if you have a Bar class with a static foo property, and this:
    //   import static Bar.foo as baz
    // then this constructor (not normal usage of statics):
    //   new Bar(baz:1)
    // will become:
    //   new Bar(foo:1)

    private Expression transformMapEntryExpression(MapEntryExpression me, ClassNode constructorCallType) {
        Expression key = me.getKeyExpression();
        Expression value = me.getValueExpression();
        ModuleNode module = currentClass.getModule();
        if (module != null && key instanceof ConstantExpression) {
            Map<String, ImportNode> importNodes = module.getStaticImports();
            if (importNodes.containsKey(key.getText())) {
                ImportNode importNode = importNodes.get(key.getText());
                if (importNode.getType().equals(constructorCallType)) {
                    String newKey = importNode.getFieldName();
                    return new MapEntryExpression(new ConstantExpression(newKey), value.transformExpression(this));
                }
            }
        }
        return me;
    }

    protected Expression transformBinaryExpression(BinaryExpression be) {
        int type = be.getOperation().getType();
        boolean oldInLeftExpression;
        Expression right = transform(be.getRightExpression());
        be.setRightExpression(right);
        Expression left;
        if (type == Types.EQUAL && be.getLeftExpression() instanceof VariableExpression) {
            oldInLeftExpression = inLeftExpression;
            inLeftExpression = true;
            left = transform(be.getLeftExpression());
            inLeftExpression = oldInLeftExpression;
            if (left instanceof StaticMethodCallExpression) {
                StaticMethodCallExpression smce = (StaticMethodCallExpression) left;
                StaticMethodCallExpression result = new StaticMethodCallExpression(smce.getOwnerType(), smce.getMethod(), right);
                setSourcePosition(result, be);
                return result;
            }
        } else {
            left = transform(be.getLeftExpression());
        }
        be.setLeftExpression(left);
        return be;
    }

    protected Expression transformVariableExpression(VariableExpression ve) {
        Variable v = ve.getAccessedVariable();
        if (v != null && v instanceof DynamicVariable) {
            Expression result = findStaticFieldOrPropAccessorImportFromModule(v.getName());
            if (result != null) {
                setSourcePosition(result, ve);
                if (inAnnotation) {
                    result = transformInlineConstants(result);
                }
                return result;
            }
        }
        return ve;
    }

    /**
     * Set the source position of toSet including its property expression if it has one.
     *
     * @param toSet resulting node
     * @param origNode original node
     */
    private static void setSourcePosition(Expression toSet, Expression origNode) {
        toSet.setSourcePosition(origNode);
        if (toSet instanceof PropertyExpression) {
            ((PropertyExpression) toSet).getProperty().setSourcePosition(origNode);
        }
    }

    // resolve constant-looking expressions statically (do here as gets transformed away later)

    private Expression transformInlineConstants(Expression exp) {
        if (exp instanceof PropertyExpression) {
            PropertyExpression pe = (PropertyExpression) exp;
            if (pe.getObjectExpression() instanceof ClassExpression) {
                ClassExpression ce = (ClassExpression) pe.getObjectExpression();
                ClassNode type = ce.getType();
                if (type.isEnum()) return exp;
                Expression constant = findConstant(getField(type, pe.getPropertyAsString()));
                // GRECLIPSE edit
                //if (constant != null) return constant;
                if (constant != null) {
                    return ResolveVisitor.cloneConstantExpression(constant, exp);
                }
                // GRECLIPSE end
            }
        } else if (exp instanceof ListExpression) {
            ListExpression le = (ListExpression) exp;
            ListExpression result = new ListExpression();
            for (Expression e : le.getExpressions()) {
                result.addExpression(transformInlineConstants(e));
            }
            return result;
        }

        return exp;
    }

    private static Expression findConstant(FieldNode fn) {
        if (fn != null && !fn.isEnum() && fn.isStatic() && fn.isFinal()) {
            if (fn.getInitialValueExpression() instanceof ConstantExpression) {
                return fn.getInitialValueExpression();
            }
        }
        return null;
    }

    protected Expression transformMethodCallExpression(MethodCallExpression mce) {
        Expression args = transform(mce.getArguments());
        Expression method = transform(mce.getMethod());
        Expression object = transform(mce.getObjectExpression());
        boolean isExplicitThisOrSuper = false;
        boolean isExplicitSuper = false;
        if (object instanceof VariableExpression) {
            VariableExpression ve = (VariableExpression) object;
            isExplicitThisOrSuper = !mce.isImplicitThis() && (ve.isThisExpression() || ve.isSuperExpression());
            isExplicitSuper = ve.isSuperExpression();
        }

        if (mce.isImplicitThis() || isExplicitThisOrSuper) {
            if (mce.isImplicitThis()) {
                Expression ret = findStaticMethodImportFromModule(method, args);
                if (ret != null) {
                    // GRECLIPSE add
                    if (!((MethodCall) ret).getMethodAsString().equals(method.getText())) {
                        // store the identifier to facilitate organizing static imports
                        ret.putNodeMetaData("static.import.alias", method.getText());
                    }
                    // GRECLIPSE end
                    setSourcePosition(ret, mce);
                    return ret;
                }
                if (method instanceof ConstantExpression && !inLeftExpression) {
                    // could be a closure field
                    String methodName = (String) ((ConstantExpression) method).getValue();
                    ret = findStaticFieldOrPropAccessorImportFromModule(methodName);
                    if (ret != null) {
                        ret = new MethodCallExpression(ret, "call", args);
                        setSourcePosition(ret, mce);
                        return ret;
                    }
                }
            } else if (currentMethod!=null && currentMethod.isStatic() && isExplicitSuper) {
                MethodCallExpression ret = new MethodCallExpression(new ClassExpression(currentClass.getSuperClass()), method, args);
                setSourcePosition(ret, mce);
                return ret;
            }

            if (method instanceof ConstantExpression) {
                ConstantExpression ce = (ConstantExpression) method;
                Object value = ce.getValue();
                if (value instanceof String) {
                    String methodName = (String) value;
                    boolean lookForPossibleStaticMethod = !methodName.equals("call");
                    if (currentMethod != null && !currentMethod.isStatic()) {
                        if (currentClass.hasPossibleMethod(methodName, args)) {
                            lookForPossibleStaticMethod = false;
                        }
                    }
                    if (!inClosure && (inSpecialConstructorCall ||
                            (lookForPossibleStaticMethod && currentClass.hasPossibleStaticMethod(methodName, args)))) {
                        StaticMethodCallExpression smce = new StaticMethodCallExpression(currentClass, methodName, args);
                        setSourcePosition(smce, mce);
                        return smce;
                    }
                }
            }
        }

        MethodCallExpression result = new MethodCallExpression(object, method, args);
        result.setSafe(mce.isSafe());
        result.setImplicitThis(mce.isImplicitThis());
        result.setSpreadSafe(mce.isSpreadSafe());
        result.setMethodTarget(mce.getMethodTarget());
        // GROOVY-6757
        result.setGenericsTypes(mce.getGenericsTypes());
        setSourcePosition(result, mce);
        return result;
    }

    protected Expression transformConstructorCallExpression(ConstructorCallExpression cce) {
        inSpecialConstructorCall = cce.isSpecialCall();
        Expression expression = cce.getArguments();
        if (expression instanceof TupleExpression) {
            TupleExpression tuple = (TupleExpression) expression;
            if (tuple.getExpressions().size() == 1) {
                expression = tuple.getExpression(0);
                if (expression instanceof NamedArgumentListExpression) {
                    NamedArgumentListExpression namedArgs = (NamedArgumentListExpression) expression;
                    List<MapEntryExpression> entryExpressions = namedArgs.getMapEntryExpressions();
                    for (int i = 0; i < entryExpressions.size(); i++) {
                        entryExpressions.set(i, (MapEntryExpression) transformMapEntryExpression(entryExpressions.get(i), cce.getType()));
                    }
                }
            }
        }
        Expression ret = cce.transformExpression(this);
        inSpecialConstructorCall = false;
        return ret;
    }

    protected Expression transformClosureExpression(ClosureExpression ce) {
        boolean oldInClosure = inClosure;
        inClosure = true;
        if (ce.getParameters() != null) {
            for (Parameter p : ce.getParameters()) {
                if (p.hasInitialExpression()) {
                    p.setInitialExpression(transform(p.getInitialExpression()));
                }
            }
        }
        Statement code = ce.getCode();
        if (code != null) code.visit(this);
        inClosure = oldInClosure;
        return ce;
    }

    protected Expression transformPropertyExpression(PropertyExpression pe) {
        if (currentMethod!=null && currentMethod.isStatic()
                && pe.getObjectExpression() instanceof VariableExpression
                && ((VariableExpression) pe.getObjectExpression()).isSuperExpression()) {
            PropertyExpression pexp = new PropertyExpression(
                    new ClassExpression(currentClass.getSuperClass()),
                    transform(pe.getProperty())
            );
            pexp.setSourcePosition(pe);
            return pexp;
        }
        boolean oldInPropertyExpression = inPropertyExpression;
        Expression oldFoundArgs = foundArgs;
        Expression oldFoundConstant = foundConstant;
        inPropertyExpression = true;
        foundArgs = null;
        foundConstant = null;
        Expression objectExpression = transform(pe.getObjectExpression());
        boolean candidate = false;
        if (objectExpression instanceof MethodCallExpression) {
            candidate = ((MethodCallExpression)objectExpression).isImplicitThis();
        }

        if (foundArgs != null && foundConstant != null && candidate) {
            Expression result = findStaticMethodImportFromModule(foundConstant, foundArgs);
            if (result != null) {
                objectExpression = result;
                objectExpression.setSourcePosition(pe);
            }
        }
        inPropertyExpression = oldInPropertyExpression;
        foundArgs = oldFoundArgs;
        foundConstant = oldFoundConstant;
        pe.setObjectExpression(objectExpression);
        return pe;
    }

    private Expression findStaticFieldOrPropAccessorImportFromModule(String name) {
        ModuleNode module = currentClass.getModule();
        if (module == null) return null;
        Map<String, ImportNode> importNodes = module.getStaticImports();
        Expression expression = null;
        String accessorName = getAccessorName(name);
        // look for one of these:
        //   import static MyClass.setProp [as setOtherProp]
        //   import static MyClass.getProp [as getOtherProp]
        // when resolving prop reference
        if (importNodes.containsKey(accessorName)) {
            ImportNode importNode = importNodes.get(accessorName);
            expression = findStaticPropertyAccessorByFullName(importNode.getType(), importNode.getFieldName());
            if (expression != null) return expression;
            expression = findStaticPropertyAccessor(importNode.getType(), getPropNameForAccessor(importNode.getFieldName()));
            if (expression != null) return expression;
        }
        if (accessorName.startsWith("get")) {
            accessorName = "is" + accessorName.substring(3);
            if (importNodes.containsKey(accessorName)) {
                ImportNode importNode = importNodes.get(accessorName);
                expression = findStaticPropertyAccessorByFullName(importNode.getType(), importNode.getFieldName());
                if (expression != null) return expression;
                expression = findStaticPropertyAccessor(importNode.getType(), getPropNameForAccessor(importNode.getFieldName()));
                if (expression != null) return expression;
            }
        }

        // look for one of these:
        //   import static MyClass.prop [as otherProp]
        // when resolving prop or field reference
        // GRECLIPSE add
        try {
        // GRECLIPSE end
        if (importNodes.containsKey(name)) {
            ImportNode importNode = importNodes.get(name);
            // GRECLIPSE add
            if (!isReconcile) {
            // GRECLIPSE end
            expression = findStaticPropertyAccessor(importNode.getType(), importNode.getFieldName());
            if (expression != null) return expression;
            // GRECLIPSE add
            }
            // GRECLIPSE end
            expression = findStaticField(importNode.getType(), importNode.getFieldName());
            if (expression != null) return expression;
        }
        // look for one of these:
        //   import static MyClass.*
        // when resolving prop or field reference
        for (ImportNode importNode : module.getStaticStarImports().values()) {
            ClassNode node = importNode.getType();
            expression = findStaticPropertyAccessor(node, name);
            if (expression != null) return expression;
            expression = findStaticField(node, name);
            if (expression != null) return expression;
        }
        // GRECLIPSE add
        } finally {
            // store the identifier to facilitate organizing static imports
            if (expression != null) expression.putNodeMetaData("static.import.alias", name);
        }
        // GRECLIPSE end
        return null;
    }

    private Expression findStaticMethodImportFromModule(Expression method, Expression args) {
        ModuleNode module = currentClass.getModule();
        if (module == null || !(method instanceof ConstantExpression)) return null;
        Map<String, ImportNode> importNodes = module.getStaticImports();
        ConstantExpression ce = (ConstantExpression) method;
        Expression expression;
        Object value = ce.getValue();
        // skip non-Strings, e.g. Integer
        if (!(value instanceof String)) return null;
        final String name = (String) value;
        // look for one of these:
        //   import static SomeClass.method [as otherName]
        // when resolving methodCall() or getProp() or setProp()
        if (importNodes.containsKey(name)) {
            ImportNode importNode = importNodes.get(name);
            expression = findStaticMethod(importNode.getType(), importNode.getFieldName(), args);
            if (expression != null) return expression;
            expression = findStaticPropertyAccessorGivenArgs(importNode.getType(), getPropNameForAccessor(importNode.getFieldName()), args);
            if (expression != null) {
                // GRECLIPSE edit
                //return new StaticMethodCallExpression(importNode.getType(), importNode.getFieldName(), args);
                return newStaticMethodCallX(importNode.getType(), importNode.getFieldName(), args);
                // GRECLIPSE end
            }
        }
        // look for one of these:
        //   import static SomeClass.someProp [as otherName]
        // when resolving getProp() or setProp()
        if (validPropName(name)) {
            String propName = getPropNameForAccessor(name);
            if (importNodes.containsKey(propName)) {
                ImportNode importNode = importNodes.get(propName);
                expression = findStaticMethod(importNode.getType(), prefix(name) + capitalize(importNode.getFieldName()), args);
                if (expression != null) return expression;
                expression = findStaticPropertyAccessorGivenArgs(importNode.getType(), importNode.getFieldName(), args);
                if (expression != null) {
                    // GRECLIPSE edit
                    //return new StaticMethodCallExpression(importNode.getType(), prefix(name) + capitalize(importNode.getFieldName()), args);
                    return newStaticMethodCallX(importNode.getType(), prefix(name) + capitalize(importNode.getFieldName()), args);
                    // GRECLIPSE end
                }
            }
        }
        Map<String, ImportNode> starImports = module.getStaticStarImports();
        ClassNode starImportType;
        if (currentClass.isEnum() && starImports.containsKey(currentClass.getName())) {
            ImportNode importNode = starImports.get(currentClass.getName());
            starImportType = importNode == null ? null : importNode.getType();
            expression = findStaticMethod(starImportType, name, args);
            if (expression != null) return expression;
        } else {
            for (ImportNode importNode : starImports.values()) {
                starImportType = importNode == null ? null : importNode.getType();
                expression = findStaticMethod(starImportType, name, args);
                if (expression != null) return expression;
                expression = findStaticPropertyAccessorGivenArgs(starImportType, getPropNameForAccessor(name), args);
                if (expression != null) {
                    // GRECLIPSE edit
                    //return new StaticMethodCallExpression(starImportType, name, args);
                    return newStaticMethodCallX(starImportType, name, args);
                    // GRECLIPSE end
                }
            }
        }
        return null;
    }

    private static String prefix(String name) {
        return name.startsWith("is") ? "is" : name.substring(0, 3);
    }

    private static String getPropNameForAccessor(String fieldName) {
        int prefixLength = fieldName.startsWith("is") ? 2 : 3;
        if (fieldName.length() < prefixLength + 1) return fieldName;
        if (!validPropName(fieldName)) return fieldName;
        return String.valueOf(fieldName.charAt(prefixLength)).toLowerCase() + fieldName.substring(prefixLength + 1);
    }

    private static boolean validPropName(String propName) {
        return propName.startsWith("get") || propName.startsWith("is") || propName.startsWith("set");
    }

    private String getAccessorName(String name) {
        return (inLeftExpression ? "set" : "get") + capitalize(name);
    }

    private Expression findStaticPropertyAccessorGivenArgs(ClassNode staticImportType, String propName, Expression args) {
        // TODO validate args?
        return findStaticPropertyAccessor(staticImportType, propName);
    }

    private Expression findStaticPropertyAccessor(ClassNode staticImportType, String propName) {
        String accessorName = getAccessorName(propName);
        Expression accessor = findStaticPropertyAccessorByFullName(staticImportType, accessorName);
        if (accessor == null && accessorName.startsWith("get")) {
            accessor = findStaticPropertyAccessorByFullName(staticImportType, "is" + accessorName.substring(3));
        }
        if (accessor == null && hasStaticProperty(staticImportType, propName)) {
            // args will be replaced
            if (inLeftExpression)
                // GRECLIPSE edit
                //accessor = new StaticMethodCallExpression(staticImportType, accessorName, ArgumentListExpression.EMPTY_ARGUMENTS);
                accessor = newStaticMethodCallX(staticImportType, accessorName, ArgumentListExpression.EMPTY_ARGUMENTS);
                // GRECLIPSE end
            else
                // GRECLIPSE edit
                //accessor = new PropertyExpression(new ClassExpression(staticImportType), propName);
                accessor = newStaticPropertyX(staticImportType, propName);
                // GRECLIPSE end
        }
        return accessor;
    }

    private static boolean hasStaticProperty(ClassNode cNode, String propName) {
        return getStaticProperty(cNode, propName) != null;
    }

    private static PropertyNode getStaticProperty(ClassNode cNode, String propName) {
        ClassNode classNode = cNode;
        while (classNode != null) {
            for (PropertyNode pn : classNode.getProperties()) {
                if (pn.getName().equals(propName) && pn.isStatic()) return pn;
            }
            classNode = classNode.getSuperClass();
        }
        return null;
    }

    private Expression findStaticPropertyAccessorByFullName(ClassNode staticImportType, String accessorMethodName) {
        // anything will do as we only check size == 1
        ArgumentListExpression dummyArgs = new ArgumentListExpression();
        dummyArgs.addExpression(new EmptyExpression());
        return findStaticMethod(staticImportType, accessorMethodName, (inLeftExpression ? dummyArgs : ArgumentListExpression.EMPTY_ARGUMENTS));
    }

    private static Expression findStaticField(ClassNode staticImportType, String fieldName) {
        if (staticImportType.isPrimaryClassNode() || staticImportType.isResolved()) {
            FieldNode field = getField(staticImportType, fieldName);
            if (field != null && field.isStatic())
                // GRECLIPSE edit
                //return new PropertyExpression(new ClassExpression(staticImportType), fieldName);
                return newStaticPropertyX(staticImportType, fieldName);
                // GRECLIPSE end
        }
        return null;
    }

    private static FieldNode getField(ClassNode classNode, String fieldName) {
        ClassNode node = classNode;
        Set<String> visited = new HashSet<String>();
        while (node != null) {
            FieldNode fn = node.getDeclaredField(fieldName);
            if (fn != null) return fn;
            ClassNode[] interfaces = node.getInterfaces();
            for (ClassNode iNode : interfaces) {
                if (visited.contains(iNode.getName())) continue;
                FieldNode ifn = getField(iNode, fieldName);
                visited.add(iNode.getName());
                if (ifn != null) return ifn;
            }
            node = node.getSuperClass();
        }
        return null;
    }

    private static Expression findStaticMethod(ClassNode staticImportType, String methodName, Expression args) {
        if (staticImportType.isPrimaryClassNode() || staticImportType.isResolved()) {
            if (staticImportType.hasPossibleStaticMethod(methodName, args)) {
                // GRECLIPSE edit
                //return new StaticMethodCallExpression(staticImportType, methodName, args);
                return newStaticMethodCallX(staticImportType, methodName, args);
                // GRECLIPSE end
            }
        }
        return null;
    }

    // GRECLIPSE add
    private static PropertyExpression newStaticPropertyX(ClassNode type, String name) {
        return new PropertyExpression(new ClassExpression(type.getPlainNodeReference()), name);
    }

    private static StaticMethodCallExpression newStaticMethodCallX(ClassNode type, String name, Expression args) {
        return new StaticMethodCallExpression(type.getPlainNodeReference(), name, args);
    }
    // GRECLIPSE end

    protected SourceUnit getSourceUnit() {
        return source;
    }
}
