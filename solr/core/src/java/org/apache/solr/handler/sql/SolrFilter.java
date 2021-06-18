/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.solr.handler.sql;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelOptCost;
import org.apache.calcite.plan.RelOptPlanner;
import org.apache.calcite.plan.RelOptUtil;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Filter;
import org.apache.calcite.rel.metadata.RelMetadataQuery;
import org.apache.calcite.rex.RexCall;
import org.apache.calcite.rex.RexInputRef;
import org.apache.calcite.rex.RexLiteral;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.util.Pair;

/**
 * Implementation of a {@link org.apache.calcite.rel.core.Filter} relational expression in Solr.
 */
class SolrFilter extends Filter implements SolrRel {
  SolrFilter(
      RelOptCluster cluster,
      RelTraitSet traitSet,
      RelNode child,
      RexNode condition) {
    super(cluster, traitSet, child, condition);
    assert getConvention() == SolrRel.CONVENTION;
    assert getConvention() == child.getConvention();
  }

  @Override
  public RelOptCost computeSelfCost(RelOptPlanner planner, RelMetadataQuery mq) {
    return super.computeSelfCost(planner, mq).multiplyBy(0.1);
  }

  public SolrFilter copy(RelTraitSet traitSet, RelNode input, RexNode condition) {
    return new SolrFilter(getCluster(), traitSet, input, condition);
  }

  public void implement(Implementor implementor) {
    implementor.visitChild(0, getInput());
    if (getInput() instanceof SolrAggregate) {
      HavingTranslator translator = new HavingTranslator(SolrRules.solrFieldNames(getRowType()), implementor.reverseAggMappings);
      String havingPredicate = translator.translateMatch(condition);
      implementor.setHavingPredicate(havingPredicate);
    } else {
      Translator translator = new Translator(SolrRules.solrFieldNames(getRowType()));
      String query = translator.translateMatch(condition);
      implementor.addQuery(query);
      implementor.setNegativeQuery(translator.negativeQuery);
    }
  }

  private static class Translator {

    protected final List<String> fieldNames;
    public boolean negativeQuery = true;

    Translator(List<String> fieldNames) {
      this.fieldNames = fieldNames;
    }

    protected String translateMatch(RexNode condition) {
      final SqlKind kind = condition.getKind();
      if (kind.belongsTo(SqlKind.COMPARISON) || kind == SqlKind.NOT) {
        return translateComparison(condition);
      } else if (condition.isA(SqlKind.AND)) {
        // see if this is a translated range query of greater than or equals and less than or equal on same field
        // if so, then collapse into a single range criteria, e.g. field:[gte TO lte] instead of two ranges AND'd together
        RexCall call = (RexCall) condition;
        List<RexNode> operands = call.getOperands();
        String query = null;
        if (operands.size() == 2) {
          RexNode lhs = operands.get(0);
          RexNode rhs = operands.get(1);
          if (lhs.getKind() == SqlKind.GREATER_THAN_OR_EQUAL && rhs.getKind() == SqlKind.LESS_THAN_OR_EQUAL) {
            query = translateBetween(lhs, rhs);
          } else if (lhs.getKind() == SqlKind.LESS_THAN_OR_EQUAL && rhs.getKind() == SqlKind.GREATER_THAN_OR_EQUAL) {
            // just swap the nodes
            query = translateBetween(rhs, lhs);
          }
        }
        return query != null ? query : "(" + translateAnd(condition) + ")";
      } else if (condition.isA(SqlKind.OR)) {
        return "(" + translateOr(condition) + ")";
      } else if (kind == SqlKind.LIKE) {
        return translateLike(condition, false);
      } else if (kind == SqlKind.IS_NOT_NULL || kind == SqlKind.IS_NULL) {
        return translateIsNullOrIsNotNull(condition);
      } else {
        return null;
      }
    }

