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
package org.neo4j.cypher.internal.runtime.interpreted.commands.expressions

import org.mockito.Mockito._
import org.neo4j.cypher.internal.runtime.ImplicitValueConversion._
import org.neo4j.cypher.internal.runtime.QueryContext
import org.neo4j.cypher.internal.runtime.interpreted.{ExecutionContext, QueryStateHelper}
import org.neo4j.graphdb.Node
import org.neo4j.values.AnyValues
import org.neo4j.values.storable.Values.stringValue
import org.neo4j.values.virtual.ListValue
import org.neo4j.values.virtual.VirtualValues.{EMPTY_LIST, list}
import org.neo4j.cypher.internal.v3_6.util.test_helpers.CypherFunSuite

class KeysFunctionTest extends CypherFunSuite {

  test("test Property Keys") {
    // GIVEN

    val node = mock[Node]

    val queryContext = mock[QueryContext]

    when(queryContext.nodePropertyIds(node.getId)).thenReturn(Array(11, 12, 13))

    when(queryContext.getPropertyKeyName(11)).thenReturn("theProp1")
    when(queryContext.getPropertyKeyName(12)).thenReturn("OtherProp")
    when(queryContext.getPropertyKeyName(13)).thenReturn("MoreProp")

    val state = QueryStateHelper.emptyWith(query = queryContext)
    val ctx = ExecutionContext() += ("n" -> node)

    // WHEN
    val result = KeysFunction(Variable("n"))(ctx, state)

    // THEN
    result should equal(list(stringValue("theProp1"), stringValue("OtherProp"), stringValue("MoreProp")))
  }

  test("test without Property Keys ") {
    // GIVEN
    val node = mock[Node]
    val queryContext = mock[QueryContext]
    when(queryContext.nodePropertyIds(node.getId)).thenReturn(Array.empty[Int])


    val state = QueryStateHelper.emptyWith(query = queryContext)
    val ctx = ExecutionContext() += ("n" -> node)

    // WHEN
    val result = KeysFunction(Variable("n"))(ctx, state)

    // THEN
    result should equal(EMPTY_LIST)
  }

  test("test using a literal map") {
    // GIVEN
    val queryContext = mock[QueryContext]
    val state = QueryStateHelper.emptyWith(query = queryContext)
    val ctx = ExecutionContext.empty

    val function = KeysFunction(LiteralMap(Map("foo" -> Literal(1), "bar" -> Literal(2), "baz" -> Literal(3))))
    // WHEN
    val result = function(ctx, state).asInstanceOf[ListValue].asArray().sortWith( (a,b) => AnyValues.COMPARATOR.compare(a,b) >= 0)

    result should equal(Array(stringValue("foo"), stringValue("baz"), stringValue("bar")))
  }
}
