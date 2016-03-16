package com.gu.scanamo

import com.amazonaws.services.dynamodbv2.model.{AttributeValue, QueryRequest}
import simulacrum.typeclass

import collection.convert.decorateAsJava._

sealed abstract class DynamoOperator(val op: String)
object LT  extends DynamoOperator("<")
object LTE extends DynamoOperator("<=")
object GT  extends DynamoOperator(">")
object GTE extends DynamoOperator(">=")


@typeclass trait QueryableKeyCondition[T] {
  def apply(t: T)(req: QueryRequest): QueryRequest
}

trait Query {
  def apply(req: QueryRequest): QueryRequest
}

object QueryableKeyCondition {
  implicit def equalsKeyCondition[V] = new QueryableKeyCondition[EqualsKeyCondition[V]] {
    override def apply(t: EqualsKeyCondition[V])(req: QueryRequest): QueryRequest =
      req.withKeyConditionExpression(s"#K = :${t.key.name}")
        .withExpressionAttributeNames(Map("#K" -> t.key.name).asJava)
        .withExpressionAttributeValues(Map(s":${t.key.name}" -> t.f.write(t.v)).asJava)
  }
  implicit def hashAndRangeQueryCondition[H, R] = new QueryableKeyCondition[AndQueryCondition[H, R]] {
    override def apply(t: AndQueryCondition[H, R])(req: QueryRequest): QueryRequest =
      req
        .withKeyConditionExpression(
          s"#K = :${t.hashCondition.key.name} AND ${t.rangeCondition.keyConditionExpression("R")}"
        )
        .withExpressionAttributeNames(Map("#K" -> t.hashCondition.key.name, "#R" -> t.rangeCondition.key.name).asJava)
        .withExpressionAttributeValues(
          Map(
            s":${t.hashCondition.key.name}" -> t.fH.write(t.hashCondition.v),
            s":${t.rangeCondition.key.name}" -> t.fR.write(t.rangeCondition.v)
          ).asJava
        )
  }
}

trait AttributeValueMap {
  def asAVMap: Map[String, AttributeValue]
}

@typeclass trait UniqueKeyCondition[T] {
  def asAVMap(t: T): Map[String, AttributeValue]
}
object UniqueKeyCondition {
  implicit def uniqueEqualsKey[V] = new UniqueKeyCondition[EqualsKeyCondition[V]] {
    override def asAVMap(t: EqualsKeyCondition[V]): Map[String, AttributeValue] =
      Map(t.key.name -> t.f.write(t.v))
  }
  implicit def uniqueAndEqualsKey[H, R] = new UniqueKeyCondition[AndEqualsCondition[H, R]] {
    override def asAVMap(t: AndEqualsCondition[H, R]): Map[String, AttributeValue] =
      t.hashCondition.asAVMap(t.hashEquality) ++ t.rangeCondition.asAVMap(t.rangeEquality)
  }
}

case class AndEqualsCondition[H, R](hashEquality: H, rangeEquality: R)(
  implicit val hashCondition: UniqueKeyCondition[H], val rangeCondition: UniqueKeyCondition[R])

case class EqualsKeyCondition[V](key: Symbol, v: V)(implicit val f: DynamoFormat[V]) {
  def and[R](rangeKeyCondition: RangeKeyCondition[R])(implicit fR: DynamoFormat[R]) =
    AndQueryCondition(this, rangeKeyCondition)
}
case class AndQueryCondition[H, R](hashCondition: EqualsKeyCondition[H], rangeCondition: RangeKeyCondition[R])
  (implicit val fH: DynamoFormat[H], val fR: DynamoFormat[R])

sealed abstract class RangeKeyCondition[V](implicit f: DynamoFormat[V]) {
  val key: Symbol
  val v: V
  def keyConditionExpression(s: String): String
}

case class SimpleKeyCondition[V](key: Symbol, v: V, operator: DynamoOperator)(implicit f: DynamoFormat[V]) extends RangeKeyCondition[V]{
  def keyConditionExpression(s: String): String = s"#$s ${operator.op} :${key.name}"
}

case class BeginsWithCondition[V](key: Symbol, v: V)(implicit f: DynamoFormat[V]) extends RangeKeyCondition[V] {
  override def keyConditionExpression(s: String): String = s"begins_with(#$s, :${key.name})"
}

object DynamoKeyCondition {
  object syntax {
    implicit class SymbolKeyCondition(s: Symbol) {
      def <[V](v: V)(implicit f: DynamoFormat[V]) = SimpleKeyCondition(s, v, LT)
      def >[V](v: V)(implicit f: DynamoFormat[V]) = SimpleKeyCondition(s, v, GT)
      def <=[V](v: V)(implicit f: DynamoFormat[V]) = SimpleKeyCondition(s, v, LTE)
      def >=[V](v: V)(implicit f: DynamoFormat[V]) = SimpleKeyCondition(s, v, GTE)
      def beginsWith[V](v: V)(implicit f: DynamoFormat[V]) = BeginsWithCondition(s, v)


      def ===[V](v: V)(implicit f: DynamoFormat[V]) = EqualsKeyCondition(s, v)
    }


    implicit def symbolTupleToKeyCondition[V](pair: (Symbol, V))(implicit f: DynamoFormat[V]) =
      EqualsKeyCondition(pair._1, pair._2)

    implicit def toAVMap[T](t: T)(implicit kc: UniqueKeyCondition[T]) =
      new AttributeValueMap {
        override def asAVMap: Map[String, AttributeValue] = kc.asAVMap(t)
      }

    implicit def toQuery[T](t: T)(implicit queryableKeyCondition: QueryableKeyCondition[T]) =
      new Query {
        override def apply(req: QueryRequest): QueryRequest = queryableKeyCondition(t)(req)
      }
  }
}



