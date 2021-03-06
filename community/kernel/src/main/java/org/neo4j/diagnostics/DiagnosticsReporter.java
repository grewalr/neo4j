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
package org.neo4j.diagnostics;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.neo4j.helpers.Service;
import org.neo4j.io.fs.FileSystemAbstraction;
import org.neo4j.kernel.configuration.Config;


public class DiagnosticsReporter
{
    private final List<DiagnosticsOfflineReportProvider> providers = new ArrayList<>();
    private final Set<String> availableClassifiers = new TreeSet<>();
    private final Map<String,List<DiagnosticsReportSource>> additionalSources = new HashMap<>();

    public void registerOfflineProvider( DiagnosticsOfflineReportProvider provider )
    {
        providers.add( provider );
        availableClassifiers.addAll( provider.getFilterClassifiers() );
    }

    public void registerSource( String classifier, DiagnosticsReportSource source )
    {
        availableClassifiers.add( classifier );
        additionalSources.computeIfAbsent( classifier, c -> new ArrayList<>() ).add( source );
    }

    public void dump( Set<String> classifiers, Path destination, DiagnosticsReporterProgressCallback progress ) throws IOException
    {
        // Collect sources
        List<DiagnosticsReportSource> sources = new ArrayList<>();
        for ( DiagnosticsOfflineReportProvider provider : providers )
        {
            sources.addAll( provider.getDiagnosticsSources( classifiers ) );
        }

        // Add additional sources
        for ( String classifier : additionalSources.keySet() )
        {
            if ( classifiers.contains( "all" ) || classifiers.contains( classifier ) )
            {
                sources.addAll( additionalSources.get( classifier ) );
            }
        }

        // Make sure target directory exists
        Files.createDirectories( destination.getParent() );

        // Compress all files to destination
        Map<String,String> env = new HashMap<>();
        env.put( "create", "true" );

        // NOTE: we need the toUri() in order to handle windows file paths
        URI uri = URI.create("jar:file:" + destination.toAbsolutePath().toUri().getPath() );

        try ( FileSystem fs = FileSystems.newFileSystem( uri, env ) )
        {
            progress.setTotalSteps( sources.size() );
            for ( int i = 0; i < sources.size(); i++ )
            {
                DiagnosticsReportSource source = sources.get( i );
                Path path = fs.getPath( source.destinationPath() );
                if ( path.getParent() != null )
                {
                    Files.createDirectories( path.getParent() );
                }

                progress.started( i + 1, path.toString() );
                try
                {
                    source.addToArchive( path, progress );
                }
                catch ( Throwable e )
                {
                    progress.error( "Step failed", e );
                    continue;
                }
                progress.finished();
            }
        }
    }

    public Set<String> getAvailableClassifiers()
    {
        return availableClassifiers;
    }

    public void registerAllOfflineProviders( Config config, File storeDirectory, FileSystemAbstraction fs )
    {
        for ( DiagnosticsOfflineReportProvider provider : Service.load( DiagnosticsOfflineReportProvider.class ) )
        {
            provider.init( fs, config, storeDirectory );
            registerOfflineProvider( provider );
        }
    }
}
