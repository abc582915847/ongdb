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
package org.neo4j.kernel.impl.transaction.log;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.neo4j.kernel.impl.transaction.CommittedTransactionRepresentation;
import org.neo4j.kernel.impl.transaction.log.entry.CheckPoint;
import org.neo4j.kernel.impl.transaction.log.entry.LogEntry;
import org.neo4j.kernel.impl.transaction.log.entry.LogEntryCommand;
import org.neo4j.kernel.impl.transaction.log.entry.LogEntryCommit;
import org.neo4j.kernel.impl.transaction.log.entry.LogEntryReader;
import org.neo4j.kernel.impl.transaction.log.entry.LogEntryStart;
import org.neo4j.storageengine.api.StorageCommand;

public class PhysicalTransactionCursor<T extends ReadableClosablePositionAwareChannel>
        implements TransactionCursor
{
    private final T channel;
    private final LogEntryCursor logEntryCursor;
    private final LogPositionMarker lastGoodPositionMarker = new LogPositionMarker();

    private CommittedTransactionRepresentation current;

    public PhysicalTransactionCursor( T channel, LogEntryReader<T> entryReader ) throws IOException
    {
        this.channel = channel;
        channel.getCurrentPosition( lastGoodPositionMarker );
        this.logEntryCursor =
                new LogEntryCursor( (LogEntryReader<ReadableClosablePositionAwareChannel>) entryReader, channel );
    }

    @Override
    public CommittedTransactionRepresentation get()
    {
        return current;
    }

    @Override
    public boolean next() throws IOException
    {
        // Clear the previous deserialized transaction so that it won't have to be kept in heap while deserializing
        // the next one. Could be problematic if both are really big.
        current = null;

        while ( true )
        {
            if ( !logEntryCursor.next() )
            {
                return false;
            }

            LogEntry entry = logEntryCursor.get();
            if ( entry instanceof CheckPoint )
            {
                // this is a good position anyhow
                channel.getCurrentPosition( lastGoodPositionMarker );
                continue;
            }

            assert entry instanceof LogEntryStart : "Expected Start entry, read " + entry + " instead";
            LogEntryStart startEntry = entry.as();
            LogEntryCommit commitEntry;

            List<StorageCommand> entries = new ArrayList<>();
            while ( true )
            {
                if ( !logEntryCursor.next() )
                {
                    return false;
                }

                entry = logEntryCursor.get();
                if ( entry instanceof LogEntryCommit )
                {
                    commitEntry = entry.as();
                    break;
                }

                LogEntryCommand command = entry.as();
                entries.add( command.getCommand() );
            }

            PhysicalTransactionRepresentation transaction = new PhysicalTransactionRepresentation( entries );
            transaction.setHeader( startEntry.getAdditionalHeader(), startEntry.getMasterId(),
                    startEntry.getLocalId(), startEntry.getTimeWritten(),
                    startEntry.getLastCommittedTxWhenTransactionStarted(), commitEntry.getTimeWritten(), -1 );
            current = new CommittedTransactionRepresentation( startEntry, transaction, commitEntry );
            channel.getCurrentPosition( lastGoodPositionMarker );
            return true;
        }
    }

    @Override
    public void close() throws IOException
    {
        logEntryCursor.close();
    }

    /**
     * @return last known good position, which is a {@link LogPosition} after a {@link CheckPoint} or
     * a {@link LogEntryCommit}.
     */
    @Override
    public LogPosition position()
    {
        return lastGoodPositionMarker.newPosition();
    }
}
