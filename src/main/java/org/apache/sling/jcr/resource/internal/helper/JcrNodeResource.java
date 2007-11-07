/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.sling.jcr.resource.internal.helper;

import static org.apache.jackrabbit.JcrConstants.JCR_CONTENT;
import static org.apache.jackrabbit.JcrConstants.JCR_CREATED;
import static org.apache.jackrabbit.JcrConstants.JCR_DATA;
import static org.apache.jackrabbit.JcrConstants.JCR_ENCODING;
import static org.apache.jackrabbit.JcrConstants.JCR_LASTMODIFIED;
import static org.apache.jackrabbit.JcrConstants.JCR_MIMETYPE;
import static org.apache.jackrabbit.JcrConstants.NT_FILE;
import static org.apache.sling.api.resource.ResourceMetadata.CHARACTER_ENCODING;
import static org.apache.sling.api.resource.ResourceMetadata.CONTENT_TYPE;
import static org.apache.sling.api.resource.ResourceMetadata.CREATION_TIME;
import static org.apache.sling.api.resource.ResourceMetadata.MODIFICATION_TIME;
import static org.apache.sling.jcr.resource.JcrResourceConstants.SLING_RESOURCE_TYPE_PROPERTY;

import java.io.IOException;
import java.io.InputStream;

import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceMetadata;
import org.apache.sling.jcr.resource.internal.JcrResourceManager;

/** A Resource that wraps a JCR Node */
public class JcrNodeResource implements Resource {

    private static final Object UNDEFINED = "undefined";

    /** The relative path name of the data property of an nt:file node */
    private static final String FILE_DATA_PROP = JCR_CONTENT + "/" + JCR_DATA;

    private final JcrResourceManager cMgr;

    private final Node node;

    private final String path;

    private final String resourceType;

    private Object object = UNDEFINED;

    private Class<?> objectType;

    private final ResourceMetadata metadata;

    public JcrNodeResource(JcrResourceManager cMgr, Session s, String path)
            throws RepositoryException {
        this.cMgr = cMgr;
        node = (Node) s.getItem(path);
        this.path = node.getPath();
        metadata = new ResourceMetadata();
        resourceType = getResourceTypeForNode(node);

        // check for nt:file metadata
        setMetaData(node, metadata);
    }

    public JcrNodeResource(JcrResourceManager cMgr, Session s, String path,
            Class<?> type) throws RepositoryException {
        this.cMgr = cMgr;
        node = (Node) s.getItem(path);
        this.path = node.getPath();
        metadata = new ResourceMetadata();
        resourceType = getResourceTypeForNode(node);
        objectType = type;

        // check for nt:file metadata
        setMetaData(node, metadata);
    }

    public JcrNodeResource(JcrResourceManager cMgr, Node node)
            throws RepositoryException {
        this.cMgr = cMgr;
        this.node = node;
        this.path = node.getPath();
        metadata = new ResourceMetadata();
        metadata.put(ResourceMetadata.RESOLUTION_PATH, path);
        resourceType = getResourceTypeForNode(node);

        // check for nt:file metadata
        setMetaData(node, metadata);
    }

    public String toString() {
        return "JcrNodeResource, type=" + resourceType + ", path=" + path;
    }

    public Object getRawData() {
        return node;
    }

    public String getURI() {
        return path;
    }

    public String getResourceType() {
        return resourceType;
    }

    public Object getObject() {
        if (object == UNDEFINED) {
            // lazy loaded object
            object = cMgr.getObject(getURI(), objectType);
        }

        return object;
    }

    public ResourceMetadata getResourceMetadata() {
        return metadata;
    }

    /**
     * Returns a stream to the <em>jcr:content/jcr:data</em> property if the
     * {@link #getRawData() raw data} is an <em>nt:file</em> node. Otherwise
     * returns <code>null</code>.
     */
    public InputStream getInputStream() throws IOException {
        // implement this for nt:file only
        if (!(getRawData() instanceof Node)) {
            return null;
        }

        try {
            Node node = (Node) getRawData();
            if (node.isNodeType(NT_FILE) && node.hasProperty(FILE_DATA_PROP)) {
                return node.getProperty(FILE_DATA_PROP).getStream();
            }
        } catch (RepositoryException re) {
            throw (IOException) new IOException("Cannot get InputStream for "
                + getURI()).initCause(re);
        }

        // fallback to non-streamable resource
        return null;
    }

    /**
     * Compute the resource type of the given node, using either the
     * SLING_RESOURCE_TYPE_PROPERTY, or the node's primary type, if the property
     * is not set
     */
    public static String getResourceTypeForNode(Node node)
            throws RepositoryException {
        String result = null;

        if (node.hasProperty(SLING_RESOURCE_TYPE_PROPERTY)) {
            result = node.getProperty(SLING_RESOURCE_TYPE_PROPERTY).getValue().getString();
        }

        if (result == null || result.length() == 0) {
            result = node.getPrimaryNodeType().getName();
        }

        return result;
    }

    private static void setMetaData(Node node, ResourceMetadata metadata) {
        try {
            if (node.isNodeType(NT_FILE)) {
                metadata.put(CREATION_TIME,
                    node.getProperty(JCR_CREATED).getLong());

                if (node.hasNode(JCR_CONTENT)) {
                    Node content = node.getNode(JCR_CONTENT);
                    if (content.hasProperty(JCR_MIMETYPE)) {
                        metadata.put(CONTENT_TYPE, content.getProperty(
                            JCR_MIMETYPE).getString());
                    }

                    if (content.hasProperty(JCR_ENCODING)) {
                        metadata.put(CHARACTER_ENCODING, content.getProperty(
                            JCR_ENCODING).getString());
                    }

                    if (content.hasProperty(JCR_LASTMODIFIED)) {
                        metadata.put(MODIFICATION_TIME, content.getProperty(
                            JCR_LASTMODIFIED).getLong());
                    }
                }
            }
        } catch (RepositoryException re) {
            // TODO: should log
        }
    }

}
