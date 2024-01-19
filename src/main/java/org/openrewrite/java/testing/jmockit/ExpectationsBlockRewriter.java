/*
 * Copyright 2024 the original author or authors.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * https://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.openrewrite.java.testing.jmockit;

import org.openrewrite.Cursor;
import org.openrewrite.ExecutionContext;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.JavaTemplate;
import org.openrewrite.java.JavaVisitor;
import org.openrewrite.java.tree.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

class ExpectationsBlockRewriter {

    private static final String WHEN_TEMPLATE_PREFIX = "when(#{any()}).";
    private static final String RETURN_TEMPLATE_PREFIX = "thenReturn(";
    private static final String THROW_TEMPLATE_PREFIX = "thenThrow(";
    private static final String PRIMITIVE_TEMPLATE_FIELD = "#{}";
    private static final String THROWABLE_TEMPLATE_FIELD = "#{any()}";

    private static String getObjectTemplateField(String fqn) {
        return "#{any(" + fqn + ")}";
    }

    private final JavaVisitor<ExecutionContext> visitor;
    private final ExecutionContext ctx;
    private final J.NewClass newExpectations;
    // index of the Expectations block in the method body
    private final int bodyStatementIndex;
    private J.Block methodBody;
    private JavaCoordinates nextStatementCoordinates;

    // keep track of the additional statements being added to the method body, which impacts the statement indices
    // used with bodyStatementIndex to obtain the coordinates of the next statement to be written
    private int numStatementsAdded = 0;

    ExpectationsBlockRewriter(JavaVisitor<ExecutionContext> visitor, ExecutionContext ctx, J.Block methodBody,
                              J.NewClass newExpectations, int bodyStatementIndex) {
        this.visitor = visitor;
        this.ctx = ctx;
        this.methodBody = methodBody;
        this.newExpectations = newExpectations;
        this.bodyStatementIndex = bodyStatementIndex;
        nextStatementCoordinates = newExpectations.getCoordinates().replace();
    }

    J.Block rewriteMethodBody() {
        visitor.maybeRemoveImport("mockit.Expectations");

        assert newExpectations.getBody() != null;
        J.Block expectationsBlock = (J.Block) newExpectations.getBody().getStatements().get(0);

        // rewrite the argument matchers in the expectations block
        ArgumentMatchersRewriter amr = new ArgumentMatchersRewriter(visitor, ctx, expectationsBlock);
        expectationsBlock = amr.rewriteExpectationsBlock();

        // iterate over the expectations statements and rebuild the method body
        List<Statement> expectationStatements = new ArrayList<>();
        for (Statement expectationStatement : expectationsBlock.getStatements()) {
            if (expectationStatement instanceof J.MethodInvocation) {
                // handle returns statements
                J.MethodInvocation invocation = (J.MethodInvocation) expectationStatement;
                if (invocation.getSelect() == null && invocation.getName().getSimpleName().equals("returns")) {
                    expectationStatements.add(expectationStatement);
                    continue;
                }
                // if a new method invocation is found, apply the template to the previous statements
                if (!expectationStatements.isEmpty()) {
                    // apply template to build new method body
                    rewriteMethodBody(expectationStatements);

                    // reset statements for next expectation
                    expectationStatements = new ArrayList<>();
                }
            }
            expectationStatements.add(expectationStatement);
        }

        // handle the last statement
        if (!expectationStatements.isEmpty()) {
            rewriteMethodBody(expectationStatements);
        }

        return methodBody;
    }

    private void rewriteMethodBody(List<Statement> expectationStatements) {
        J.MethodInvocation invocation = (J.MethodInvocation) expectationStatements.get(0);
        final MockInvocationResults mockInvocationResults = buildMockInvocationResults(expectationStatements);

        if (!mockInvocationResults.getResults().isEmpty()) {
            // rewrite the statement to mockito if there are results
            rewriteExpectationResult(mockInvocationResults.getResults(), invocation);
        } else if (nextStatementCoordinates.isReplacement()) {
            // if there are no results and the Expectations block is not yet replaced, remove it
            removeExpectationsStatement();
        }
        if (mockInvocationResults.getTimes() != null) {
            // add a verification statement to the end of the test method body
            writeMethodVerification(invocation, mockInvocationResults.getTimes());
        }
    }

    private void rewriteExpectationResult(List<Expression> results, J.MethodInvocation invocation) {
        visitor.maybeAddImport("org.mockito.Mockito", "when");

        String template = getMockitoStatementTemplate(results);
        List<Object> templateParams = new ArrayList<>();
        templateParams.add(invocation);
        templateParams.addAll(results);

        methodBody = JavaTemplate.builder(template)
                .javaParser(JavaParser.fromJavaVersion().classpathFromResources(ctx, "mockito-core-3.12"))
                .staticImports("org.mockito.Mockito.*")
                .build()
                .apply(
                        new Cursor(visitor.getCursor(), methodBody),
                        nextStatementCoordinates,
                        templateParams.toArray()
                );
        if (!nextStatementCoordinates.isReplacement()) {
            numStatementsAdded += 1;
        }

        // the next statement coordinates are directly after the most recently written statement
        nextStatementCoordinates = methodBody.getStatements().get(bodyStatementIndex + numStatementsAdded)
                .getCoordinates().after();
    }

    private void removeExpectationsStatement() {
        methodBody = JavaTemplate.builder("")
                .javaParser(JavaParser.fromJavaVersion())
                .build()
                .apply(
                        new Cursor(visitor.getCursor(), methodBody),
                        nextStatementCoordinates
                );

        // the next statement coordinates are directly after the most recently added statement, or the first statement
        // of the test method body if the Expectations block was the first statement
        nextStatementCoordinates = bodyStatementIndex == 0 ? methodBody.getCoordinates().firstStatement() :
                methodBody.getStatements().get(bodyStatementIndex + numStatementsAdded).getCoordinates().after();
    }

    private void writeMethodVerification(J.MethodInvocation invocation, Expression times) {
        visitor.maybeAddImport("org.mockito.Mockito", "verify");
        visitor.maybeAddImport("org.mockito.Mockito", "times");

        String fqn = getInvocationSelectFullyQualifiedClassName(invocation);
        String verifyTemplate = getVerifyTemplate(invocation.getArguments(), fqn);
        Object[] templateParams = new Object[] {
                invocation.getSelect(),
                times,
                invocation.getName().getSimpleName()
        };
        methodBody = JavaTemplate.builder(verifyTemplate)
                .javaParser(JavaParser.fromJavaVersion().classpathFromResources(ctx, "mockito-core-3.12"))
                .staticImports("org.mockito.Mockito.*")
                .imports(fqn)
                .build()
                .apply(
                        new Cursor(visitor.getCursor(), methodBody),
                        methodBody.getCoordinates().lastStatement(),
                        templateParams
                );
    }

    private static String getMockitoStatementTemplate(List<Expression> results) {
        boolean buildingResults = false;
        final StringBuilder templateBuilder = new StringBuilder(WHEN_TEMPLATE_PREFIX);
        for (Expression result : results) {
            JavaType resultType = result.getType();
            if (resultType instanceof JavaType.Primitive) {
                appendToTemplate(templateBuilder, buildingResults, RETURN_TEMPLATE_PREFIX, PRIMITIVE_TEMPLATE_FIELD);
            } else if (resultType instanceof JavaType.Class) {
                boolean isThrowable = TypeUtils.isAssignableTo(Throwable.class.getName(), resultType);
                if (isThrowable) {
                    appendToTemplate(templateBuilder, buildingResults, THROW_TEMPLATE_PREFIX, THROWABLE_TEMPLATE_FIELD);
                } else {
                    appendToTemplate(templateBuilder, buildingResults, RETURN_TEMPLATE_PREFIX,
                            getObjectTemplateField(((JavaType.Class) resultType).getFullyQualifiedName()));
                }
            } else if (resultType instanceof JavaType.Parameterized) {
                appendToTemplate(templateBuilder, buildingResults, RETURN_TEMPLATE_PREFIX,
                        getObjectTemplateField(((JavaType.Parameterized) resultType).getType().getFullyQualifiedName()));
            } else {
                throw new IllegalStateException("Unexpected expression type for template: " + result.getType());
            }
            buildingResults = true;
        }
        templateBuilder.append(");");
        return templateBuilder.toString();
    }

    private static void appendToTemplate(StringBuilder templateBuilder, boolean buildingResults, String templatePrefix,
                                         String templateField) {
        if (!buildingResults) {
            templateBuilder.append(templatePrefix);
        } else {
            templateBuilder.append(", ");
        }
        templateBuilder.append(templateField);
    }

    private static String getVerifyTemplate(List<Expression> arguments, String fqn) {
        if (arguments.isEmpty()) {
            return "verify(#{any(" + fqn + ")}, times(#{any(int)})).#{}();";
        }
        StringBuilder templateBuilder = new StringBuilder("verify(#{any(" + fqn + ")}, times(#{any(int)})).#{}(");
        for (Expression argument : arguments) {
            if (argument instanceof J.Literal) {
                templateBuilder.append(((J.Literal) argument).getValueSource());
            } else {
                templateBuilder.append(argument);
            }
            templateBuilder.append(", ");
        }
        templateBuilder.delete(templateBuilder.length() - 2, templateBuilder.length());
        templateBuilder.append(");");
        return templateBuilder.toString();
    }

    private static MockInvocationResults buildMockInvocationResults(List<Statement> expectationStatements) {
        int numResults = 0;
        boolean hasTimes = false;
        final MockInvocationResults resultWrapper = new MockInvocationResults();
        for (int i = 1; i < expectationStatements.size(); i++) {
            Statement expectationStatement = expectationStatements.get(i);
            if (expectationStatement instanceof J.MethodInvocation) {
                if (hasTimes) {
                    throw new IllegalStateException("times statement must be last in expectation");
                }
                // handle returns statement
                J.MethodInvocation invocation = (J.MethodInvocation) expectationStatement;
                for (Expression argument : invocation.getArguments()) {
                    numResults += 1;
                    resultWrapper.addResult(argument);
                }
                continue;
            }
            J.Assignment assignment = (J.Assignment) expectationStatement;
            if (!(assignment.getVariable() instanceof J.Identifier)) {
                throw new IllegalStateException("Unexpected assignment variable type: " + assignment.getVariable());
            }
            J.Identifier identifier = (J.Identifier) assignment.getVariable();
            boolean isResult = identifier.getSimpleName().equals("result");
            boolean isTimes = identifier.getSimpleName().equals("times");
            if (isResult) {
                if (hasTimes) {
                    throw new IllegalStateException("times statement must be last in expectation");
                }
                numResults += 1;
                resultWrapper.addResult(assignment.getAssignment());
            } else if (isTimes) {
                hasTimes = true;
                if (numResults > 1) {
                    throw new IllegalStateException("multiple results cannot be used with times statement");
                }
                resultWrapper.setTimes(assignment.getAssignment());
            }
        }
        return resultWrapper;
    }

    private static String getInvocationSelectFullyQualifiedClassName(J.MethodInvocation invocation) {
        Expression select = invocation.getSelect();
        if (select == null || select.getType() == null) {
            throw new IllegalStateException("Missing type information for invocation select field: " + select);
        }
        String fqn = ""; // default to empty string to support method invocations
        if (select instanceof J.Identifier) {
            fqn = ((JavaType.FullyQualified) Objects.requireNonNull(select.getType())).getFullyQualifiedName();
        }
        return fqn;
    }

    private static class MockInvocationResults {
        private final List<Expression> results = new ArrayList<>();
        private Expression times;

        private List<Expression> getResults() {
            return results;
        }

        private void addResult(Expression result) {
            results.add(result);
        }

        private Expression getTimes() {
            return times;
        }

        private void setTimes(Expression times) {
            this.times = times;
        }
    }
}