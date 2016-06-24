package com.gu.scanamo

import com.amazonaws.services.dynamodbv2.model.{PutRequest, WriteRequest, _}
import cats.data.Xor
import com.gu.scanamo.DynamoResultStream.{QueryResultStream, ScanResultStream}
import com.gu.scanamo.error.DynamoReadError
import com.gu.scanamo.ops.ScanamoOps
import com.gu.scanamo.query._
import com.gu.scanamo.request.{ScanamoDeleteRequest, ScanamoPutRequest, ScanamoUpdateRequest}
import com.gu.scanamo.update.UpdateExpression

object ScanamoFree {

  import cats.std.list._
  import cats.syntax.traverse._
  import collection.convert.decorateAsJava._

  def given[T: ConditionExpression](tableName: String)(condition: T): ConditionalOperation[T] =
    ConditionalOperation(tableName, condition)

  def put[T](tableName: String)(item: T)(implicit f: DynamoFormat[T]): ScanamoOps[PutItemResult] =
    ScanamoOps.put(ScanamoPutRequest(tableName, f.write(item), None))

  def putAll[T](tableName: String)(items: List[T])(implicit f: DynamoFormat[T]): ScanamoOps[List[BatchWriteItemResult]] =
    items.grouped(25).toList.traverseU(batch =>
      ScanamoOps.batchWrite(
        new BatchWriteItemRequest().withRequestItems(Map(tableName -> batch.map(i =>
          new WriteRequest().withPutRequest(new PutRequest().withItem(f.write(i).getM))
        ).asJava).asJava)
      )
    )

  def get[T](tableName: String)(key: UniqueKey[_])
    (implicit ft: DynamoFormat[T]): ScanamoOps[Option[Xor[DynamoReadError, T]]] =
    for {
      res <- ScanamoOps.get(new GetItemRequest().withTableName(tableName).withKey(key.asAVMap.asJava))
    } yield
      Option(res.getItem).map(read[T])

  def getAll[T: DynamoFormat](tableName: String)(keys: UniqueKeys[_]): ScanamoOps[List[Xor[DynamoReadError, T]]] = {
    import collection.convert.decorateAsScala._
    for {
      res <- ScanamoOps.batchGet(
        new BatchGetItemRequest().withRequestItems(Map(tableName ->
          new KeysAndAttributes().withKeys(keys.asAVMap.map(_.asJava).asJava)
        ).asJava)
      )
    } yield
      keys.sortByKeys(res.getResponses.get(tableName).asScala.toList).map(read[T])
  }

  def delete(tableName: String)(key: UniqueKey[_]): ScanamoOps[DeleteItemResult] =
    ScanamoOps.delete(ScanamoDeleteRequest(tableName, key.asAVMap, None))

  def scan[T: DynamoFormat](tableName: String): ScanamoOps[List[Xor[DynamoReadError, T]]] =
    ScanResultStream.stream[T](new ScanRequest().withTableName(tableName))

  def scanWithLimit[T: DynamoFormat](tableName: String, limit: Int): ScanamoOps[List[Xor[DynamoReadError, T]]] =
    ScanResultStream.stream[T](new ScanRequest().withTableName(tableName).withLimit(limit))

  def scanIndex[T: DynamoFormat](tableName: String, indexName: String): ScanamoOps[List[Xor[DynamoReadError, T]]] =
    ScanResultStream.stream[T](new ScanRequest().withTableName(tableName).withIndexName(indexName))

  def scanIndexWithLimit[T: DynamoFormat](tableName: String, indexName: String, limit: Int): ScanamoOps[List[Xor[DynamoReadError, T]]] =
    ScanResultStream.stream[T](new ScanRequest().withTableName(tableName).withIndexName(indexName).withLimit(limit))

  def query[T: DynamoFormat](tableName: String)(query: Query[_]): ScanamoOps[List[Xor[DynamoReadError, T]]] =
    QueryResultStream.stream[T](query(new QueryRequest().withTableName(tableName)))

  def queryWithLimit[T: DynamoFormat](tableName: String)(query: Query[_], limit: Int): ScanamoOps[List[Xor[DynamoReadError, T]]] =
    QueryResultStream.stream[T](query(new QueryRequest().withTableName(tableName)).withLimit(limit))

  def queryIndex[T: DynamoFormat](tableName: String, indexName: String)(query: Query[_]): ScanamoOps[List[Xor[DynamoReadError, T]]] =
    QueryResultStream.stream[T](query(new QueryRequest().withTableName(tableName)).withIndexName(indexName))

  def queryIndexWithLimit[T: DynamoFormat](tableName: String, indexName: String)(query: Query[_], limit: Int): ScanamoOps[List[Xor[DynamoReadError, T]]] =
    QueryResultStream.stream[T](query(new QueryRequest().withTableName(tableName)).withIndexName(indexName).withLimit(limit))

  def update[T](tableName: String)(key: UniqueKey[_])(expression: T)(implicit update: UpdateExpression[T]) =
    ScanamoOps.update(ScanamoUpdateRequest(
      tableName, key.asAVMap, update.expression(expression), update.attributeNames(expression), update.attributeValues(expression), None))

  /**
    * {{{
    * prop> import collection.convert.decorateAsJava._
    * prop> import com.amazonaws.services.dynamodbv2.model._
    *
    * prop> (m: Map[String, Int]) =>
    *     |   ScanamoFree.read[Map[String, Int]](
    *     |     m.mapValues(i => new AttributeValue().withN(i.toString)).asJava
    *     |   ) == cats.data.Xor.right(m)
    * }}}
    */
  def read[T](m: java.util.Map[String, AttributeValue])(implicit f: DynamoFormat[T]): Xor[DynamoReadError, T] =
    f.read(new AttributeValue().withM(m))
}
