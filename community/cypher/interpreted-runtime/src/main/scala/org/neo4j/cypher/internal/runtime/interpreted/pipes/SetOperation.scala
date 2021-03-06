/*
 * Copyright (c) 2018-2020 "Graph Foundation"
 * Graph Foundation, Inc. [https://graphfoundation.org]
 *
 * Copyright (c) 2002-2020 "Neo4j,"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of ONgDB.
 *
 * ONgDB is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.cypher.internal.runtime.interpreted.pipes

import org.neo4j.cypher.internal.runtime.interpreted._
import org.neo4j.cypher.internal.runtime.interpreted.commands.expressions.Expression
import org.neo4j.cypher.internal.runtime.{Operations, QueryContext}
import org.neo4j.cypher.internal.v3_6.util.{CypherTypeException, InvalidArgumentException}
import org.neo4j.function.ThrowingBiConsumer
import org.neo4j.values.AnyValue
import org.neo4j.values.storable.Values
import org.neo4j.values.virtual._

import scala.collection.Map
import scala.collection.mutable.ArrayBuffer

sealed trait SetOperation {

  def set(executionContext: ExecutionContext, state: QueryState): Unit

  def name: String

  def needsExclusiveLock: Boolean

  def registerOwningPipe(pipe: Pipe): Unit
}

object SetOperation {

  private[pipes] def toMap(executionContext: ExecutionContext, state: QueryState, expression: Expression) = {
    /* Make the map expression look like a map */
    val qtx = state.query
    expression(executionContext, state) match {
      case IsMap(map) => propertyKeyMap(qtx, map(qtx))
      case x => throw new CypherTypeException(s"Expected $expression to be a map, but it was :`$x`")
    }
  }

  private def propertyKeyMap(qtx: QueryContext, map: MapValue): Map[Int, AnyValue] = {
    val builder = Map.newBuilder[Int, AnyValue]
    val setKeys = new ArrayBuffer[String]()
    val setValues = new ArrayBuffer[AnyValue]()

    map.foreach(new ThrowingBiConsumer[String, AnyValue, RuntimeException] {
      override def accept(k: String, v: AnyValue): Unit = {
        if (v == Values.NO_VALUE) {
          val optPropertyKeyId = qtx.getOptPropertyKeyId(k)
          if (optPropertyKeyId.isDefined) {
            builder += optPropertyKeyId.get -> v
          }
        }
        else {
          setKeys += k
          setValues += v
        }
      }
    })

    // Adding property keys is O(|totalPropertyKeyIds|^2) per call, so
    // batch creation is way faster is graphs with many propertyKeyIds
    val propertyIds = qtx.getOrCreatePropertyKeyIds(setKeys.toArray)
    for (i <- propertyIds.indices)
      builder += (propertyIds(i) -> setValues(i))

    builder.result()
  }
}

abstract class AbstractSetPropertyOperation extends SetOperation {

  protected def setProperty[T](context: ExecutionContext, state: QueryState, ops: Operations[T],
                                                    itemId: Long, propertyKey: LazyPropertyKey,
                                                    expression: Expression) = {
    val queryContext = state.query
    val maybePropertyKey = propertyKey.id(queryContext).map(_.id) // if the key was already looked up
    val propertyId = maybePropertyKey
      .getOrElse(queryContext.getOrCreatePropertyKeyId(propertyKey.name)) // otherwise create it

    val value = makeValueNeoSafe(expression(context, state))

    if (value == Values.NO_VALUE) {
      if (ops.hasProperty(itemId, propertyId)) ops.removeProperty(itemId, propertyId)
    }
    else ops.setProperty(itemId, propertyId, value)
  }
}

abstract class SetEntityPropertyOperation[T](itemName: String, propertyKey: LazyPropertyKey,
                                                                  expression: Expression)
  extends AbstractSetPropertyOperation {

  override def set(executionContext: ExecutionContext, state: QueryState) = {
    val item = executionContext.get(itemName).get
    if (item != Values.NO_VALUE) {
      val itemId = id(item)
      val ops = operations(state.query)
      if (needsExclusiveLock) ops.acquireExclusiveLock(itemId)

      invalidateCachedProperties(executionContext, itemId)

      try {
        setProperty[T](executionContext, state, ops, itemId, propertyKey, expression)
      } finally if (needsExclusiveLock) ops.releaseExclusiveLock(itemId)
    }
  }

  protected def id(item: Any): Long

  protected def operations(qtx: QueryContext): Operations[T]

  protected def invalidateCachedProperties(executionContext: ExecutionContext, id: Long): Unit

  override def registerOwningPipe(pipe: Pipe): Unit = expression.registerOwningPipe(pipe)
}

