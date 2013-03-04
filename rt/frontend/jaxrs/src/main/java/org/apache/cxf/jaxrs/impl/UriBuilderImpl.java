/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.cxf.jaxrs.impl;

import java.lang.reflect.Method;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.ws.rs.Path;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.PathSegment;
import javax.ws.rs.core.UriBuilder;
import javax.ws.rs.core.UriBuilderException;

import org.apache.cxf.common.util.StringUtils;
import org.apache.cxf.jaxrs.model.URITemplate;
import org.apache.cxf.jaxrs.utils.HttpUtils;
import org.apache.cxf.jaxrs.utils.JAXRSUtils;

public class UriBuilderImpl extends UriBuilder {

    private String scheme;
    private String userInfo;
    private int port = -1;
    private String host;
    private List<PathSegment> paths = new ArrayList<PathSegment>();
    private boolean leadingSlash;
    private String fragment;
    private String schemeSpecificPart; 
    private MultivaluedMap<String, String> query = new MetadataMap<String, String>();
    private MultivaluedMap<String, String> matrix = new MetadataMap<String, String>();
    
    /**
     * Creates builder with empty URI.
     */
    public UriBuilderImpl() {
    }

    /**
     * Creates builder initialized with given URI.
     * 
     * @param uri initial value for builder
     * @throws IllegalArgumentException when uri is null
     */
    public UriBuilderImpl(URI uri) throws IllegalArgumentException {
        setUriParts(uri);
    }

    @Override
    public URI build(Object... values) throws IllegalArgumentException, UriBuilderException {
        return doBuild(false, values);
    }

    private URI doBuild(boolean fromEncoded, Object... values) {
        if (values == null) {
            throw new IllegalArgumentException("Template parameter values are set to null");
        }
        for (int i = 0; i < values.length; i++) {
            if (values[i] == null) {
                throw new IllegalArgumentException("Template parameter value is set to null");
            }
        }
        
        String thePath = buildPath();
        URITemplate pathTempl = new URITemplate(thePath);
        thePath = substituteVarargs(pathTempl, values, 0, false, fromEncoded);
        
        String theQuery = buildQuery();
        int queryTemplateVarsSize = 0;
        if (theQuery != null) {
            URITemplate queryTempl = new URITemplate(theQuery);
            queryTemplateVarsSize = queryTempl.getVariables().size();
            if (queryTemplateVarsSize > 0) {
                int lengthDiff = values.length - pathTempl.getVariables().size(); 
                queryTemplateVarsSize = queryTempl.getVariables().size(); 
                theQuery = substituteVarargs(queryTempl, values, values.length - lengthDiff, true, fromEncoded);
            }
        }
        
        String theFragment = fragment;
        if (theFragment != null) {
            URITemplate fragmentTempl = new URITemplate(theFragment);
            if (fragmentTempl.getVariables().size() > 0) {
                int lengthDiff = values.length - pathTempl.getVariables().size() - queryTemplateVarsSize; 
                theFragment = substituteVarargs(fragmentTempl, values, 
                    values.length - lengthDiff, true, fromEncoded);
            }
        }
        
        try {
            return buildURI(fromEncoded, thePath, theQuery, theFragment);
        } catch (URISyntaxException ex) {
            throw new UriBuilderException("URI can not be built", ex);
        }
    }
    
    private URI buildURI(boolean fromEncoded, String thePath, String theQuery, String theFragment) 
        throws URISyntaxException {
        if (fromEncoded) { 
            return buildURIFromEncoded(thePath, theQuery, theFragment);
        } else if (!isSchemeOpaque()) {
            if ((scheme != null || host != null || userInfo != null)
                && thePath.length() != 0 && !thePath.startsWith("/")) {
                thePath = "/" + thePath;
            }
            try {
                return buildURIFromEncoded(thePath, theQuery, theFragment);
            } catch (Exception ex) {
                // lets try the option below
            }
            URI uri = new URI(scheme, userInfo, host, port, 
                           thePath, theQuery, theFragment);
            if (thePath.contains("%2F")) {
                // TODO: the bogus case of segments containing encoded '/'
                // Not sure if we have a cleaner solution though.
                String realPath = uri.getRawPath().replace("%252F", "%2F");
                uri = buildURIFromEncoded(realPath, uri.getRawQuery(), uri.getRawFragment());
            }
            return uri;
        } else {
            return new URI(scheme, schemeSpecificPart, theFragment);
        }
    }
    
