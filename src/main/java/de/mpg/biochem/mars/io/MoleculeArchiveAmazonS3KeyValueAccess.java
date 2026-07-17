/*-
 * #%L
 * Molecule Archive Suite (Mars) - core data storage and processing algorithms.
 * %%
 * Copyright (C) 2018 - 2026 Karl Duderstadt
 * %%
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * 
 * 1. Redistributions of source code must retain the above copyright notice,
 *    this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDERS OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 * #L%
 */

package de.mpg.biochem.mars.io;

import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.channels.NonReadableChannelException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import software.amazon.awssdk.services.s3.model.CommonPrefix;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Object;

public class MoleculeArchiveAmazonS3KeyValueAccess {
    private final S3Client s3;
    private final String bucketName;

    /**
     * Opens an {@link S3Client} client and a given bucket name.
     *
     * @param s3 the s3 instance
     * @param bucketName the bucket name
     * @throws IOException if the access could not be created
     */
    public MoleculeArchiveAmazonS3KeyValueAccess(final S3Client s3, final String bucketName) throws IOException {

        this.s3 = s3;
        this.bucketName = bucketName;

        try {
            s3.headBucket(HeadBucketRequest.builder().bucket(bucketName).build());
        } catch (final NoSuchBucketException e) {
            throw new IOException("Bucket " + bucketName + " does not exist.");
        }
    }

    public String[] components(final String path) {

        return Arrays.stream(path.split("/"))
                .filter(x -> !x.isEmpty())
                .toArray(String[]::new);
    }


    public String compose(final String... components) {

        if (components == null || components.length == 0)
            return null;

        return normalize(
                Arrays.stream(components)
                        .filter(x -> !x.isEmpty())
                        .collect(Collectors.joining("/"))
        );
    }


    public String parent(final String path) {

        final String[] components = components(path);
        final String[] parentComponents =Arrays.copyOf(components, components.length - 1);

        return compose(parentComponents);
    }

    public String normalize(final String path) {

        return normalizeGroupPath(path);
    }

    /**
     * Normalize a group path relative to a container's root, resulting in
     * removal of redundant "/", "./", resolution of relative "../",
     * and removal of leading slashes.
     *
     * @param path
     *            to normalize
     * @return the normalized path
     */
    public static String normalizeGroupPath(final String path) {

        /*
         * Alternatively, could do something like the below in every
         * KeyValueReader implementation
         *
         * return keyValueAccess.relativize( N5URI.normalizeGroupPath(path),
         * basePath);
         *
         * has to be in the implementations, since KeyValueAccess doesn't have a
         * basePath.
         */
        return normalizePath(path.startsWith("/") || path.startsWith("\\") ? path.substring(1) : path);
    }

    /**
     * Normalize a POSIX path, resulting in removal of redundant "/", "./", and
     * resolution of relative "../".
     * <p>
     * NOTE: currently a private helper method only used by normalizeGroupPath(String).
     * 	It's safe to do in that case since relative group paths should always be POSIX compliant.
     * 	A new helper method to understand other path types (e.g. Windows) may be necessary eventually.
     *
     * @param path
     *            to normalize
     * @return the normalized path
     */
    private static String normalizePath(String path) {

        path = path == null ? "" : path;
        final char[] pathChars = path.toCharArray();

        final List<String> tokens = new ArrayList<>();
        final StringBuilder curToken = new StringBuilder();
        boolean escape = false;
        for (final char character : pathChars) {
            /* Skip if we last saw escape */
            if (escape) {
                escape = false;
                curToken.append(character);
                continue;
            }
            /* Check if we are escape character */
            if (character == '\\') {
                escape = true;
            } else if (character == '/') {
                if (tokens.isEmpty() && curToken.length() == 0) {
                    /* If we are root, and the first token, then add the '/' */
                    curToken.append(character);
                }

                /*
                 * The current token is complete, add it to the list, if it
                 * isn't empty
                 */
                final String newToken = curToken.toString();
                if (!newToken.isEmpty()) {
                    /*
                     * If our token is '..' then remove the last token instead
                     * of adding a new one
                     */
                    if (newToken.equals("..")) {
                        tokens.remove(tokens.size() - 1);
                    } else {
                        tokens.add(newToken);
                    }
                }
                /* reset for the next token */
                curToken.setLength(0);
            } else {
                curToken.append(character);
            }
        }
        final String lastToken = curToken.toString();
        if (!lastToken.isEmpty()) {
            if (lastToken.equals("..")) {
                tokens.remove(tokens.size() - 1);
            } else {
                tokens.add(lastToken);
            }
        }
        if (tokens.isEmpty())
            return "";
        String root = "";
        if (tokens.get(0).equals("/")) {
            tokens.remove(0);
            root = "/";
        }
        return root + tokens
                .stream()
                .filter(it -> !it.equals("."))
                .filter(it -> !it.isEmpty())
                .reduce((l, r) -> l + "/" + r)
                .orElse("");
    }

