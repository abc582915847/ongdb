/*
 * Copyright (c) 2002-2018 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
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
package org.neo4j.kernel.api.index;

import org.junit.Ignore;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.neo4j.collection.primitive.PrimitiveLongCollections;
import org.neo4j.collection.primitive.PrimitiveLongIterator;
import org.neo4j.internal.kernel.api.IndexOrder;
import org.neo4j.internal.kernel.api.IndexQuery;
import org.neo4j.internal.kernel.api.schema.LabelSchemaDescriptor;
import org.neo4j.kernel.api.exceptions.index.IndexEntryConflictException;
import org.neo4j.kernel.api.exceptions.index.IndexNotApplicableKernelException;
import org.neo4j.kernel.api.schema.index.IndexDescriptor;
import org.neo4j.kernel.api.schema.index.IndexDescriptorFactory;
import org.neo4j.kernel.configuration.Config;
import org.neo4j.kernel.impl.api.index.sampling.IndexSamplingConfig;
import org.neo4j.kernel.impl.index.schema.NodeValueIterator;
import org.neo4j.storageengine.api.schema.IndexReader;
import org.neo4j.values.storable.Value;
import org.neo4j.values.storable.ValueTuple;
import org.neo4j.values.storable.Values;

import static java.util.Collections.singletonList;
import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;
import static org.neo4j.collection.primitive.PrimitiveLongCollections.single;
import static org.neo4j.helpers.collection.Iterators.asSet;
import static org.neo4j.internal.kernel.api.InternalIndexState.FAILED;
import static org.neo4j.kernel.api.StatementConstants.NO_SUCH_NODE;
import static org.neo4j.kernel.api.index.IndexEntryUpdate.add;

@Ignore( "Not a test. This is a compatibility suite that provides test cases for verifying" +
        " SchemaIndexProvider implementations. Each index provider that is to be tested by this suite" +
        " must create their own test class extending IndexProviderCompatibilityTestSuite." +
        " The @Ignore annotation doesn't prevent these tests to run, it rather removes some annoying" +
        " errors or warnings in some IDEs about test classes needing a public zero-arg constructor." )
public class SimpleIndexPopulatorCompatibility extends IndexProviderCompatibilityTestSuite.Compatibility
{
    public SimpleIndexPopulatorCompatibility(
            IndexProviderCompatibilityTestSuite testSuite, IndexDescriptor descriptor )
    {
        super( testSuite, descriptor );
    }

    private final IndexSamplingConfig indexSamplingConfig = new IndexSamplingConfig( Config.defaults() );

    @Test
    public void shouldStorePopulationFailedForRetrievalFromProviderLater() throws Exception
    {
        // GIVEN
        IndexSamplingConfig indexSamplingConfig = new IndexSamplingConfig( Config.defaults() );
        withPopulator( indexProvider.getPopulator( 17, descriptor, indexSamplingConfig ), p ->
        {
            String failure = "The contrived failure";
            p.create();

            // WHEN
            p.markAsFailed( failure );
            p.close( false );

            // THEN
            assertThat( indexProvider.getPopulationFailure( 17, descriptor ), containsString( failure ) );
        } );
    }

    @Test
    public void shouldReportInitialStateAsFailedIfPopulationFailed() throws Exception
    {
        // GIVEN
        IndexSamplingConfig indexSamplingConfig = new IndexSamplingConfig( Config.defaults() );
        withPopulator( indexProvider.getPopulator( 17, descriptor, indexSamplingConfig ), p ->
        {
            String failure = "The contrived failure";
            p.create();

            // WHEN
            p.markAsFailed( failure );

            // THEN
            assertEquals( FAILED, indexProvider.getInitialState( 17, descriptor ) );
        } );
    }

    @Test
    public void shouldBeAbleToDropAClosedIndexPopulator() throws Exception
    {
        // GIVEN
        IndexSamplingConfig indexSamplingConfig = new IndexSamplingConfig( Config.defaults() );
        withPopulator( indexProvider.getPopulator( 17, descriptor, indexSamplingConfig ), p ->
        {
            p.close( false );

            // WHEN
            p.drop();

            // THEN - no exception should be thrown (it's been known to!)
        } );
    }

    @Test
    public void shouldApplyUpdatesIdempotently() throws Exception
    {
        // GIVEN
        IndexSamplingConfig indexSamplingConfig = new IndexSamplingConfig( Config.defaults() );
        final Value propertyValue = Values.of( "value1" );
        withPopulator( indexProvider.getPopulator( 17, descriptor, indexSamplingConfig ), p ->
        {
            p.create();
            long nodeId = 1;

            // update using populator...
            IndexEntryUpdate<LabelSchemaDescriptor> update = add( nodeId, descriptor.schema(), propertyValue );
            p.add( singletonList( update ) );
            // ...is the same as update using updater
            try ( IndexUpdater updater = p.newPopulatingUpdater( ( node, propertyId ) -> propertyValue ) )
            {
                updater.process( update );
            }

            p.close( true );
        } );

        // then
        try ( IndexAccessor accessor = indexProvider.getOnlineAccessor( 17, descriptor, indexSamplingConfig ) )
        {
            try ( IndexReader reader = accessor.newReader() )
            {
                int propertyKeyId = descriptor.schema().getPropertyId();
                PrimitiveLongIterator nodes = reader.query( IndexQuery.exact( propertyKeyId, propertyValue ) );
                assertEquals( asSet( 1L ), PrimitiveLongCollections.toSet( nodes ) );
            }
            accessor.close();
        }
    }

    private List<NodeAndValue> allValues( Iterable<Value> supportedValues )
    {
        long nodeIds = 0;
        List<NodeAndValue> result = new ArrayList<>();
        for ( Value value : supportedValues )
        {
            result.add( new NodeAndValue( nodeIds++, value ) );
        }
        return result;
    }

    private List<NodeAndValue> allValues = allValues( testSuite.getSupportedValues() );

    static class NodeAndValue
    {
        final long nodeId;
        final Value value;

        NodeAndValue( long nodeId, Value value )
        {
            this.nodeId = nodeId;
            this.value = value;
        }
    }

    @Test
    public void shouldPopulateWithAllValues() throws Exception
    {
        // GIVEN
        withPopulator( indexProvider.getPopulator( 17, descriptor, indexSamplingConfig ), p ->
        {
            p.create();

            List<IndexEntryUpdate<LabelSchemaDescriptor>> updates = new ArrayList<>();
            allValues.forEach( entry -> updates.add( IndexEntryUpdate.add( entry.nodeId, descriptor.schema(), entry.value ) ) );

            p.add( updates );

            p.close( true );
        } );

        // then
        assertHasAllValues();
    }

    @Test
    public void shouldUpdateWithAllValuesDuringPopulation() throws Exception
    {
        // GIVEN
        withPopulator( indexProvider.getPopulator( 17, descriptor, indexSamplingConfig ), p ->
        {
            p.create();

            try ( IndexUpdater updater = p.newPopulatingUpdater( this::allValueLookup ) )
            {
                for ( NodeAndValue entry : allValues )
                {
                    updater.process( IndexEntryUpdate.add( entry.nodeId, descriptor.schema(), entry.value ) );
                }
            }

            p.close( true );
        } );

        // then
        assertHasAllValues();
    }

    private Value allValueLookup( long nodeId, int propertyId )
    {
        for ( NodeAndValue x : allValues )
        {
            if ( x.nodeId == nodeId )
            {
                return x.value;
            }
        }
        return Values.NO_VALUE;
    }

    private void assertHasAllValues() throws IOException, IndexNotApplicableKernelException
    {
        try ( IndexAccessor accessor = indexProvider.getOnlineAccessor( 17, descriptor, indexSamplingConfig ) )
        {
            try ( IndexReader reader = accessor.newReader() )
            {
                int propertyKeyId = descriptor.schema().getPropertyId();
                for ( NodeAndValue entry : allValues )
                {
                    NodeValueIterator nodes = new NodeValueIterator();
                    reader.query( nodes, IndexOrder.NONE, IndexQuery.exact( propertyKeyId, entry.value ) );
                    assertEquals( entry.nodeId, single( nodes, NO_SUCH_NODE ) );
                }
            }
            accessor.close();
        }
    }

    @Ignore( "Not a test. This is a compatibility suite" )
    public static class General extends SimpleIndexPopulatorCompatibility
    {
        public General( IndexProviderCompatibilityTestSuite testSuite )
        {
            super( testSuite, IndexDescriptorFactory.forLabel( 1000, 100 ) );
        }

        @Test
        public void shouldProvidePopulatorThatAcceptsDuplicateEntries() throws Exception
        {
            // when
            IndexSamplingConfig indexSamplingConfig = new IndexSamplingConfig( Config.defaults() );
            Value value = Values.of( "value1" );
            withPopulator( indexProvider.getPopulator( 17, descriptor, indexSamplingConfig ), p ->
            {
                p.create();
                p.add( Arrays.asList(
                        IndexEntryUpdate.add( 1, descriptor.schema(), value ),
                        IndexEntryUpdate.add( 2, descriptor.schema(), value ) ) );
                p.close( true );
            } );

            // then
            try ( IndexAccessor accessor = indexProvider.getOnlineAccessor( 17, descriptor, indexSamplingConfig ) )
            {
                try ( IndexReader reader = accessor.newReader() )
                {
                    PrimitiveLongIterator nodes = reader.query( IndexQuery.exact( 1, value ) );
                    assertEquals( asSet( 1L, 2L ), PrimitiveLongCollections.toSet( nodes ) );
                }
                accessor.close();
            }
        }
    }

    @Ignore( "Not a test. This is a compatibility suite" )
    public static class Unique extends SimpleIndexPopulatorCompatibility
    {
        public Unique( IndexProviderCompatibilityTestSuite testSuite )
        {
            super( testSuite, IndexDescriptorFactory.uniqueForLabel( 1000, 100 ) );
        }

        /**
         * This is also checked by the UniqueConstraintCompatibility test, only not on this abstraction level.
         */
        @Test
        public void shouldProvidePopulatorThatEnforcesUniqueConstraints() throws Exception
        {
            // when
            Value value = Values.of( "value1" );
            int nodeId1 = 1;
            int nodeId2 = 2;

            IndexSamplingConfig indexSamplingConfig = new IndexSamplingConfig( Config.defaults() );
            withPopulator( indexProvider.getPopulator( 17, descriptor, indexSamplingConfig ), p ->
            {
                p.create();
                p.add( Arrays.asList(
                        IndexEntryUpdate.add( nodeId1, descriptor.schema(), value ),
                        IndexEntryUpdate.add( nodeId2, descriptor.schema(), value ) ) );
                try
                {
                    NodePropertyAccessor propertyAccessor =
                            new NodePropertyAccessor( nodeId1, descriptor.schema(), value );
                    propertyAccessor.addNode( nodeId2, descriptor.schema(), value );
                    p.verifyDeferredConstraints( propertyAccessor );

                    fail( "expected exception" );
                }
                // then
                catch ( IndexEntryConflictException conflict )
                {
                    assertEquals( nodeId1, conflict.getExistingNodeId() );
                    assertEquals( ValueTuple.of( value ), conflict.getPropertyValues() );
                    assertEquals( nodeId2, conflict.getAddedNodeId() );
                }
            } );
        }
    }
}