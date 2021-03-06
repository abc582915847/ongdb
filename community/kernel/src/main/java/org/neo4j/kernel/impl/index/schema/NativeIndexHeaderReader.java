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
package org.neo4j.kernel.impl.index.schema;

import org.apache.commons.lang3.exception.ExceptionUtils;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import org.neo4j.index.internal.gbptree.Header;

import static java.lang.String.format;
import static org.neo4j.kernel.impl.index.schema.NativeIndexPopulator.BYTE_FAILED;

class NativeIndexHeaderReader implements Header.Reader
{
    private final Header.Reader additionalReader;
    byte state;
    String failureMessage;

    NativeIndexHeaderReader( Header.Reader additionalReader )
    {
        this.additionalReader = additionalReader;
    }

    @Override
    public void read( ByteBuffer headerData )
    {
        try
        {
            state = headerData.get();
            if ( state == BYTE_FAILED )
            {
                failureMessage = readFailureMessage( headerData );
            }
            else
            {
                additionalReader.read( headerData );
            }
        }
        catch ( BufferUnderflowException e )
        {
            state = BYTE_FAILED;
            failureMessage =
                    format( "Could not read header, most likely caused by index not being fully constructed. Index needs to be recreated. Stacktrace:%n%s",
                            ExceptionUtils.getStackTrace( e ) );
        }
    }

    /**
     * Alternative header readers should react to FAILED indexes by using this, because their specific headers will have been
     * overwritten by the FailedHeaderWriter.
     */
    static String readFailureMessage( ByteBuffer headerData )
    {
        short messageLength = headerData.getShort();
        byte[] failureMessageBytes = new byte[messageLength];
        headerData.get( failureMessageBytes );
        return new String( failureMessageBytes, StandardCharsets.UTF_8 );
    }
}