    /**
     * Test whether the {@code normalPath} exists.
     * <p>
     * Removes leading slash from {@code normalPath}, and then checks whether
     * either {@code path} or {@code path + "/"} is a key.
     *
     * @param normalPath is expected to be in normalized form, no further
     * 		efforts are made to normalize it.
     * @return {@code true} if {@code path} exists, {@code false} otherwise
     */
    public boolean exists(final String normalPath) {

        return isDirectory(normalPath) || isFile(normalPath);
    }

    /**
     * Check existence of the given {@code key}.
     *
     * @return {@code true} if {@code key} exists.
     */
    private boolean keyExists(final String key) {
        final ListObjectsV2Request listObjectsRequest = ListObjectsV2Request.builder()
                .bucket(bucketName)
                .prefix(key)
                .maxKeys(1)
                .build();
        final ListObjectsV2Response objectsListing = s3.listObjectsV2(listObjectsRequest);
        return objectsListing.keyCount() > 0;
    }

    /**
     * When listing children objects for a group, must append a delimiter to the path (e.g. group/data/).
     * This is necessary for not including wrong objects in the filtered set
     * (e.g. group/data-2/attributes.json when group/data is passed without the last slash).
     *
     * @param path the path
     * @return the path with a trailing slash
     */
    public static String addTrailingSlash(final String path) {
        return path.endsWith("/") ? path : path + "/";
    }

    /**
     * When absolute paths are passed (e.g. /group/data), AWS S3 service creates an additional root folder with an empty name.
     * This method removes the root slash symbol and returns the corrected path.
     *
     * @param path the path
     * @return the path without the leading slash
     */
    public static String removeLeadingSlash(final String path) {
        return path.startsWith("/") ? path.substring(1) : path;
    }

    /**
     * Test whether the path is a directory.
     * <p>
     * Appends trailing "/" to {@code normalPath} if there is none, removes
     * leading "/", and then checks whether resulting {@code path} is a key.
     *
     * @param normalPath is expected to be in normalized form, no further
     * 		efforts are made to normalize it.
     * @return {@code true} if {@code path} (with trailing "/") exists as a key, {@code false} otherwise
     */

    public boolean isDirectory(final String normalPath) {
        final String key = removeLeadingSlash(addTrailingSlash(normalPath));
        return key.isEmpty() || keyExists(key);
    }

    /**
     * Test whether the path is a file.
     * <p>
     * Checks whether {@code normalPath} has no trailing "/", then removes
     * leading "/" and checks whether the resulting {@code path} is a key.
     *
     * @param normalPath is expected to be in normalized form, no further
     * 		efforts are made to normalize it.
     * @return {@code true} if {@code path} exists as a key and has no trailing slash, {@code false} otherwise
     */

    public boolean isFile(final String normalPath) {
        return !normalPath.endsWith("/") && keyExists(removeLeadingSlash(normalPath));
    }


    public LockedChannel lockForReading(final String normalPath) throws IOException {
        return new S3ObjectChannel(removeLeadingSlash(normalPath), true);
    }


    public LockedChannel lockForWriting(final String normalPath) throws IOException {
        return new S3ObjectChannel(removeLeadingSlash(normalPath), false);
    }

    public List<String> listObjectKeys(final String normalPath) {
        final List<String> keys = new ArrayList<>();
        final String prefix = removeLeadingSlash(addTrailingSlash(normalPath));
        String continuationToken = null;
        ListObjectsV2Response objectsListing;
        do {
            objectsListing = s3.listObjectsV2(ListObjectsV2Request.builder()
                    .bucket(bucketName)
                    .prefix(prefix)
                    .delimiter("/")
                    .continuationToken(continuationToken)
                    .build());
            for (final S3Object objectSummary : objectsListing.contents()) {
                keys.add(objectSummary.key());
            }
            continuationToken = objectsListing.nextContinuationToken();
        } while (objectsListing.isTruncated());
        return keys;
    }

    public String[] listDirectories(final String normalPath) {
        return list(normalPath, true);
    }

    private String[] list(final String normalPath, final boolean onlyDirectories) {
        final List<String> items = new ArrayList<>();
        final String prefix = removeLeadingSlash(addTrailingSlash(normalPath));
        String continuationToken = null;
        ListObjectsV2Response objectsListing;
        do {
            objectsListing = s3.listObjectsV2(ListObjectsV2Request.builder()
                    .bucket(bucketName)
                    .prefix(prefix)
                    .delimiter("/")
                    .continuationToken(continuationToken)
                    .build());
            for (final CommonPrefix commonPrefix : objectsListing.commonPrefixes()) items.add(lastGroupName(commonPrefix.prefix()));
            if (!onlyDirectories)
                for (final S3Object objectSummary : objectsListing.contents()) items.add(lastGroupName(objectSummary.key()));
            continuationToken = objectsListing.nextContinuationToken();
        } while (objectsListing.isTruncated());
        return items.toArray(new String[items.size()]);
    }

    private String lastGroupName(final String pathName) {
        String[] parts = pathName.split("/");
        return parts[parts.length - 1];
    }