case class SetNodePropertyOperation(nodeName: String, propertyKey: LazyPropertyKey,
                                    expression: Expression, needsExclusiveLock: Boolean = true)
  extends SetEntityPropertyOperation[NodeValue](nodeName, propertyKey, expression) {

  override def name = "SetNodeProperty"

  override protected def id(item: Any) = CastSupport.castOrFail[VirtualNodeValue](item).id()

  override protected def operations(qtx: QueryContext) = qtx.nodeOps

  override protected def invalidateCachedProperties(executionContext: ExecutionContext, id: Long): Unit =
    executionContext.invalidateCachedProperties(id)
}

case class SetRelationshipPropertyOperation(relName: String, propertyKey: LazyPropertyKey,
                                            expression: Expression, needsExclusiveLock: Boolean = true)
  extends SetEntityPropertyOperation[RelationshipValue](relName, propertyKey, expression) {

  override def name = "SetRelationshipProperty"

  override protected def id(item: Any) = CastSupport.castOrFail[VirtualRelationshipValue](item).id()

  override protected def operations(qtx: QueryContext) = qtx.relationshipOps

  override protected def invalidateCachedProperties(executionContext: ExecutionContext, id: Long): Unit = {} // we do not cache relationships
}

case class SetPropertyOperation(entityExpr: Expression, propertyKey: LazyPropertyKey, expression: Expression)
  extends AbstractSetPropertyOperation {

  override def name: String = "SetProperty"

  override def set(executionContext: ExecutionContext, state: QueryState) = {
    val resolvedEntity = entityExpr(executionContext, state)
    if (resolvedEntity != Values.NO_VALUE) {
      val (entityId, ops, invalidation) = resolvedEntity match {
        case node: VirtualNodeValue => (node.id(), state.query.nodeOps, (id:Long) => executionContext.invalidateCachedProperties(id))
        case rel: VirtualRelationshipValue => (rel.id(), state.query.relationshipOps, (_:Long) => {})
        case _ => throw new InvalidArgumentException(
          s"The expression $entityExpr should have been a node or a relationship, but got $resolvedEntity")
      }
      // better safe than sorry let's lock the entity
      ops.acquireExclusiveLock(entityId)

      invalidation(entityId)

      try {
        setProperty(executionContext, state, ops, entityId, propertyKey, expression)
      } finally ops.releaseExclusiveLock(entityId)
    }
  }

  override def needsExclusiveLock = true

  override def registerOwningPipe(pipe: Pipe): Unit = {
    entityExpr.registerOwningPipe(pipe)
    expression.registerOwningPipe(pipe)
  }
}

abstract class AbstractSetPropertyFromMapOperation(expression: Expression) extends SetOperation {

  protected def setPropertiesFromMap[T](qtx: QueryContext, ops: Operations[T], itemId: Long,
                                     map: Map[Int, AnyValue], removeOtherProps: Boolean) {

    /*Set all map values on the property container*/
    for ((k, v) <- map) {
      if (v == Values.NO_VALUE)
        ops.removeProperty(itemId, k)
      else
        ops.setProperty(itemId, k, makeValueNeoSafe(v))
    }

    val properties = ops.propertyKeyIds(itemId).filterNot(map.contains).toSet

    /*Remove all other properties from the property container ( SET n = {prop1: ...})*/
    if (removeOtherProps) {
      for (propertyKeyId <- properties) {
        ops.removeProperty(itemId, propertyKeyId)
      }
    }
  }
  override def registerOwningPipe(pipe: Pipe): Unit = expression.registerOwningPipe(pipe)
}

