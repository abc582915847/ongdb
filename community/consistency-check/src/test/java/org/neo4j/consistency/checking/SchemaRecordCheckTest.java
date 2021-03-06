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
package org.neo4j.consistency.checking;

import org.junit.jupiter.api.Test;

import org.neo4j.consistency.checking.index.IndexAccessors;
import org.neo4j.consistency.report.ConsistencyReport;
import org.neo4j.consistency.store.RecordAccessStub;
import org.neo4j.internal.kernel.api.exceptions.schema.MalformedSchemaRuleException;
import org.neo4j.internal.kernel.api.schema.IndexProviderDescriptor;
import org.neo4j.kernel.impl.store.SchemaStorage;
import org.neo4j.kernel.impl.store.record.ConstraintRule;
import org.neo4j.kernel.impl.store.record.DynamicRecord;
import org.neo4j.kernel.impl.store.record.LabelTokenRecord;
import org.neo4j.kernel.impl.store.record.PropertyKeyTokenRecord;
import org.neo4j.storageengine.api.schema.SchemaRule;
import org.neo4j.storageengine.api.schema.StoreIndexDescriptor;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;
import static org.neo4j.consistency.checking.SchemaRuleUtil.constraintIndexRule;
import static org.neo4j.consistency.checking.SchemaRuleUtil.indexRule;
import static org.neo4j.consistency.checking.SchemaRuleUtil.uniquenessConstraintRule;

