/**
 * Copyright (C) 2011-2021 Red Hat, Inc. (https://github.com/Commonjava/indy-sidecar)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.commonjava.util.sidecar.services;

import org.apache.commons.io.IOUtils;
import org.apache.http.NoHttpResponseException;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.conn.ConnectionPoolTimeoutException;
import org.apache.http.impl.client.BasicCookieStore;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.commonjava.util.sidecar.config.SidecarConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@ApplicationScoped
public class ArchiveRetrieveService
{

    private final Logger logger = LoggerFactory.getLogger( getClass() );

    private final static int CONNECTION_REQUEST_TIMEOUT = 30 * 60 * 1000; // 30m

    private final static int CONNECTION_TIMEOUT = 30 * 60 * 1000; // 30m

    private final static int SOCKET_TIMEOUT = 30 * 60 * 1000; // 30m

    private final static String BUILD_CONFIG_ID = "build.config.id";

    private final static String MAVEN_META = "maven-metadata.xml";

    private final String PART_SUFFIX = ".part";

    private final String ARCHIVE_SUFFIX = ".zip";

    private final String DEFAULT_REPO_PATH = "download";

    private CloseableHttpClient client;

    protected final static List<String> decompressedBuilds = new ArrayList<>();

    @Inject
    SidecarConfig sidecarConfig;

    private boolean decompressSuccess;

    public boolean isDecompressed()
    {
        return decompressedBuilds.contains( getBuildConfigId() );
    }

    @PostConstruct
    public void init()
    {
        final PoolingHttpClientConnectionManager ccm = new PoolingHttpClientConnectionManager();
        ccm.setMaxTotal( 30 );
        RequestConfig rc = RequestConfig.custom()
                                        .setConnectionRequestTimeout( CONNECTION_REQUEST_TIMEOUT )
                                        .setConnectTimeout( CONNECTION_TIMEOUT )
                                        .setSocketTimeout( SOCKET_TIMEOUT )
                                        .build();
        final HttpClientBuilder builder = HttpClients.custom()
                                                     .setConnectionManager( ccm )
                                                     .setDefaultRequestConfig( rc )
                                                     .setRetryHandler( ( exception, executionCount, context ) -> {
                                                         if ( executionCount > 3 )
                                                         {
                                                             return false;
                                                         }
                                                         if ( exception instanceof NoHttpResponseException )
                                                         {
                                                             logger.info( "NoHttpResponse start to retry times:"
                                                                                          + executionCount );
                                                             return true;
                                                         }
                                                         if ( exception instanceof SocketTimeoutException )
                                                         {
                                                             logger.info( "SocketTimeout start to retry times:"
                                                                                          + executionCount );
                                                             return true;
                                                         }
                                                         if ( exception instanceof ConnectionPoolTimeoutException )
                                                         {
                                                             logger.info( "ConnectionPoolTimeout start to retry times:"
                                                                                          + executionCount );
                                                             return true;
                                                         }
                                                         return false;
                                                     } );
        client = builder.build();

        logger.info( "Decompress archive" );
        if ( !isDecompressed() )
        {
            this.decompressSuccess = decompressArchive();
            //bus.publish( ARCHIVE_DECOMPRESS_COMPLETE, sidecarConfig.localRepository.orElse( DEFAULT_REPO_PATH ) );
        }
    }

    @PreDestroy
    public void destroy()
    {
        try
        {
            File downloadDir = new File( sidecarConfig.localRepository.get() );
            List<File> downloads = Files.walk( downloadDir.toPath() )
                                        .filter( Files::isRegularFile )
                                        .map( Path::toFile )
                                        .collect( Collectors.toList() );
            for ( File content : downloads )
            {
                content.delete();
            }
            downloadDir.delete();
        }
        catch ( IOException e )
        {
            logger.error( "Failed to clean up downloads", e );
        }
        finally
        {
            IOUtils.closeQuietly( client, null );
        }
    }

    public boolean decompressArchive()
    {
        if ( sidecarConfig.archiveApi.isEmpty() )
        {
            return false;
        }
        final File target = new File( sidecarConfig.localRepository.orElse( DEFAULT_REPO_PATH ),
                                      getBuildConfigId() + ARCHIVE_SUFFIX );

        if ( !retrieveArchive( target ) )
        {
            return false;
        }
        if ( target.exists() && target.length() <= 0 )
        {
            return false;
        }

        return writeDecompressedFiles( target );
    }

    public Optional<File> getLocally( final String path )
    {
        File download = new File( sidecarConfig.localRepository.orElse( DEFAULT_REPO_PATH ) + File.separator + path );
        if ( !download.exists() )
        {
            return Optional.empty();
        }
        return Optional.of( download );
    }

    public boolean shouldProxy( final String path )
    {
        return getBuildConfigId() == null || getBuildConfigId().trim().isEmpty() || path.endsWith( MAVEN_META ) || !decompressSuccess;
    }

    public String getBuildConfigId()
    {
        return System.getenv( BUILD_CONFIG_ID );
    }

    private boolean retrieveArchive( final File target )
    {
        String buildConfigId = getBuildConfigId();
        final File dir = target.getParentFile();
        dir.mkdirs();
        final File part = new File( dir, target.getName() + PART_SUFFIX );

        final HttpClientContext context = new HttpClientContext();
        context.setCookieStore( new BasicCookieStore() );
        String path = String.format( "%s/%s", sidecarConfig.archiveApi.get(), buildConfigId );
        final HttpGet request = new HttpGet( path );
        InputStream input = null;
        try
        {
            CloseableHttpResponse response = client.execute( request, context );
            int statusCode = response.getStatusLine().getStatusCode();
            if ( statusCode == 200 )
            {
                try (FileOutputStream out = new FileOutputStream( part ))
                {
                    input = response.getEntity().getContent();
                    IOUtils.copy( input, out );
                }
                part.renameTo( target );
                return true;
            }
            // first build case
            else if ( statusCode == 404 )
            {
                logger.info( "Not Found archive for build config id: {}", buildConfigId );
                return false;
            }
            else
            {
                logger.error( "Error when getting the archive for build config id: {}", buildConfigId );
                return false;
            }
        }
        catch ( final Exception e )
        {
            e.printStackTrace();
            logger.error( "Failed to download archive for build config id: {}", buildConfigId );
            return false;
        }
        finally
        {
            request.releaseConnection();
            request.reset();
            IOUtils.closeQuietly( input, null );
        }
    }

    private boolean writeDecompressedFiles( final File target )
    {
        FileInputStream fis;
        byte[] buffer = new byte[1024];
        try
        {
            fis = new FileInputStream( target );
            ZipInputStream zis = new ZipInputStream( fis );
            ZipEntry ze = zis.getNextEntry();

            while ( ze != null )
            {
                String fileName = ze.getName();
                File newFile = new File( sidecarConfig.localRepository.orElse( DEFAULT_REPO_PATH ) + File.separator
                                                         + fileName );
                logger.debug( "Unzipping to {}", newFile.getAbsolutePath() );
                new File( newFile.getParent() ).mkdirs();
                FileOutputStream fos = new FileOutputStream( newFile );

                int len;
                while ( ( len = zis.read( buffer ) ) > 0 )
                {
                    fos.write( buffer, 0, len );
                }
                fos.close();
                zis.closeEntry();
                ze = zis.getNextEntry();
            }
            decompressedBuilds.add( getBuildConfigId() );
            zis.closeEntry();
            zis.close();
            fis.close();
            return true;
        }
        catch ( IOException e )
        {
            e.printStackTrace();
            logger.error( "Failed to decompress the archive for build config id: " + getBuildConfigId(), e );
            return false;
        }
    }
}