abstract class SetNodeOrRelPropertyFromMapOperation[T](itemName: String,
                                                       expression: Expression,
                                                       removeOtherProps: Boolean) extends AbstractSetPropertyFromMapOperation(expression) {
  override def set(executionContext: ExecutionContext, state: QueryState) = {
    val item = executionContext(itemName)
    if (item != Values.NO_VALUE) {
      val ops = operations(state.query)
      val itemId = id(item)
      if (needsExclusiveLock) ops.acquireExclusiveLock(itemId)

      invalidateCachedProperties(executionContext, itemId)

      try {
        val map = SetOperation.toMap(executionContext, state, expression)

        setPropertiesFromMap(state.query, ops, itemId, map, removeOtherProps)
      } finally if (needsExclusiveLock) ops.releaseExclusiveLock(itemId)
    }
  }

  protected def id(item: Any): Long

  protected def operations(qtx: QueryContext): Operations[T]

  protected def invalidateCachedProperties(executionContext: ExecutionContext, id: Long): Unit
}

case class SetNodePropertyFromMapOperation(nodeName: String, expression: Expression,
                                           removeOtherProps: Boolean, needsExclusiveLock: Boolean = true)
  extends SetNodeOrRelPropertyFromMapOperation[NodeValue](nodeName, expression, removeOtherProps) {

  override def name = "SetNodePropertyFromMap"

  override protected def id(item: Any) = CastSupport.castOrFail[VirtualNodeValue](item).id()

  override protected def operations(qtx: QueryContext) = qtx.nodeOps

  override protected def invalidateCachedProperties(executionContext: ExecutionContext, id: Long): Unit =
    executionContext.invalidateCachedProperties(id)
}

case class SetRelationshipPropertyFromMapOperation(relName: String, expression: Expression,
                                                   removeOtherProps: Boolean, needsExclusiveLock: Boolean = true)
  extends SetNodeOrRelPropertyFromMapOperation[RelationshipValue](relName, expression, removeOtherProps) {

  override def name = "SetRelationshipPropertyFromMap"

  override protected def id(item: Any) = CastSupport.castOrFail[VirtualRelationshipValue](item).id()

  override protected def operations(qtx: QueryContext) = qtx.relationshipOps

  override protected def invalidateCachedProperties(executionContext: ExecutionContext, id: Long): Unit = {} // we do not cache relationships
}

case class SetPropertyFromMapOperation(entityExpr: Expression,
                                       expression: Expression,
                                       removeOtherProps: Boolean)
  extends AbstractSetPropertyFromMapOperation(expression) {

  override def name = "SetPropertyFromMap"

  override def set(executionContext: ExecutionContext, state: QueryState) = {
    val resolvedEntity = entityExpr(executionContext, state)
    if (resolvedEntity != Values.NO_VALUE) {
      val (entityId, ops, invalidation) = resolvedEntity match {
        case node: VirtualNodeValue => (node.id(), state.query.nodeOps, (id:Long) => executionContext.invalidateCachedProperties(id))
        case rel: VirtualRelationshipValue => (rel.id(), state.query.relationshipOps, (_:Long) => {})
        case _ => throw new InvalidArgumentException(
          s"The expression $entityExpr should have been a node or a relationship, but got $resolvedEntity")
      }
      ops.acquireExclusiveLock(entityId)

      invalidation(entityId)

      try {
        val map = SetOperation.toMap(executionContext, state, expression)

        setPropertiesFromMap(state.query, ops, entityId, map, removeOtherProps)
      } finally ops.releaseExclusiveLock(entityId)
    }
  }

  override def needsExclusiveLock = true

  override def registerOwningPipe(pipe: Pipe): Unit = {
    entityExpr.registerOwningPipe(pipe)
    expression.registerOwningPipe(pipe)
  }
}

case class SetLabelsOperation(nodeName: String, labels: Seq[LazyLabel]) extends SetOperation {

  override def set(executionContext: ExecutionContext, state: QueryState) = {
    val value: AnyValue = executionContext.get(nodeName).get
    if (value != Values.NO_VALUE) {
      val nodeId = CastSupport.castOrFail[VirtualNodeValue](value).id()
      val labelIds = labels.map(_.getOrCreateId(state.query).id)
      state.query.setLabelsOnNode(nodeId, labelIds.iterator)
    }
  }

  override def name = "SetLabels"

  override def needsExclusiveLock = false

  override def registerOwningPipe(pipe: Pipe): Unit = ()
}