    private URI buildURIFromEncoded(String thePath, String theQuery, String theFragment) 
        throws URISyntaxException {
        StringBuilder b = new StringBuilder();
        if (scheme != null) {
            b.append(scheme).append(":");
        }
        if (!isSchemeOpaque()) {
            if (scheme != null) {
                b.append("//");
            }
            if (userInfo != null) {
                b.append(userInfo).append('@');
            }
            if (host != null) {
                b.append(host);
            }
            if (port != -1) {
                b.append(':').append(port);    
            }
            if (thePath != null && thePath.length() > 0) {
                b.append(thePath.startsWith("/") || b.length() == 0 ? thePath : '/' + thePath);
            }
            if (theQuery != null && theQuery.length() != 0) {
                b.append('?').append(theQuery);
            }
        } else {
            b.append(schemeSpecificPart);
        }
        if (theFragment != null) {
            b.append('#').append(theFragment);
        }
        return new URI(b.toString());
    }
    
    private boolean isSchemeOpaque() {
        return schemeSpecificPart != null;
    }
    
    @Override
    public URI buildFromEncoded(Object... values) throws IllegalArgumentException, UriBuilderException {
        return doBuild(true, values);
    }

    @Override
    public URI buildFromMap(Map<String, ? extends Object> map) throws IllegalArgumentException,
        UriBuilderException {
        return doBuildFromMap(map, false);
    }

    private URI doBuildFromMap(Map<String, ? extends Object> map, boolean fromEncoded) 
        throws IllegalArgumentException, UriBuilderException {
        try {
            String thePath = buildPath();
            thePath = substituteMapped(thePath, map, false, fromEncoded);
            
            String theQuery = buildQuery();
            if (theQuery != null) {
                theQuery = substituteMapped(theQuery, map, true, fromEncoded);
            }
            
            String theFragment = fragment == null ? null 
                : substituteMapped(fragment, map, true, fromEncoded);
            
            return buildURI(fromEncoded, thePath, theQuery, theFragment);
        } catch (URISyntaxException ex) {
            throw new UriBuilderException("URI can not be built", ex);
        }
    }
    private String substituteVarargs(URITemplate templ, 
                                     Object[] values, 
                                     int ind,
                                     boolean isQuery,
                                     boolean fromEncoded) {
        
        Map<String, String> varValueMap = new HashMap<String, String>();
        
        // vars in set are properly ordered due to linking in hash set
        Set<String> uniqueVars = new LinkedHashSet<String>(templ.getVariables());
        if (values.length < uniqueVars.size()) {
            throw new IllegalArgumentException("Unresolved variables; only " + values.length
                                               + " value(s) given for " + uniqueVars.size()
                                               + " unique variable(s)");
        }
        int idx = ind;
        for (String var : uniqueVars) {
            
            Object oval = values[idx++];
            if (oval == null) {
                throw new IllegalArgumentException("No object for " + var);
            }
            String value = oval.toString();
            if (fromEncoded) {
                value = HttpUtils.encodePartiallyEncoded(value, isQuery);
            } else {
                value = isQuery ? HttpUtils.queryEncode(value) : HttpUtils.pathEncode(value);
            }
            
            varValueMap.put(var, value);
            
        }
        return templ.substitute(varValueMap);
    }
    