    public String[] list(final String normalPath) throws IOException {
        return list(normalPath, false);
    }


    public void createDirectories(final String normalPath) throws IOException {

        String path = "";
        for (final String component : components(removeLeadingSlash(normalPath))) {
            path = addTrailingSlash(compose(path, component));
            if (path.equals("/")) {
                continue;
            }
            s3.putObject(
                    PutObjectRequest.builder().bucket(bucketName).key(path).build(),
                    RequestBody.fromBytes(new byte[0]));
        }
    }

    public void delete(final String normalPath) throws IOException {

        try {
            s3.headBucket(HeadBucketRequest.builder().bucket(bucketName).build());
        } catch (final NoSuchBucketException e) {
            return;
        }

        // remove bucket when deleting "/"
        if (normalPath.equals(normalize("/"))) {

            // need to delete all objects before deleting the bucket
            // see: https://docs.aws.amazon.com/AmazonS3/latest/userguide/delete-bucket.html
            String continuationToken = null;
            ListObjectsV2Response objectsListing;
            do {
                objectsListing = s3.listObjectsV2(ListObjectsV2Request.builder()
                        .bucket(bucketName)
                        .continuationToken(continuationToken)
                        .build());
                for (final S3Object object : objectsListing.contents())
                    s3.deleteObject(DeleteObjectRequest.builder().bucket(bucketName).key(object.key()).build());
                continuationToken = objectsListing.nextContinuationToken();
            } while (objectsListing.isTruncated());

            s3.deleteBucket(DeleteBucketRequest.builder().bucket(bucketName).build());
            return;
        }

        final String path = removeLeadingSlash(normalPath);

        if (!path.endsWith("/")) {
            s3.deleteObjects(DeleteObjectsRequest.builder()
                    .bucket(bucketName)
                    .delete(Delete.builder().objects(ObjectIdentifier.builder().key(path).build()).build())
                    .build());
        }

        final String prefix = addTrailingSlash(path);
        String continuationToken = null;
        ListObjectsV2Response objectsListing;
        do {
            objectsListing = s3.listObjectsV2(ListObjectsV2Request.builder()
                    .bucket(bucketName)
                    .prefix(prefix)
                    .continuationToken(continuationToken)
                    .build());
            final List<ObjectIdentifier> objectsToDelete = new ArrayList<>();
            for (final S3Object object : objectsListing.contents())
                objectsToDelete.add(ObjectIdentifier.builder().key(object.key()).build());

            if (!objectsToDelete.isEmpty()) {
                s3.deleteObjects(DeleteObjectsRequest.builder()
                        .bucket(bucketName)
                        .delete(Delete.builder().objects(objectsToDelete).build())
                        .build());
            }
            continuationToken = objectsListing.nextContinuationToken();
        } while (objectsListing.isTruncated());
    }

    private class S3ObjectChannel implements LockedChannel {

        protected final String path;
        final boolean readOnly;
        private final ArrayList<Closeable> resources = new ArrayList<>();

        protected S3ObjectChannel(final String path, final boolean readOnly) throws IOException {

            this.path = path;
            this.readOnly = readOnly;
        }

        private void checkWritable() {

            if (readOnly) {
                throw new NonReadableChannelException();
            }
        }

        @Override
        public InputStream newInputStream() throws IOException {
            final ResponseInputStream<GetObjectResponse> in = s3.getObject(
                    GetObjectRequest.builder().bucket(bucketName).key(path).build());
            synchronized (resources) {
                resources.add(in);
            }
            return in;
        }

        @Override
        public Reader newReader() throws IOException {

            final InputStreamReader reader = new InputStreamReader(newInputStream(), StandardCharsets.UTF_8);
            synchronized (resources) {
                resources.add(reader);
            }
            return reader;
        }

        @Override
        public OutputStream newOutputStream() throws IOException {

            checkWritable();
            return new S3OutputStream();
        }

        @Override
        public Writer newWriter() throws IOException {

            checkWritable();
            final OutputStreamWriter writer = new OutputStreamWriter(newOutputStream(), StandardCharsets.UTF_8);
            synchronized (resources) {
                resources.add(writer);
            }
            return writer;
        }

        @Override
        public void close() throws IOException {

            synchronized (resources) {
                for (final Closeable resource : resources)
                    resource.close();
                resources.clear();
            }
        }

        final class S3OutputStream extends OutputStream {
            private final ByteArrayOutputStream buf = new ByteArrayOutputStream();

            private boolean closed = false;

            @Override
            public void write(final byte[] b, final int off, final int len) throws IOException {

                buf.write(b, off, len);
            }

            @Override
            public void write(final int b) throws IOException {

                buf.write(b);
            }

            @Override
            public synchronized void close() throws IOException {

                if (!closed) {
                    closed = true;
                    final byte[] bytes = buf.toByteArray();
                    s3.putObject(
                            PutObjectRequest.builder().bucket(bucketName).key(path).build(),
                            RequestBody.fromBytes(bytes));
                    buf.close();
                }
            }
        }
    }
}