    protected String translateBetween(RexNode gteNode, RexNode lteNode) {
      Pair<String, RexLiteral> gte = getFieldValuePair(gteNode);
      Pair<String, RexLiteral> lte = getFieldValuePair(lteNode);
      String fieldName = gte.getKey();
      String query = null;
      if (fieldName.equals(lte.getKey()) && compareRexLiteral(gte.right, lte.right) < 0) {
        query = fieldName + ":[" + gte.getValue() + " TO " + lte.getValue() + "]";
        this.negativeQuery = false; // so we don't get *:* AND range
      }
      return query;
    }

    @SuppressWarnings("unchecked")
    private int compareRexLiteral(final RexLiteral gte, final RexLiteral lte) {
      return gte.getValue().compareTo(lte.getValue());
    }

    protected String translateIsNullOrIsNotNull(RexNode node) {
      if (!(node instanceof RexCall)) {
        throw new AssertionError("expected RexCall for predicate but found: " + node);
      }
      RexCall call = (RexCall) node;
      List<RexNode> operands = call.getOperands();
      if (operands.size() != 1) {
        throw new AssertionError("expected 1 operand for " + node);
      }

      final RexNode left = operands.get(0);
      if (left instanceof RexInputRef) {
        String name = fieldNames.get(((RexInputRef) left).getIndex());
        SqlKind kind = node.getKind();
        this.negativeQuery = false;
        return kind == SqlKind.IS_NOT_NULL ? "+" + name + ":*" : "(*:* -" + name + ":*)";
      }

      throw new AssertionError("expected field ref but found " + left);
    }

    protected String translateOr(RexNode condition) {
      List<String> ors = new ArrayList<>();
      for (RexNode node : RelOptUtil.disjunctions(condition)) {
        ors.add(translateMatch(node));
      }
      return String.join(" OR ", ors);
    }

    protected String translateAnd(RexNode node0) {
      List<String> andStrings = new ArrayList<>();
      List<String> notStrings = new ArrayList<>();

      List<RexNode> ands = new ArrayList<>();
      List<RexNode> nots = new ArrayList<>();
      RelOptUtil.decomposeConjunction(node0, ands, nots);


      for (RexNode node : ands) {
        andStrings.add(translateMatch(node));
      }

      String andString = String.join(" AND ", andStrings);

      if (!nots.isEmpty()) {
        for (RexNode node : nots) {
          notStrings.add(translateMatch(node));
        }
        String notString = String.join(" NOT ", notStrings);
        return "(" + andString + ") NOT (" + notString + ")";
      } else {
        return andString;
      }
    }

    protected String translateLike(RexNode like, boolean isNegativeQuery) {
      Pair<String, RexLiteral> pair = getFieldValuePair(like);
      String terms = pair.getValue().toString().trim();
      terms = terms.replace("'", "").replace('%', '*').replace('_', '?');
      boolean wrappedQuotes = false;
      if (!terms.startsWith("(") && !terms.startsWith("[") && !terms.startsWith("{")) {
        terms = "\"" + terms + "\"";
        wrappedQuotes = true;
      }

      this.negativeQuery = isNegativeQuery;
      String query = pair.getKey() + ":" + terms;
      return wrappedQuotes ? "{!complexphrase}" + query : query;
    }