    private String substituteMapped(String path, Map<String, ? extends Object> varValueMap,
        boolean isQuery, boolean fromEncoded) {
    
        URITemplate templ = new URITemplate(path);
        
        Set<String> uniqueVars = new HashSet<String>(templ.getVariables());
        if (varValueMap.size() < uniqueVars.size()) {
            throw new IllegalArgumentException("Unresolved variables; only " + varValueMap.size()
                                               + " value(s) given for " + uniqueVars.size()
                                               + " unique variable(s)");
        }
        Map<String, Object> theMap = new LinkedHashMap<String, Object>(); 
        for (String var : uniqueVars) {
            Object oval = varValueMap.get(var);
            if (oval == null) {
                throw new IllegalArgumentException("No object for " + var);
            }
            if (fromEncoded) {
                oval = HttpUtils.encodePartiallyEncoded(oval.toString(), isQuery);
            } else {
                oval = isQuery ? HttpUtils.queryEncode(oval.toString()) : HttpUtils.pathEncode(oval.toString());
            }
            theMap.put(var, oval);
        }
        return templ.substitute(theMap);
    }

    @Override
    public URI buildFromEncodedMap(Map<String, ? extends Object> map) throws IllegalArgumentException,
        UriBuilderException {
        
        Map<String, String> decodedMap = new HashMap<String, String>(map.size());
        for (Map.Entry<String, ? extends Object> entry : map.entrySet()) {
            if (entry.getValue() == null) {
                throw new IllegalArgumentException("Value is null");
            }
            String theValue = entry.getValue().toString();
            if (theValue.contains("/")) {
                // protecting '/' from being encoded here assumes that a given value may constitute multiple
                // path segments - very questionable especially given that queries and fragments may also 
                // contain template vars - technically this can be covered by checking where a given template
                // var is coming from and act accordingly. Confusing nonetheless.
                StringBuilder buf = new StringBuilder();
                String[] values = StringUtils.split(theValue, "/");
                for (int i = 0; i < values.length; i++) {
                    buf.append(HttpUtils.encodePartiallyEncoded(values[i], false));
                    if (i + 1 < values.length) {
                        buf.append("/");
                    }
                }
                decodedMap.put(entry.getKey(), buf.toString());
            } else {
                decodedMap.put(entry.getKey(), HttpUtils.encodePartiallyEncoded(theValue, false));
            }
            
        }
        return doBuildFromMap(decodedMap, true);
    }

    // CHECKSTYLE:OFF
    @Override
    public UriBuilder clone() {
        UriBuilderImpl builder = new UriBuilderImpl();
        builder.scheme = scheme;
        builder.userInfo = userInfo;
        builder.port = port;
        builder.host = host;
        builder.paths = new ArrayList<PathSegment>(paths);
        builder.fragment = fragment;
        builder.query = new MetadataMap<String, String>(query);
        builder.matrix = new MetadataMap<String, String>(matrix);
        builder.schemeSpecificPart = schemeSpecificPart;
        builder.leadingSlash = leadingSlash;
        return builder;
    }

    // CHECKSTYLE:ON

    @Override
    public UriBuilder fragment(String theFragment) throws IllegalArgumentException {
        this.fragment = theFragment;
        return this;
    }

    @Override
    public UriBuilder host(String theHost) throws IllegalArgumentException {
        if ("".equals(theHost)) {
            throw new IllegalArgumentException("Host cannot be empty");
        }
        this.host = theHost;
        return this;
    }

    @Override
    public UriBuilder path(@SuppressWarnings("rawtypes") Class resource) throws IllegalArgumentException {
        if (resource == null) {
            throw new IllegalArgumentException("resource is null");
        }
        Class<?> cls = resource;
        Path ann = cls.getAnnotation(Path.class);
        if (ann == null) {
            throw new IllegalArgumentException("Class '" + resource.getCanonicalName()
                                               + "' is not annotated with Path");
        }
        // path(String) decomposes multi-segment path when necessary
        return path(ann.value());
    }