class SchemaRecordCheckTest
        extends RecordCheckTestBase<DynamicRecord, ConsistencyReport.SchemaConsistencyReport, SchemaRecordCheck>
{
    private final int labelId = 1;
    private final int propertyKeyId = 2;

    SchemaRecordCheckTest()
    {
        super( new SchemaRecordCheck( configureSchemaStore(), configureIndexAccessors() ),
                ConsistencyReport.SchemaConsistencyReport.class, new int[0] );
    }

    @Test
    void shouldReportMalformedSchemaRule() throws Exception
    {
        // given
        DynamicRecord badRecord = inUse( new DynamicRecord( 0 ) );
        badRecord.setType( RecordAccessStub.SCHEMA_RECORD_TYPE );

        when( checker().ruleAccess.loadSingleSchemaRule( 0 ) ).thenThrow( new MalformedSchemaRuleException( "Bad Record" ) );

        // when
        ConsistencyReport.SchemaConsistencyReport report = check( badRecord );

        // then
        verify( report ).malformedSchemaRule();
    }

    @Test
    void shouldReportInvalidLabelReferences() throws Exception
    {
        // given
        int schemaRuleId = 0;

        DynamicRecord record = inUse( dynamicRecord( schemaRuleId ) );
        IndexProviderDescriptor providerDescriptor = new IndexProviderDescriptor( "in-memory", "1.0" );
        StoreIndexDescriptor rule = indexRule( schemaRuleId, labelId, propertyKeyId, providerDescriptor );
        when( checker().ruleAccess.loadSingleSchemaRule( schemaRuleId ) ).thenReturn( rule );

        LabelTokenRecord labelTokenRecord = add( notInUse( new LabelTokenRecord( labelId ) ) );
        add(inUse( new PropertyKeyTokenRecord( propertyKeyId ) ) );

        // when
        ConsistencyReport.SchemaConsistencyReport report = check( record );

        // then
        verify( report ).labelNotInUse( labelTokenRecord );
    }

    @Test
    void shouldReportInvalidPropertyReferenceFromIndexRule() throws Exception
    {
        // given
        int schemaRuleId = 0;

        DynamicRecord record = inUse( dynamicRecord( schemaRuleId ) );
        IndexProviderDescriptor providerDescriptor = new IndexProviderDescriptor( "in-memory", "1.0" );
        StoreIndexDescriptor rule = indexRule( schemaRuleId, labelId, propertyKeyId, providerDescriptor );
        when( checker().ruleAccess.loadSingleSchemaRule( schemaRuleId ) ).thenReturn( rule );

        add( inUse( new LabelTokenRecord( labelId ) ) );
        PropertyKeyTokenRecord propertyKeyToken = add( notInUse( new PropertyKeyTokenRecord( propertyKeyId ) ) );

        // when
        ConsistencyReport.SchemaConsistencyReport report = check( record );

        // then
        verify( report ).propertyKeyNotInUse( propertyKeyToken );
    }

    @Test
    void shouldReportInvalidPropertyReferenceFromUniquenessConstraintRule() throws Exception
    {
        // given
        int schemaRuleId = 0;
        int indexRuleId = 1;

        DynamicRecord record = inUse( dynamicRecord( schemaRuleId ) );

        ConstraintRule rule = uniquenessConstraintRule( schemaRuleId, labelId, propertyKeyId, indexRuleId );

        when( checker().ruleAccess.loadSingleSchemaRule( schemaRuleId ) ).thenReturn( rule );

        add( inUse( new LabelTokenRecord( labelId ) ) );
        PropertyKeyTokenRecord propertyKeyToken = add( notInUse( new PropertyKeyTokenRecord( propertyKeyId ) ) );

        // when
        ConsistencyReport.SchemaConsistencyReport report = check( record );

        // then
        verify( report ).propertyKeyNotInUse( propertyKeyToken );
    }

    @Test
    void shouldReportUniquenessConstraintNotReferencingBack() throws Exception
    {
        // given
        int ruleId1 = 0;
        int ruleId2 = 1;

        DynamicRecord record1 = inUse( dynamicRecord( ruleId1 ) );
        DynamicRecord record2 = inUse( dynamicRecord( ruleId2 ) );

        IndexProviderDescriptor providerDescriptor = new IndexProviderDescriptor( "in-memory", "1.0" );

        StoreIndexDescriptor rule1 = constraintIndexRule( ruleId1, labelId, propertyKeyId, providerDescriptor, (long) ruleId2 );
        ConstraintRule rule2 = uniquenessConstraintRule( ruleId2, labelId, propertyKeyId, ruleId2 );

        when( checker().ruleAccess.loadSingleSchemaRule( ruleId1 ) ).thenReturn( rule1 );
        when( checker().ruleAccess.loadSingleSchemaRule( ruleId2 ) ).thenReturn( rule2 );

        add( inUse( new LabelTokenRecord( labelId ) ) );
        add( inUse( new PropertyKeyTokenRecord( propertyKeyId ) ) );

        // when
        check( record1 );
        check( record2 );
        SchemaRecordCheck obligationChecker = checker().forObligationChecking();
        check( obligationChecker, record1 );
        ConsistencyReport.SchemaConsistencyReport report = check( obligationChecker, record2 );

        // then
        verify( report ).uniquenessConstraintNotReferencingBack( record1 );
    }

    @Test
    void shouldNotReportConstraintIndexRuleWithoutBackReference() throws Exception
    {
        // given
        int ruleId = 1;

        DynamicRecord record = inUse( dynamicRecord( ruleId ) );

        IndexProviderDescriptor providerDescriptor = new IndexProviderDescriptor( "in-memory", "1.0" );

        StoreIndexDescriptor rule = constraintIndexRule( ruleId, labelId, propertyKeyId, providerDescriptor );

        when( checker().ruleAccess.loadSingleSchemaRule( ruleId ) ).thenReturn( rule );

        add( inUse( new LabelTokenRecord( labelId ) ) );
        add( inUse( new PropertyKeyTokenRecord( propertyKeyId ) ) );

        // when
        check( record );
        SchemaRecordCheck obligationChecker = checker().forObligationChecking();
        ConsistencyReport.SchemaConsistencyReport report = check( obligationChecker, record );

        // then
        verifyZeroInteractions( report );
    }

    @Test
    void shouldReportTwoUniquenessConstraintsReferencingSameIndex() throws Exception
    {
        // given
        int ruleId1 = 0;
        int ruleId2 = 1;

        DynamicRecord record1 = inUse( dynamicRecord( ruleId1 ) );
        DynamicRecord record2 = inUse( dynamicRecord( ruleId2 ) );

        ConstraintRule rule1 = uniquenessConstraintRule( ruleId1, labelId, propertyKeyId, ruleId2 );
        ConstraintRule rule2 = uniquenessConstraintRule( ruleId2, labelId, propertyKeyId, ruleId2 );

        when( checker().ruleAccess.loadSingleSchemaRule( ruleId1 ) ).thenReturn( rule1 );
        when( checker().ruleAccess.loadSingleSchemaRule( ruleId2 ) ).thenReturn( rule2 );

        add( inUse( new LabelTokenRecord( labelId ) ) );
        add( inUse( new PropertyKeyTokenRecord( propertyKeyId ) ) );

        // when
        check( record1 );
        ConsistencyReport.SchemaConsistencyReport report = check( record2 );

        // then
        verify( report ).duplicateObligation( record1 );
    }

    @Test
    void shouldReportUnreferencedUniquenessConstraint() throws Exception
    {
        // given
        int ruleId = 0;

        DynamicRecord record = inUse( dynamicRecord( ruleId ) );

        ConstraintRule rule = uniquenessConstraintRule( ruleId, labelId, propertyKeyId, ruleId );

        when( checker().ruleAccess.loadSingleSchemaRule( ruleId ) ).thenReturn( rule );

        add( inUse( new LabelTokenRecord( labelId ) ) );
        add( inUse( new PropertyKeyTokenRecord( propertyKeyId ) ) );

        // when
        check( record );
        SchemaRecordCheck obligationChecker = checker().forObligationChecking();
        ConsistencyReport.SchemaConsistencyReport report = check( obligationChecker, record );

        // then
        verify( report ).missingObligation( SchemaRule.Kind.CONSTRAINT_INDEX_RULE );
    }

    @Test
    void shouldReportConstraintIndexNotReferencingBack() throws Exception
    {
        // given
        int ruleId1 = 0;
        int ruleId2 = 1;

        DynamicRecord record1 = inUse( dynamicRecord( ruleId1 ) );
        DynamicRecord record2 = inUse( dynamicRecord( ruleId2) );

        IndexProviderDescriptor providerDescriptor = new IndexProviderDescriptor( "in-memory", "1.0" );

        StoreIndexDescriptor rule1 = constraintIndexRule( ruleId1, labelId, propertyKeyId, providerDescriptor, (long) ruleId1 );
        ConstraintRule rule2 = uniquenessConstraintRule( ruleId2, labelId, propertyKeyId, ruleId1 );

        when( checker().ruleAccess.loadSingleSchemaRule( ruleId1 ) ).thenReturn( rule1 );
        when( checker().ruleAccess.loadSingleSchemaRule( ruleId2 ) ).thenReturn( rule2 );

        add( inUse( new LabelTokenRecord( labelId ) ) );
        add( inUse( new PropertyKeyTokenRecord( propertyKeyId ) ) );

        // when
        check( record1 );
        check( record2 );
        SchemaRecordCheck obligationChecker = checker().forObligationChecking();
        ConsistencyReport.SchemaConsistencyReport report = check( obligationChecker, record1 );
        check( obligationChecker, record2 );

        // then
        verify( report ).constraintIndexRuleNotReferencingBack( record2 );
    }

    @Test
    void shouldReportTwoConstraintIndexesReferencingSameConstraint() throws Exception
    {
        // given
        int ruleId1 = 0;
        int ruleId2 = 1;

        DynamicRecord record1 = inUse( dynamicRecord( ruleId1 ) );
        DynamicRecord record2 = inUse( dynamicRecord( ruleId2 ) );

        IndexProviderDescriptor providerDescriptor = new IndexProviderDescriptor( "in-memory", "1.0" );

        StoreIndexDescriptor rule1 = constraintIndexRule( ruleId1, labelId, propertyKeyId, providerDescriptor, (long) ruleId1 );
        StoreIndexDescriptor rule2 = constraintIndexRule( ruleId2, labelId, propertyKeyId, providerDescriptor, (long) ruleId1 );

        when( checker().ruleAccess.loadSingleSchemaRule( ruleId1 ) ).thenReturn( rule1 );
        when( checker().ruleAccess.loadSingleSchemaRule( ruleId2 ) ).thenReturn( rule2 );

        add( inUse( new LabelTokenRecord( labelId ) ) );
        add( inUse( new PropertyKeyTokenRecord( propertyKeyId ) ) );

        // when
        check( record1 );
        ConsistencyReport.SchemaConsistencyReport report = check( record2 );

        // then
        verify( report ).duplicateObligation( record1 );
    }

    @Test
    void shouldReportUnreferencedConstraintIndex() throws Exception
    {
        // given
        int ruleId = 0;

        DynamicRecord record = inUse( dynamicRecord( ruleId ) );

        IndexProviderDescriptor providerDescriptor = new IndexProviderDescriptor( "in-memory", "1.0" );

        StoreIndexDescriptor rule = constraintIndexRule( ruleId, labelId, propertyKeyId, providerDescriptor, (long) ruleId );

        when( checker().ruleAccess.loadSingleSchemaRule( ruleId ) ).thenReturn( rule );

        add( inUse( new LabelTokenRecord( labelId ) ) );
        add( inUse( new PropertyKeyTokenRecord( propertyKeyId ) ) );

        // when
        check( record );
        SchemaRecordCheck obligationChecker = checker().forObligationChecking();
        ConsistencyReport.SchemaConsistencyReport report = check( obligationChecker, record );

        // then
        verify( report ).missingObligation( SchemaRule.Kind.UNIQUENESS_CONSTRAINT );
    }

    @Test
    void shouldReportTwoIndexRulesWithDuplicateContent() throws Exception
    {
        // given
        int ruleId1 = 0;
        int ruleId2 = 1;

        DynamicRecord record1 = inUse( dynamicRecord( ruleId1 ) );
        DynamicRecord record2 = inUse( dynamicRecord( ruleId2 ) );

        IndexProviderDescriptor providerDescriptor = new IndexProviderDescriptor( "in-memory", "1.0" );

        StoreIndexDescriptor rule1 = constraintIndexRule( ruleId1, labelId, propertyKeyId, providerDescriptor, (long) ruleId1 );
        StoreIndexDescriptor rule2 = constraintIndexRule( ruleId2, labelId, propertyKeyId, providerDescriptor, (long) ruleId2 );

        when( checker().ruleAccess.loadSingleSchemaRule( ruleId1 ) ).thenReturn( rule1 );
        when( checker().ruleAccess.loadSingleSchemaRule( ruleId2 ) ).thenReturn( rule2 );

        add( inUse( new LabelTokenRecord( labelId ) ) );
        add( inUse( new PropertyKeyTokenRecord( propertyKeyId ) ) );

        // when
        check( record1 );
        ConsistencyReport.SchemaConsistencyReport report = check( record2 );

        // then
        verify( report ).duplicateRuleContent( record1 );
    }

    private static IndexAccessors configureIndexAccessors()
    {
        return mock( IndexAccessors.class );
    }

    private static SchemaStorage configureSchemaStore()
    {
        return mock( SchemaStorage.class );
    }

    private static DynamicRecord dynamicRecord( long id )
    {
        DynamicRecord record = new DynamicRecord( id );
        record.setType( RecordAccessStub.SCHEMA_RECORD_TYPE );
        return record;
    }
}