    protected String translateComparison(RexNode node) {
      final SqlKind kind = node.getKind();
      if (kind == SqlKind.NOT) {
        RexNode negated = ((RexCall) node).getOperands().get(0);
        return "-" + (negated.getKind() == SqlKind.LIKE ? translateLike(negated, true) : translateComparison(negated));
      }

      Pair<String, RexLiteral> binaryTranslated = getFieldValuePair(node);
      switch (kind) {
        case EQUALS:
          String terms = binaryTranslated.getValue().toString().trim();
          terms = terms.replace("'", "");
          boolean wrappedQuotes = false;
          if (!terms.startsWith("(") && !terms.startsWith("[") && !terms.startsWith("{")) {
            terms = "\"" + terms + "\"";
            wrappedQuotes = true;
          }

          String clause = binaryTranslated.getKey() + ":" + terms;
          if (terms.contains("*") && wrappedQuotes) {
            clause = "{!complexphrase}" + clause;
          }
          this.negativeQuery = false;
          return clause;
        case NOT_EQUALS:
          return "-(" + binaryTranslated.getKey() + ":" + binaryTranslated.getValue() + ")";
        case LESS_THAN:
          this.negativeQuery = false;
          return "(" + binaryTranslated.getKey() + ": [ * TO " + binaryTranslated.getValue() + " })";
        case LESS_THAN_OR_EQUAL:
          this.negativeQuery = false;
          return "(" + binaryTranslated.getKey() + ": [ * TO " + binaryTranslated.getValue() + " ])";
        case GREATER_THAN:
          this.negativeQuery = false;
          return "(" + binaryTranslated.getKey() + ": { " + binaryTranslated.getValue() + " TO * ])";
        case GREATER_THAN_OR_EQUAL:
          this.negativeQuery = false;
          return "(" + binaryTranslated.getKey() + ": [ " + binaryTranslated.getValue() + " TO * ])";
        case LIKE:
          return translateLike(node, false);
        case IS_NOT_NULL:
        case IS_NULL:
          return translateIsNullOrIsNotNull(node);
        default:
          throw new AssertionError("cannot translate " + node);
      }
    }

    protected Pair<String, RexLiteral> getFieldValuePair(RexNode node) {
      if (!(node instanceof RexCall)) {
        throw new AssertionError("expected RexCall for predicate but found: " + node);
      }

      RexCall call = (RexCall) node;
      Pair<String, RexLiteral> binaryTranslated = call.getOperands().size() == 2 ? translateBinary(call) : null;
      if (binaryTranslated == null) {
        throw new AssertionError("unsupported predicate expression: " + node);
      }

      return binaryTranslated;
    }

    /**
     * Translates a call to a binary operator, reversing arguments if necessary.
     */
    protected Pair<String, RexLiteral> translateBinary(RexCall call) {
      List<RexNode> operands = call.getOperands();
      if (operands.size() != 2) {
        throw new AssertionError("Invalid number of arguments - " + operands.size());
      }
      final RexNode left = operands.get(0);
      final RexNode right = operands.get(1);
      final Pair<String, RexLiteral> a = translateBinary2(left, right);
      if (a != null) {
        return a;
      }
      final Pair<String, RexLiteral> b = translateBinary2(right, left);
      if (b != null) {
        return b;
      }
      throw new AssertionError("cannot translate call " + call);
    }

    /**
     * Translates a call to a binary operator. Returns whether successful.
     */
    protected Pair<String, RexLiteral> translateBinary2(RexNode left, RexNode right) {
      if (right.getKind() != SqlKind.LITERAL) {
        return null;
      }

      final RexLiteral rightLiteral = (RexLiteral) right;
      switch (left.getKind()) {
        case INPUT_REF:
          final RexInputRef left1 = (RexInputRef) left;
          String name = fieldNames.get(left1.getIndex());
          return new Pair<>(name, rightLiteral);
        case CAST:
          return translateBinary2(((RexCall) left).operands.get(0), right);
//        case OTHER_FUNCTION:
//          String itemName = SolrRules.isItem((RexCall) left);
//          if (itemName != null) {
//            return translateOp2(op, itemName, rightLiteral);
//          }
        default:
          return null;
      }
    }
  }

  private static class HavingTranslator extends Translator {

    private final Map<String, String> reverseAggMappings;

    HavingTranslator(List<String> fieldNames, Map<String, String> reverseAggMappings) {
      super(fieldNames);
      this.reverseAggMappings = reverseAggMappings;
    }

    @Override
    protected String translateMatch(RexNode condition) {
      if (condition.getKind().belongsTo(SqlKind.COMPARISON)) {
        return translateComparison(condition);
      } else if (condition.isA(SqlKind.AND)) {
        return translateAnd(condition);
      } else if (condition.isA(SqlKind.OR)) {
        return translateOr(condition);
      } else {
        return null;
      }
    }