    @Override
    public UriBuilder path(@SuppressWarnings("rawtypes") Class resource, String method) 
        throws IllegalArgumentException {
        if (resource == null) {
            throw new IllegalArgumentException("resource is null");
        }
        if (method == null) {
            throw new IllegalArgumentException("method is null");
        }
        Path foundAnn = null;
        for (Method meth : resource.getMethods()) {
            if (meth.getName().equals(method)) {
                Path ann = meth.getAnnotation(Path.class);
                if (foundAnn != null && ann != null) {
                    throw new IllegalArgumentException("Multiple Path annotations for '" + method
                                                       + "' overloaded method");
                }
                foundAnn = ann;
            }
        }
        if (foundAnn == null) {
            throw new IllegalArgumentException("No Path annotation for '" + method + "' method");
        }
        // path(String) decomposes multi-segment path when necessary
        return path(foundAnn.value());
    }

    @Override
    public UriBuilder path(Method method) throws IllegalArgumentException {
        if (method == null) {
            throw new IllegalArgumentException("method is null");
        }
        Path ann = method.getAnnotation(Path.class);
        if (ann == null) {
            throw new IllegalArgumentException("Method '" + method.getClass().getCanonicalName() + "."
                                               + method.getName() + "' is not annotated with Path");
        }
        // path(String) decomposes multi-segment path when necessary
        return path(ann.value());
    }

    @Override
    public UriBuilder path(String path) throws IllegalArgumentException {
        return doPath(path, true);
    }

    private UriBuilder doPath(String path, boolean checkSegments) {
        if (path == null) {
            throw new IllegalArgumentException("path is null");
        }
        if (isAbsoluteUriPath(path)) {
            uri(URI.create(path));
            return this;
        }
        
        if (paths.isEmpty()) {
            leadingSlash = path.startsWith("/");
        }
        
        List<PathSegment> segments;
        if (checkSegments) { 
            segments = JAXRSUtils.getPathSegments(path, false, false);
        } else {
            segments = new ArrayList<PathSegment>();
            segments.add(new PathSegmentImpl(path.replaceAll("/", "%2F"), false));
        }
        if (!paths.isEmpty() && !matrix.isEmpty()) {
            PathSegment ps = paths.remove(paths.size() - 1);
            paths.add(replacePathSegment(ps));
        }
        paths.addAll(segments);
        matrix.clear();
        if (!paths.isEmpty()) {
            matrix = paths.get(paths.size() - 1).getMatrixParameters();        
        }
        return this;
    }
    
    @Override
    public UriBuilder port(int thePort) throws IllegalArgumentException {
        if (thePort < 0 && thePort != -1) {
            throw new IllegalArgumentException("Port cannot be negative");
        }
        this.port = thePort;
        return this;
    }

    @Override
    public UriBuilder scheme(String s) throws IllegalArgumentException {
        scheme = s;
        return this;
    }

    @Override
    public UriBuilder schemeSpecificPart(String ssp) throws IllegalArgumentException {
        // scheme-specific part is whatever after ":" of URI
        // see: http://en.wikipedia.org/wiki/URI_scheme
        try {
            if (scheme == null) {
                scheme = "http";
            }
            URI uri = new URI(scheme, ssp, fragment);
            setUriParts(uri);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Wrong syntax of scheme-specific part", e);
        }
        return this;
    }

    @Override
    public UriBuilder uri(URI uri) throws IllegalArgumentException {
        setUriParts(uri);
        return this;
    }

    @Override
    public UriBuilder userInfo(String ui) throws IllegalArgumentException {
        this.userInfo = ui;
        return this;
    }

