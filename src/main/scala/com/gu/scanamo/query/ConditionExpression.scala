package com.gu.scanamo.query

import cats.data.Xor
import com.amazonaws.services.dynamodbv2.model._
import com.gu.scanamo.DynamoFormat
import com.gu.scanamo.ops.ScanamoOps
import com.gu.scanamo.request.{RequestCondition, ScanamoDeleteRequest, ScanamoPutRequest, ScanamoUpdateRequest}
import com.gu.scanamo.update.UpdateExpression
import simulacrum.typeclass

case class ConditionalOperation[T](tableName: String, t: T)(implicit state: ConditionExpression[T]) {
  def put[V](item: V)(implicit f: DynamoFormat[V]): ScanamoOps[Xor[ConditionalCheckFailedException, PutItemResult]] = {
    val unconditionalRequest = ScanamoPutRequest(tableName, f.write(item), None)
    ScanamoOps.conditionalPut(unconditionalRequest.copy(
      condition = Some(state.apply(t)(unconditionalRequest.condition))))
  }

  def delete(key: UniqueKey[_]): ScanamoOps[Xor[ConditionalCheckFailedException, DeleteItemResult]] = {
    val unconditionalRequest = ScanamoDeleteRequest(tableName = tableName, key = key.asAVMap, None)
    ScanamoOps.conditionalDelete(unconditionalRequest.copy(
      condition = Some(state.apply(t)(unconditionalRequest.condition))))
  }

  def update[U](key: UniqueKey[_], expression: U)(implicit update: UpdateExpression[U]):
    ScanamoOps[Xor[ConditionalCheckFailedException, UpdateItemResult]] = {

    val unconditionalRequest = ScanamoUpdateRequest(
      tableName, key.asAVMap, update.expression(expression), update.attributeNames(expression), update.attributeValues(expression), None)
    ScanamoOps.conditionalUpdate(unconditionalRequest.copy(
      condition = Some(state.apply(t)(unconditionalRequest.condition))))
  }
}

@typeclass trait ConditionExpression[T] {
  def apply(t: T)(condition: Option[RequestCondition]): RequestCondition
}

object ConditionExpression {
  implicit def symbolValueEqualsCondition[V: DynamoFormat] = new ConditionExpression[(Symbol, V)] {
    override def apply(pair: (Symbol, V))(condition: Option[RequestCondition]): RequestCondition =
      RequestCondition(
        s"#condition = :${pair._1.name}",
        Map("#condition" -> pair._1.name),
        Some(Map(s":${pair._1.name}" -> DynamoFormat[V].write(pair._2)))
      )
  }

  implicit def attributeExistsCondition = new ConditionExpression[AttributeExists] {
    override def apply(t: AttributeExists)(condition: Option[RequestCondition]): RequestCondition =
      RequestCondition("attribute_exists(#conditionttr)", Map("#conditionttr" -> t.key.name), None)
  }

  implicit def notCondition[T](implicit pcs: ConditionExpression[T]) = new ConditionExpression[Not[T]] {
    override def apply(not: Not[T])(condition: Option[RequestCondition]): RequestCondition = {
      val conditionToNegate = pcs(not.condition)(condition)
      conditionToNegate.copy(expression = s"NOT(${conditionToNegate.expression})")
    }
  }

  implicit def beginsWithCondition[V: DynamoFormat] = new ConditionExpression[BeginsWith[V]] {
    override def apply(b: BeginsWith[V])(condition: Option[RequestCondition]): RequestCondition =
      RequestCondition(
        s"begins_with(#condition, :${b.key.name})",
        Map("#condition" -> b.key.name),
        Some(Map(s":${b.key.name}" -> DynamoFormat[V].write(b.v)))
      )
  }

  implicit def keyIsCondition[V: DynamoFormat] = new ConditionExpression[KeyIs[V]] {
    override def apply(k: KeyIs[V])(condition: Option[RequestCondition]): RequestCondition =
      RequestCondition(
        s"#condition ${k.operator.op} :${k.key.name}",
        Map("#condition" -> k.key.name),
        Some(Map(s":${k.key.name}" -> DynamoFormat[V].write(k.v)))
      )
  }

  implicit def andCondition[L, R](implicit lce: ConditionExpression[L], rce: ConditionExpression[R]) =
    new ConditionExpression[AndCondition[L, R]] {
      override def apply(and: AndCondition[L, R])(condition: Option[RequestCondition]): RequestCondition =
        combineConditions(condition, and.l, and.r, "AND")
    }

  implicit def orCondition[L, R](implicit lce: ConditionExpression[L], rce: ConditionExpression[R]) =
    new ConditionExpression[OrCondition[L, R]] {
      override def apply(and: OrCondition[L, R])(condition: Option[RequestCondition]): RequestCondition =
        combineConditions(condition, and.l, and.r, "OR")
    }

  private def combineConditions[L, R](condition: Option[RequestCondition], l: L, r: R, combininingOperator: String)(
    implicit lce: ConditionExpression[L], rce: ConditionExpression[R]): RequestCondition = {
    def prefixKeys[T](map: Map[String, T], prefix: String, magicChar: Char) = map.map {
      case (k, v) => (newKey(k, prefix, magicChar), v)
    }
    def newKey(oldKey: String, prefix: String, magicChar: Char) =
      s"$magicChar$prefix${oldKey.stripPrefix(magicChar.toString)}"

    def prefixKeysIn(string: String, keys: Iterable[String], prefix: String, magicChar: Char) =
      keys.foldLeft(string)((result, key) => result.replaceAllLiterally(key, newKey(key, prefix, magicChar)))

    val lPrefix = s"${combininingOperator.toLowerCase}_l_"
    val rPrefix = s"${combininingOperator.toLowerCase}_r_"

    val lCondition = lce(l)(condition)
    val rCondition = rce(r)(condition)

    val mergedExpressionAttributeNames =
        prefixKeys(lCondition.attributeNames, lPrefix, '#') ++
          prefixKeys(rCondition.attributeNames, rPrefix, '#')

    val lValues = lCondition.attributeValues.map(prefixKeys(_, lPrefix, ':'))
    val rValues = rCondition.attributeValues.map(prefixKeys(_, rPrefix, ':'))

    val mergedExpressionAttributeValues = lValues match {
      case Some(m) => Some(m ++ rValues.getOrElse(Map.empty))
      case _ => rValues
    }

    val lConditionExpression =
      prefixKeysIn(
        prefixKeysIn(lCondition.expression, lCondition.attributeNames.keys, lPrefix, '#'),
        lCondition.attributeValues.toList.flatMap(_.keys), lPrefix, ':'
      )
    val rConditionExpression =
      prefixKeysIn(
        prefixKeysIn(rCondition.expression, rCondition.attributeNames.keys, rPrefix, '#'),
        rCondition.attributeValues.toList.flatMap(_.keys), rPrefix, ':'
      )
    RequestCondition(
      s"($lConditionExpression $combininingOperator $rConditionExpression)",
      mergedExpressionAttributeNames,
      mergedExpressionAttributeValues)
  }
}

case class AndCondition[L: ConditionExpression, R: ConditionExpression](l: L, r: R)

case class OrCondition[L: ConditionExpression, R: ConditionExpression](l: L, r: R)

case class Condition[T: ConditionExpression](t: T) {
  def and[Y: ConditionExpression](other: Y) = AndCondition(t, other)
  def or[Y: ConditionExpression](other: Y) = OrCondition(t, other)
}