    @Override
    protected String translateOr(RexNode condition) {
      List<String> ors = new ArrayList<>();
      for (RexNode node : RelOptUtil.disjunctions(condition)) {
        ors.add(translateMatch(node));
      }
      StringBuilder builder = new StringBuilder();

      builder.append("or(");
      for (int i = 0; i < ors.size(); i++) {
        if (i > 0) {
          builder.append(",");
        }

        builder.append(ors.get(i));
      }
      builder.append(")");
      return builder.toString();
    }

    @Override
    protected String translateAnd(RexNode node0) {
      List<String> andStrings = new ArrayList<>();
      List<String> notStrings = new ArrayList<>();

      List<RexNode> ands = new ArrayList<>();
      List<RexNode> nots = new ArrayList<>();

      RelOptUtil.decomposeConjunction(node0, ands, nots);

      for (RexNode node : ands) {
        andStrings.add(translateMatch(node));
      }

      StringBuilder builder = new StringBuilder();

      builder.append("and(");
      for (int i = 0; i < andStrings.size(); i++) {
        if (i > 0) {
          builder.append(",");
        }

        builder.append(andStrings.get(i));
      }
      builder.append(")");


      if (!nots.isEmpty()) {
        for (RexNode node : nots) {
          notStrings.add(translateMatch(node));
        }

        StringBuilder notBuilder = new StringBuilder();
        for (int i = 0; i < notStrings.size(); i++) {
          if (i > 0) {
            notBuilder.append(",");
          }
          notBuilder.append("not(");
          notBuilder.append(notStrings.get(i));
          notBuilder.append(")");
        }

        return "and(" + builder.toString() + "," + notBuilder.toString() + ")";
      } else {
        return builder.toString();
      }
    }

    /**
     * Translates a call to a binary operator, reversing arguments if necessary.
     */
    @Override
    protected Pair<String, RexLiteral> translateBinary(RexCall call) {
      List<RexNode> operands = call.getOperands();
      if (operands.size() != 2) {
        throw new AssertionError("Invalid number of arguments - " + operands.size());
      }
      final RexNode left = operands.get(0);
      final RexNode right = operands.get(1);
      final Pair<String, RexLiteral> a = translateBinary2(left, right);

      if (a != null) {
        if (reverseAggMappings.containsKey(a.getKey())) {
          return new Pair<>(reverseAggMappings.get(a.getKey()), a.getValue());
        }
        return a;
      }
      final Pair<String, RexLiteral> b = translateBinary2(right, left);
      if (b != null) {
        return b;
      }
      throw new AssertionError("cannot translate call " + call);
    }

    @Override
    protected String translateComparison(RexNode node) {
      Pair<String, RexLiteral> binaryTranslated = getFieldValuePair(node);
      switch (node.getKind()) {
        case EQUALS:
          String terms = binaryTranslated.getValue().toString().trim();
          return "eq(" + binaryTranslated.getKey() + "," + terms + ")";
        case NOT_EQUALS:
          return "not(eq(" + binaryTranslated.getKey() + "," + binaryTranslated.getValue() + "))";
        case LESS_THAN:
          return "lt(" + binaryTranslated.getKey() + "," + binaryTranslated.getValue() + ")";
        case LESS_THAN_OR_EQUAL:
          return "lteq(" + binaryTranslated.getKey() + "," + binaryTranslated.getValue() + ")";
        case GREATER_THAN:
          return "gt(" + binaryTranslated.getKey() + "," + binaryTranslated.getValue() + ")";
        case GREATER_THAN_OR_EQUAL:
          return "gteq(" + binaryTranslated.getKey() + "," + binaryTranslated.getValue() + ")";
        default:
          throw new AssertionError("cannot translate " + node);
      }
    }
  }
}