    private void setUriParts(URI uri) {
        if (uri == null) {
            throw new IllegalArgumentException("uri is null");
        }
        String theScheme = uri.getScheme();
        if (theScheme != null) {
            scheme = theScheme;
        }
        String rawPath = uri.getRawPath();
        if (!uri.isOpaque() 
            && (theScheme != null || rawPath != null && rawPath.startsWith("/"))) {
            port = uri.getPort();
            host = uri.getHost();
            if (rawPath != null) {
                setPathAndMatrix(uri.getRawPath());
            }
            String rawQuery = uri.getRawQuery();
            if (rawQuery != null) {
                query = JAXRSUtils.getStructuredParams(rawQuery, "&", false, false);
            }
            userInfo = uri.getUserInfo();
            schemeSpecificPart = null;
        } else {
            schemeSpecificPart = uri.getSchemeSpecificPart();
        }
        String theFragment = uri.getFragment();
        if (theFragment != null) {
            fragment = theFragment;
        }
    }

    private void setPathAndMatrix(String path) {
        leadingSlash = path.startsWith("/");
        paths = JAXRSUtils.getPathSegments(path, false, false);
        if (!paths.isEmpty()) {
            matrix = paths.get(paths.size() - 1).getMatrixParameters();
        } else {
            matrix.clear();
        }
    }
    
    private String buildPath() {
        StringBuilder sb = new StringBuilder();
        Iterator<PathSegment> iter = paths.iterator();
        while (iter.hasNext()) {
            PathSegment ps = iter.next();
            String p = ps.getPath();
            if (p.length() != 0 || !iter.hasNext()) {
                p = new URITemplate(p).encodeLiteralCharacters(false);
                if (sb.length() == 0 && leadingSlash) {
                    sb.append('/');
                } else if (!p.startsWith("/") && sb.length() > 0) {
                    sb.append('/');
                }
                sb.append(p);
                if (iter.hasNext()) {
                    buildMatrix(sb, ps.getMatrixParameters());
                }
            }
        }
        buildMatrix(sb, matrix);
        return sb.toString();
    }

    private String buildQuery() {
        return buildParams(query, '&');
    }

    @Override
    public UriBuilder matrixParam(String name, Object... values) throws IllegalArgumentException {
        if (name == null || values == null) {
            throw new IllegalArgumentException("name or values is null");
        }
        List<String> list = matrix.get(name);
        if (list == null) {
            matrix.put(name, toStringList(values));
        } else {
            list.addAll(toStringList(values));
        }
        return this;
    }

    @Override
    public UriBuilder queryParam(String name, Object... values) throws IllegalArgumentException {
        if (name == null || values == null) {
            throw new IllegalArgumentException("name or values is null");
        }
        List<String> list = query.get(name);
        if (list == null) {
            query.put(name, toStringList(values));
        } else {
            list.addAll(toStringList(values));
        }
        return this;
    }

    @Override
    public UriBuilder replaceMatrix(String matrixValues) throws IllegalArgumentException {
        this.matrix = JAXRSUtils.getStructuredParams(matrixValues, ";", true, false);
        return this;
    }

    @Override
    public UriBuilder replaceMatrixParam(String name, Object... values) throws IllegalArgumentException {
        if (name == null) {
            throw new IllegalArgumentException("name is null");
        }
        if (values != null && values.length >= 1 && values[0] != null) {
            matrix.put(name, toStringList(values));
        } else {
            matrix.remove(name);
        }
        return this;
    }

    @Override
    public UriBuilder replacePath(String path) {
        if (path == null) {
            clearPathAndMatrix();
        } else if (isAbsoluteUriPath(path)) {
            clearPathAndMatrix();
            uri(URI.create(path));
        } else {
            setPathAndMatrix(path);
        }
        return this;
    }

    private void clearPathAndMatrix() {
        paths.clear();
        matrix.clear();
    }
    
    private boolean isAbsoluteUriPath(String path) {
        // This is the cheapest way to figure out if a given path is an absolute 
        // URI with the http(s) scheme, more expensive way is to always convert 
        // a path to URI and check if it starts from some scheme or not
        
        // Given that the list of schemes can be open-ended it is recommended that
        // UriBuilder.fromUri is called instead for schemes like 'file', 'jms', etc
        // be supported though the use of non-http schemes for *building* new URIs
        // is pretty limited in the context of working with JAX-RS services
         
        return path.startsWith("http");
    }
    
