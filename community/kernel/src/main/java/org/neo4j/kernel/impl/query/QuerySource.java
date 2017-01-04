/*
 * Copyright (c) 2002-2017 "Neo Technology,"
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
package org.neo4j.kernel.impl.query;

import java.io.Serializable;
import java.net.InetSocketAddress;
import java.net.SocketAddress;

public abstract class QuerySource
{
    public abstract String asConnectionDetails();

    public QuerySource withUsername( String username )
    {
        return new SessionWithUsername( this, username );
    }

    public static final QuerySource EMBEDDED_SESSION = new QuerySource()
    {
        @Override
        public String asConnectionDetails()
        {
            return "embedded-session\t";
        }
    };

    public String requestScheme()
    {
        return null;
    }

    public String clientAddress()
    {
        return null;
    }

    public String requestURI()
    {
        return null;
    }

    public static class BoltSession extends QuerySource
    {
        private final String principalName;
        private final String clientName;
        private final SocketAddress clientAddress;
        private final SocketAddress serverAddress;

        public BoltSession(
                String principalName,
                String clientName,
                SocketAddress clientAddress,
                SocketAddress serverAddress )
        {
            this.principalName = principalName;
            this.clientName = clientName;
            this.clientAddress = clientAddress;
            this.serverAddress = serverAddress;
        }

        @Override
        public String asConnectionDetails()
        {
            return String.format(
                    "bolt-session\tbolt\t%s\t%s\t\tclient%s\tserver%s>",
                    principalName,
                    clientName,
                    clientAddress,
                    serverAddress );
        }

        @Override
        public String requestScheme()
        {
            return "bolt";
        }

        @Override
        public String clientAddress()
        {
            return addressString( clientAddress );
        }

        @Override
        public String requestURI()
        {
            return addressString( serverAddress );
        }

        private String addressString( SocketAddress address )
        {
            if ( address instanceof InetSocketAddress )
            {
                InetSocketAddress inet = (InetSocketAddress) address;
                return String.format( "%s:%s", inet.getHostString(), inet.getPort() );
            }
            return address.toString();
        }
    }

    public static class ServerSession extends QuerySource
    {
        private final String scheme;
        private final InetSocketAddress clientAddress;
        private final InetSocketAddress serverAddress;
        private final String requestPath;

        public ServerSession(
                String scheme,
                @SuppressWarnings( "unused" ) String userAgent, // useful for achieving parity with BoltSession
                InetSocketAddress clientAddress,
                InetSocketAddress serverAddress,
                String requestPath )
        {
            this.scheme = scheme;
            this.clientAddress = clientAddress;
            this.serverAddress = serverAddress;
            this.requestPath = requestPath;
        }

        @Override
        public String asConnectionDetails()
        {
            return String.join( "\t", "server-session", scheme, clientAddress.getHostString(), requestPath );
        }

        @Override
        public String requestScheme()
        {
            return scheme;
        }

        @Override
        public String clientAddress()
        {
            return String.format( "%s:%s", clientAddress.getHostString(), clientAddress.getPort() );
        }

        @Override
        public String requestURI()
        {
            return serverAddress == null ? requestPath : String.format(
                    "%s://%s:%d%s",
                    scheme,
                    serverAddress.getHostString(),
                    serverAddress.getPort(),
                    requestPath );
        }
    }

    public static class ShellSession extends QuerySource
    {
        private final Serializable id;

        public ShellSession( Serializable id )
        {
            this.id = id;
        }

        @Override
        public String asConnectionDetails()
        {
            return "shell-session\tshell\t" + id;
        }
    }

    private static class SessionWithUsername extends QuerySource
    {
        private final QuerySource source;
        private final String username;

        private SessionWithUsername( QuerySource source, String username )
        {
            this.source = source;
            this.username = username;
        }

        @Override
        public String asConnectionDetails()
        {
            return source.asConnectionDetails() + '\t' + username;
        }

        @Override
        public String requestScheme()
        {
            return source.requestScheme();
        }

        @Override
        public String clientAddress()
        {
            return source.clientAddress();
        }

        @Override
        public String requestURI()
        {
            return source.requestURI();
        }
    }
}
