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
package org.neo4j.io.pagecache.tracing;

import java.io.IOException;

/**
 * Begin flushing modifications from an in-memory page to the backing file.
 */
public interface FlushEvent
{
    /**
     * A FlushEvent implementation that does nothing.
     */
    FlushEvent NULL = new FlushEvent()
    {
        @Override
        public void addBytesWritten( long bytes )
        {
        }

        @Override
        public void done()
        {
        }

        @Override
        public void done( IOException exception )
        {
        }

        @Override
        public void addPagesFlushed( int pageCount )
        {
        }
    };

    /**
     * Add up a number of bytes that has been written to the file.
     */
    void addBytesWritten( long bytes );

    /**
     * The page flush has completed successfully.
     */
    void done();

    /**
     * The page flush did not complete successfully, but threw the given exception.
     */
    void done( IOException exception );

    void addPagesFlushed( int pageCount );
}