    @Override
    public UriBuilder replaceQuery(String queryValue) throws IllegalArgumentException {
        if (queryValue != null) {
            // workaround to do with a conflicting and confusing requirement where spaces 
            // passed as part of replaceQuery are encoded as %20 while those passed as part 
            // of quertyParam are encoded as '+'
            queryValue = queryValue.replace(" ", "%20");
        }
        query = JAXRSUtils.getStructuredParams(queryValue, "&", false, true);
        return this;
    }

    @Override
    public UriBuilder replaceQueryParam(String name, Object... values) throws IllegalArgumentException {
        if (name == null) {
            throw new IllegalArgumentException("name is null");
        }
        if (values != null && values.length >= 1 && values[0] != null) {
            query.put(name, toStringList(values));
        } else {
            query.remove(name);
        }
        return this;
    }

    @Override
    public UriBuilder segment(String... segments) throws IllegalArgumentException {
        if (segments == null) {
            throw new IllegalArgumentException("Segments should not be null");
        }
        for (String segment : segments) {
            doPath(segment, false);
        }
        return this;
    }

    /**
     * Query or matrix params convertion from object values vararg to list of strings. No encoding is
     * provided.
     * 
     * @param values entry vararg values
     * @return list of strings
     * @throws IllegalArgumentException when one of values is null
     */
    private List<String> toStringList(Object... values) throws IllegalArgumentException {
        List<String> list = new ArrayList<String>();
        if (values != null) {
            for (int i = 0; i < values.length; i++) {
                Object value = values[i];
                if (value == null) {
                    throw new IllegalArgumentException("Null value on " + i + " position");
                }
                list.add(value.toString());
            }
        }
        if (list.isEmpty()) {
            list.add("");
        }
        return list;
    }

    /**
     * Builds param string for query part or matrix part of URI.
     * 
     * @param map query or matrix multivalued map
     * @param separator params separator, '&' for query ';' for matrix
     * @param fromEncoded if true then values will be decoded 
     * @return stringified params.
     */
    private String buildParams(MultivaluedMap<String, String> map, char separator) {
        boolean isQuery = separator == '&';
        StringBuilder b = new StringBuilder();
        for (Iterator<Map.Entry<String, List<String>>> it = map.entrySet().iterator(); it.hasNext();) {
            Map.Entry<String, List<String>> entry = it.next();
            for (Iterator<String> sit = entry.getValue().iterator(); sit.hasNext();) {
                String val = sit.next();
                boolean templateValue = val.startsWith("{") && val.endsWith("}");
                if (!templateValue) { 
                    val = HttpUtils.encodePartiallyEncoded(val, isQuery);
                } else {
                    val = new URITemplate(val).encodeLiteralCharacters(isQuery);
                }
                b.append(entry.getKey());
                if (val.length() != 0) {
                    b.append('=').append(val);
                }
                if (sit.hasNext() || it.hasNext()) {
                    b.append(separator);
                }
            }
        }
        return b.length() > 0 ? b.toString() : null;
    }
    
    /**
     * Builds param string for matrix part of URI.
     * 
     * @param sb buffer to add the matrix part to, will get ';' added if map is not empty 
     * @param map matrix multivalued map
     */    
    private void buildMatrix(StringBuilder sb, MultivaluedMap<String, String> map) {
        if (!map.isEmpty()) {
            sb.append(';');
            sb.append(buildParams(map, ';'));
        }
    }
    
    private PathSegment replacePathSegment(PathSegment ps) {
        StringBuilder sb = new StringBuilder();
        sb.append(ps.getPath());
        buildMatrix(sb, matrix);
        return new PathSegmentImpl(sb.toString());
    }
}
